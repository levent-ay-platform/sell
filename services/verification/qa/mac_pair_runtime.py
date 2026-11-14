"""Real Mac v5 + Vision input adapter for the existing TypeScript matcher."""
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import threading
import time

ROOT=Path(__file__).resolve().parents[3]
MAX_TOTAL=12*1024*1024

def identity_document(fields):
    confidence=fields['confidencePercent']/100
    lines=[]
    # Existing matcher understands this explicit combined Arabic label.
    # Never manufacture a full name from an absent component.
    if fields['surname'] is not None and fields['givenName'] is not None:
        lines.append('اللقب والاسم: '+fields['surname']+' '+fields['givenName'])
    if fields['cinFull'] is not None:lines.append('CIN: '+fields['cinFull'])
    return {'format':'image','mayApproveOwnership':False,'pages':[{'page':1,
        'method':'ocr','lines':[{'text':t,'page':1,'confidence':confidence,
            'source':'ocr-ar'} for t in lines]}]}

def sniff(data):
    if data[:5]==b'%PDF-':return 'application/pdf'
    if data[:8]==b'\x89PNG\r\n\x1a\n':return 'image/png'
    if data[:3]==b'\xff\xd8\xff':return 'image/jpeg'
    if data[:4] in (b'II*\x00',b'MM\x00*'):return 'image/tiff'
    raise ValueError('unsupported file')

def frame(data):
    if len(data)<56 or len(data)>MAX_TOTAL or data[0]!=1 or not 1<=data[1]<=8:raise ValueError('bad frame')
    offset=2;files=[]
    for i in range(data[1]+1):
        if offset+4>len(data):raise ValueError('bad length')
        size=struct.unpack_from('>I',data,offset)[0];offset+=4
        if size<20 or size>MAX_TOTAL or offset+size>len(data):raise ValueError('bad length')
        content=data[offset:offset+size];offset+=size
        files.append(('IDENTITY' if i==data[1] else 'DOCUMENT',sniff(content),content))
    if offset!=len(data) or files[-1][1] not in ('image/jpeg','image/png') or len(files[-1][2])>6*1024*1024:raise ValueError('bad identity')
    if len(files)>2 and any(m=='application/pdf' for _,m,_ in files[:-1]):raise ValueError('mixed pdf')
    return files

class MacPairRuntime:
    def __init__(self,pilot,vision,node):
        self.pilot=pilot;self.vision=str(vision);self.node=str(node)
        self.lock=threading.Lock()
    def compare(self,data):
        files=frame(data)
        if not self.lock.acquire(blocking=False):raise RuntimeError('busy')
        deadline=time.monotonic()+165
        def remaining():
            n=deadline-time.monotonic()
            if n<=0:raise RuntimeError('timeout')
            return n
        try:
            docs=[]
            with tempfile.TemporaryDirectory(prefix='malak-ocr-') as directory:
                os.chmod(directory,0o700)
                for index,(_,mime,content) in enumerate(files[:-1]):
                    path=Path(directory)/str(index)
                    path.write_bytes(content);os.chmod(path,0o600)
                    result=subprocess.run([self.vision,str(path)],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=remaining(),check=True)
                    if len(result.stdout)>4_000_000:raise ValueError('OCR size')
                    doc=json.loads(result.stdout)
                    if len(doc.get('pages',[]))+sum(len(x['pages']) for x in docs)>8:raise ValueError('too many pages')
                    docs.append(doc)
                fields=self.pilot.read(files[-1][2],files[-1][1])
                front=identity_document(fields)
                payload=json.dumps({'documents':docs,'front':front},ensure_ascii=False).encode()
                result=subprocess.run([self.node,'--experimental-strip-types',str(ROOT/'services/verification/qa/compare_mac_pair.mjs')],input=payload,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=remaining(),check=True)
                if len(result.stdout)>64000:raise ValueError('result size')
                report=json.loads(result.stdout)
                report['identityFields']={k:fields[k] for k in ('surname','givenName','cinFull','birthDate','confidencePercent')}
                return files,report
        finally:self.lock.release()
