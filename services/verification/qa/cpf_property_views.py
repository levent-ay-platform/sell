"""Bounded private CPF property-only OCR views. Never mutate identity/plan evidence."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import unicodedata

def norm(text):
    return ''.join(c for c in unicodedata.normalize('NFKC', text)
        if unicodedata.category(c) not in ('Cf','Mn')).replace('ـ','').replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي')

def is_certificate(doc):
    pages=doc.get('pages',[])
    if not pages:return False
    return any(re.search(r'شهاد[ةه]\s+(?:(?:اشتراك\s+في\s+)?(?:ال)?ملكي[ةه])',norm(l.get('text','')))
        for l in pages[0].get('lines',[]) if isinstance(l.get('text'),str))

def property_regions(page, width, height):
    rows=page.get('lines',[])
    sw=page.get('pageImageWidth');sh=page.get('pageImageHeight')
    if type(sw) is not int or type(sh) is not int or min(sw,sh)<175:return []
    def box(line):
        b=line.get('bbox')
        if not isinstance(b,dict):return None
        try:
            x0=max(0,int(b['left']*width/sw)-25);x1=min(width,int(b['right']*width/sw)+25)
            y0=max(0,int(b['top']*height/sh)-8);y1=min(height,int(b['bottom']*height/sh)+8)
            return (x0,y0,x1,y1) if x1>x0 and y1>y0 else None
        except (KeyError,TypeError,ValueError):return None
    area=[];plot=[]
    for i,row in enumerate(rows):
        text=norm(row.get('text',''))
        if box(row) is None:continue
        if re.search(r'التجزئ',text):area.append(i)
        if re.search(r'القطع\s+عدد',text):plot.append(i)
    # Both distinct property-table anchors required; no broad fallback crop.
    if len(area)!=1 or len(plot)!=1:return []
    a,b=area[0],plot[0]
    if abs(a-b)>12:return []
    lo=max(0,min(a,b)-2);hi=min(len(rows),max(a,b)+3)
    regions=[box(row) for row in rows[lo:hi]]
    regions=[q for q in regions if q is not None]
    y0=max(0,min(q[1] for q in regions)-25)
    y1=min(height,max(q[3] for q in regions)+25)
    if y1-y0>height*.32:return []
    return [(0,y0,width,y1),box(rows[a]),box(rows[b])]

def recover_property_views(doc,source,mime,rasters,vision,budget):
    if budget<8 or not is_certificate(doc):return []
    deadline=time.monotonic()+min(40,budget)
    def run(args,limit):
        left=deadline-time.monotonic()
        if left<1:raise TimeoutError()
        # Crop scans must not replace original page renderings.
        env=dict(os.environ);env.pop('MALAK_VISION_PLAN_RASTERS',None)
        p=subprocess.run([str(a) for a in args],env=env,stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,timeout=min(left,limit),check=True)
        if len(p.stdout)>4_000_000:raise ValueError('OCR size')
        return p.stdout
    def scan(path):
        return json.loads(run([vision,path],8))
    views=[]
    try:
        from PIL import Image,ImageOps,ImageFilter
        raster=rasters/'1.png'
        if not raster.is_file():
            raster=rasters/'cpf-first.png'
            if mime=='application/pdf':
                run(['/usr/bin/swift',Path(__file__).with_name('cpf_render_first_page.swift'),source,raster],16)
            else:
                with Image.open(source) as raw:
                    if raw.width*raw.height>25_000_000:return []
                    image=ImageOps.exif_transpose(raw).convert('RGB')
                    image.thumbnail((2400,2400),Image.Resampling.LANCZOS)
                    image.save(raster)
            os.chmod(raster,0o600)
        with Image.open(raster) as raw:
            if min(raw.size)<200 or raw.width*raw.height>25_000_000:return []
            im=raw.convert('RGB')
        im.thumbnail((2400,2400),Image.Resampling.LANCZOS)
        full=rasters/'cpf-full.png';im.save(full);os.chmod(full,0o600)
        fresh=scan(full)['pages'][0]
        regions=property_regions(fresh,im.width,im.height)
        # Read both original and enhanced band/anchor rows, retaining
        # disagreements rather than selecting the majority of one image.
        for q in regions:
            for contrast in (False,True):
                try:
                    crop=im.crop(q);scale=min(3.5,2700/max(crop.size))
                    crop=crop.resize(tuple(max(1,round(v*scale)) for v in crop.size),Image.Resampling.LANCZOS)
                    if contrast:crop=ImageOps.autocontrast(ImageOps.grayscale(crop)).filter(ImageFilter.SHARPEN).convert('RGB')
                    canvas=Image.new('RGB',(max(200,crop.width+40),max(200,crop.height+40)),'white')
                    canvas.paste(crop,(20,20));path=rasters/'cpf-crop.png';canvas.save(path);os.chmod(path,0o600)
                    lines=[]
                    for l in scan(path)['pages'][0]['lines'][:60]:
                        text=l.get('text');confidence=l.get('confidence')
                        if isinstance(text,str) and 0<len(text)<=170 and isinstance(confidence,(int,float)) and 0<=confidence<=1:
                            # Crop-local coordinates are not full-page coordinates.
                            lines.append({'page':1,'text':text,'source':'ocr-ar','confidence':min(.5,confidence)})
                    if lines:views.append({'format':'image','mayApproveOwnership':False,
                        'pages':[{'page':1,'method':'ocr','lines':lines}]})
                except (OSError,ValueError,KeyError,IndexError,TypeError,TimeoutError,subprocess.SubprocessError):
                    continue
    except (ImportError,OSError,ValueError,KeyError,IndexError,TypeError,TimeoutError,subprocess.SubprocessError):
        pass
    return views
