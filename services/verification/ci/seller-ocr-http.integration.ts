/**
 * REAL localhost HTTP -> already-scanned synthetic PDF -> real private Python
 * pdftotext/Tesseract worker -> owner-only OCR field cards. NEVER run with real
 * CPF/CIN, CI secrets or externally routable listeners.
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import {randomUUID,randomBytes} from 'node:crypto';
import {mkdtemp,rm,readFile,readdir} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {createServer,request} from 'node:http';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {createPrivateEvidenceHttpHandler} from '../src/private-evidence-http.ts';
import {analyzeOwnerUploadedEvidence} from '../src/seller-ocr-service.ts';
import {createPrivatePythonOcrWorker} from '../src/private-ocr-worker.ts';
import {uploadIndividualEvidence} from '../src/evidence-upload.ts';
import {openPrivateEvidenceStore} from '../src/local-private-store.ts';
import type {AuthorizedUploadCase,EvidenceRow,CaseEvidenceRepository} from '../src/evidence-upload.ts';

if(process.env.MALAK_SYNTHETIC_OCR!=='yes')throw Error('SYNTHETIC_OCR_CI_ONLY');
const script=fileURLToPath(new URL('../ocr/ingest.py',import.meta.url));
const userId=randomUUID(),caseId=randomUUID();
const TOKEN='synthetic_opaque_0123456789_test_only';
const consent:AuthorizedUploadCase={id:caseId,claimantKind:'PERSON',actorMayUpload:true,status:'PENDING',
  consent:{personUserId:userId,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',policyVersion:'synthetic-test',
    acceptedAt:'2026-11-10T09:00:00Z',revokedAt:null}};
function syntheticPdf():Buffer {
  const txt='Certificat de propriete - Titre foncier: 987654 - Superficie: 240 m2 - Page 1 sur 1';
  const stream=`BT /F1 16 Tf 60 710 Td (${txt}) Tj ET`;
  const objs=[
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`,
  ];
  let s='%PDF-1.4\n';const offsets=[0];
  for(let i=0;i<objs.length;i++){offsets.push(Buffer.byteLength(s));s+=`${i+1} 0 obj\n${objs[i]}\nendobj\n`;}
  const ptr=Buffer.byteLength(s);s+=`xref\n0 ${offsets.length}\n0000000000 65535 f \n`;
  for(const o of offsets.slice(1))s+=`${String(o).padStart(10,'0')} 00000 n \n`;
  s+=`trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${ptr}\n%%EOF\n`;
  return Buffer.from(s);
}
function call(port:number,url:string,bytes:Buffer,bearer=TOKEN) {
 return new Promise<{status:number;body:any}>((resolve,reject)=>{
  const req=request({hostname:'127.0.0.1',port,path:url,method:'POST',headers:{
    Authorization:'Bearer '+bearer,'Content-Type':'application/octet-stream','Content-Length':String(bytes.length),
    'X-Idempotency-Key':'synthetic_ci_paper_owner_01','X-Evidence-Kind':'OWNERSHIP_CERTIFICATE',
    'X-Page-Number':'null','X-Claimed-Mime':'application/pdf','X-Declared-Area-M2':'240'}},r=>{
    const chunks:Buffer[]=[];r.on('data',x=>chunks.push(x));r.on('end',()=>{
      try{resolve({status:r.statusCode??0,body:JSON.parse(Buffer.concat(chunks).toString('utf8'))});}
      catch(e){reject(e);}
    });
  });
  req.setTimeout(60000,()=>req.destroy(Error('SYNTHETIC_TEST_TIMEOUT')));
  req.on('error',reject);req.end(bytes);
 });
}
test('REAL synthetic HTTPS-ready contract: owner HTTP upload -> protected Python OCR -> private facts',async()=>{
  const temp=await mkdtemp(join(tmpdir(),'malak-ci-ocr-'));
  const storeRoot=await mkdtemp(join(tmpdir(),'malak-ci-sealed-'));
  const store=await openPrivateEvidenceStore(storeRoot);
  const worker=createPrivatePythonOcrWorker({tmpRoot:temp,scriptPath:script,
    pythonPath:process.env.MALAK_OCR_PYTHON??'/usr/bin/python3',timeoutMs:30000});
  const auth={authenticate:async (token:string)=>token===TOKEN?{userId}:null};
  let currentConsent:AuthorizedUploadCase|null=consent;let scans=0;
  const events:any[]=[];const receipts:EvidenceRow[]=[];
  const cases:CaseEvidenceRepository={
    inCaseLock:async(_caseId,action)=>action(),
    getUploadCase:async()=>currentConsent,
    findReplay:async(c,u,k)=>receipts.find(x=>x.caseId===c&&x.uploadedBy===u&&x.idempotencyKey===k)??null,
    attachEvidence:async row=>{receipts.push(row);},
  };
  const uploadDeps={auth,cases,objects:store,
    scanner:{scan:async()=>{scans++;return 'CLEAN' as const;}},
    generateDataKey:async()=>({plaintextKey:Uint8Array.from(randomBytes(32)),
      wrappedKey:'SYNTHETIC_WRAPPED_KEY_FOR_CI_ONLY',keyVersion:'v1'})
  };
  const deps={auth,cases,worker};
  const server=createServer(createPrivateEvidenceHttpHandler({auth,
    limits:{consume:async()=>true},audit:{recordDenied:async e=>{events.push(e);}},
    upload:request=>uploadIndividualEvidence(request,uploadDeps),
    inspect:async()=>({ok:false as const,code:'ACCESS_DENIED' as const,mayPublish:false as const,ownershipVerified:false as const}),
    analyzeOwnerUpload:request=>analyzeOwnerUploadedEvidence(request,deps),
  }));
  server.listen(0,'127.0.0.1');await once(server,'listening');
  const address=server.address();if(!address||typeof address==='string')throw Error('NO_PORT');
  try {
    const pdf=syntheticPdf();
    const r=await call(address.port,`/internal/v1/evidence/${caseId}/upload-ocr`,pdf);
    assert.equal(r.status,200,JSON.stringify({status:r.status,code:r.body.code,ocr:r.body.ocrStatus}));
    assert.equal(r.body.ok,true);assert.equal(r.body.ocrStatus,'READY');
    assert.equal(r.body.reading.access,'AUTHORIZED_OWNER_OR_REVIEWER_ONLY');
    assert.equal(r.body.reading.facts.find((f:any)=>f.id==='areaSquareMetres').value,'240');
    assert.equal(r.body.reading.facts.find((f:any)=>f.id==='registryReference').value,'987654');
    assert.equal(r.body.reading.officialCpfOwnershipCheck,'NOT_STARTED');
    assert.equal(r.body.mayPublish,false);assert.equal(r.body.ownershipVerified,false);
    assert.equal(scans,1);assert.equal(events.length,0);assert.equal(receipts.length,1);
    assert.equal(receipts[0].evidenceId,r.body.evidenceId);
    const encrypted=await readFile(join(storeRoot,'private','evidence',
      receipts[0].objectKey.split('/').at(-1)+'.sealed'));
    assert.equal(encrypted.subarray(0,10).toString('ascii'),'MALAK-EV1\0');
    assert.equal(encrypted.includes(Buffer.from('987654')),false);
    assert.equal(encrypted.includes(pdf),false);
    const denied=await call(address.port,`/internal/v1/evidence/${caseId}/upload-ocr`,pdf,'x'.repeat(30));
    assert.equal(denied.status,401);assert.equal(scans,1);assert.equal(events.length,1);
    assert.ok(!JSON.stringify(denied).includes('987654'));
    currentConsent={...consent,consent:null};
    const revoked=await call(address.port,`/internal/v1/evidence/${caseId}/upload-ocr`,pdf);
    assert.equal(revoked.status,403);assert.equal(events.at(-1)?.reason,'FORBIDDEN');
    assert.equal(revoked.body.ok,false);
    const storedTmp=await readdir(temp);
    assert.deepEqual(storedTmp,[],'sensitive tmp files removed');
  } finally {server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));await rm(temp,{recursive:true,force:true});await rm(storeRoot,{recursive:true,force:true});}
});
