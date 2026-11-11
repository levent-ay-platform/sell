/**
 * Synthetic-only local HTTP E2E:
 * certificate/CIN PDFs -> authenticated upload -> encrypted private store
 * -> scoped receipt lookup -> AES-GCM -> REAL Poppler/Python OCR -> pair report.
 * ClamAV, SQL and Vault have independent live integration gates; do not
 * mistake these deliberately injected equivalents for production readiness.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {createServer,request} from 'node:http';
import {once} from 'node:events';
import {mkdtemp,readdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createPrivateEvidenceHttpHandler} from '../src/private-evidence-http.ts';
import {uploadIndividualEvidence} from '../src/evidence-upload.ts';
import {openPrivateEvidenceStore} from '../src/local-private-store.ts';
import {createPrivatePythonOcrWorker} from '../src/private-ocr-worker.ts';
import {analyzeAuthorizedPrivateEvidencePair} from '../src/private-pair-service.ts';
import type {EvidenceRow,AuthorizedUploadCase,CaseEvidenceRepository,EvidenceKind} from '../src/evidence-upload.ts';

if(process.env.MALAK_SYNTHETIC_OCR!=='yes')throw Error('SYNTHETIC_CI_ONLY');
const script=fileURLToPath(new URL('../ocr/ingest.py',import.meta.url));
const userId=randomUUID(),caseId=randomUUID();
const bearer='synthetic-ocr-pair-private-session-0123456789';
function pdf(pages:readonly (readonly string[])[]):Buffer {
 const n=pages.length,font=3+n;
 const pageObj=(i:number)=>3+i,streamObj=(i:number)=>4+n+i;
 const objs:string[]=[
  '<< /Type /Catalog /Pages 2 0 R >>',
  '<< /Type /Pages /Kids ['+pages.map((_p,i)=>pageObj(i)+' 0 R').join(' ')+'] /Count '+n+' >>',
  ...pages.map((_p,i)=>'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 '+font+' 0 R >> >> /Contents '+streamObj(i)+' 0 R >>'),
  '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ...pages.map(p=>{
    const content=p.map((line,i)=>'BT /F1 14 Tf 60 '+(730-i*28)+' Td ('+
      line.replace(/[()\\]/g,'')+') Tj ET').join('\n');
    return '<< /Length '+Buffer.byteLength(content)+' >>\nstream\n'+content+'\nendstream';
  })
 ];
 let body='%PDF-1.4\n';const offsets:number[]=[0];
 for(let i=0;i<objs.length;i++){
  offsets.push(Buffer.byteLength(body));
  body+=(i+1)+' 0 obj\n'+objs[i]+'\nendobj\n';
 }
 const ptr=Buffer.byteLength(body);
 body+='xref\n0 '+offsets.length+'\n0000000000 65535 f \n';
 for(const n of offsets.slice(1))body+=String(n).padStart(10,'0')+' 00000 n \n';
 body+='trailer\n<< /Size '+offsets.length+' /Root 1 0 R >>\nstartxref\n'+ptr+'\n%%EOF\n';
 return Buffer.from(body);
}
/** Synthetic-only images, generated in-memory by pinned CI Pillow. The
 * backend then uses actual Python/Tesseract OCR, never precomputed responses. */
function syntheticPng(lines:readonly string[]):Buffer {
 if(process.env.MALAK_SYNTHETIC_OCR!=='yes'||!process.env.MALAK_OCR_PYTHON)
   throw Error('SYNTHETIC_IMAGE_ONLY');
 const python=`from PIL import Image,ImageFont,ImageDraw
from io import BytesIO
import sys
image=Image.new("RGB",(1600,940),"white")
draw=ImageDraw.Draw(image)
font=ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",50)
for i,text in enumerate(sys.argv[1:]):
 draw.text((75,120+i*145),text,fill="black",font=font)
target=BytesIO()
image.save(target,format="PNG")
sys.stdout.buffer.write(target.getvalue())
`;
 const result=spawnSync(process.env.MALAK_OCR_PYTHON,['-c',python,...lines],{
   timeout:15000,maxBuffer:2*1024*1024
 });
 if(result.status!==0||result.error||result.stdout.length<100)
   throw Error('SYNTHETIC_IMAGE_GENERATION_FAILED');
 return result.stdout;
}
function http(port:number,url:string,body:Buffer,headers:Record<string,string>={}){
 return new Promise<{status:number;body:any}>((resolve,reject)=>{
  const req=request({hostname:'127.0.0.1',port,path:url,method:'POST',
   headers:{Authorization:'Bearer '+bearer,'Content-Type':'application/octet-stream',
    'Content-Length':String(body.length),...headers}},res=>{
     const chunks:Buffer[]=[];res.on('data',chunk=>chunks.push(chunk));res.on('end',()=>{
       try{resolve({status:res.statusCode??0,body:JSON.parse(Buffer.concat(chunks).toString('utf8'))});}
       catch(err){reject(err);}
     });
   });
   req.setTimeout(60000,()=>req.destroy(Error('SYNTHETIC_HTTP_TIMEOUT')));
   req.on('error',reject);req.end(body);
 });
}
test('real two-PDF identity flow over local HTTP: matching, differing, revoked, sealed and cleaned',async()=>{
 const tmpOcr=await mkdtemp(join(tmpdir(),'malak-ocr-pair-ci-'));
 const tmpStore=await mkdtemp(join(tmpdir(),'malak-sealed-pair-ci-'));
 const store=await openPrivateEvidenceStore(tmpStore);
 const worker=createPrivatePythonOcrWorker({tmpRoot:tmpOcr,scriptPath:script,
   pythonPath:process.env.MALAK_OCR_PYTHON??'/usr/bin/python3',timeoutMs:30000});
 let consent:AuthorizedUploadCase|null={id:caseId,claimantKind:'PERSON',
   actorMayUpload:true,status:'PENDING',
   consent:{personUserId:userId,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',
    policyVersion:'synthetic-ci-only',acceptedAt:'2026-11-11T00:00:00Z',revokedAt:null}};
 const auth={authenticate:async(t:string)=>t===bearer?{userId}:null};
 const uploaded:EvidenceRow[]=[];
 const keys=new Map<string,Uint8Array>();
 const cases:CaseEvidenceRepository & {getPrivateEvidence:(c:string,u:string,e:string)=>Promise<EvidenceRow|null>}={
   inCaseLock:async(_caseId,action)=>action(),
   getUploadCase:async()=>consent,
   findReplay:async(c,u,k)=>uploaded.find(row=>row.caseId===c&&row.uploadedBy===u&&row.idempotencyKey===k)??null,
   attachEvidence:async row=>{uploaded.push(row);},
   getPrivateEvidence:async(c,u,e)=>uploaded.find(row=>
     row.caseId===c&&row.uploadedBy===u&&row.evidenceId===e)??null
 };
 const uploadDeps={auth,cases,objects:store,
   scanner:{scan:async()=> 'CLEAN' as const}, // independently tested live ClamAV
   generateDataKey:async({evidenceId}:{caseId:string;evidenceId:string})=>{
     const privateKey=randomBytes(32);keys.set(evidenceId,Buffer.from(privateKey));
     return {plaintextKey:privateKey,wrappedKey:'SYNTHETIC_ONLY_WRAPPED_'+evidenceId,
       keyVersion:'v1'};
   }
 };
 const pairDeps={auth,cases,objects:store,worker,
   keys:{unwrapDataKey:async({evidenceId}:{evidenceId:string})=>{
     const key=keys.get(evidenceId);if(!key)throw Error('MISSING_SYNTHETIC_KEY');
     return Buffer.from(key);
   }}
 };
 const events:any[]=[];
 const server=createServer(createPrivateEvidenceHttpHandler({auth,
   limits:{consume:async()=>true},audit:{recordDenied:async event=>{events.push(event);}},
   upload:req=>uploadIndividualEvidence(req,uploadDeps),
   inspect:async()=>({ok:false as const,code:'ACCESS_DENIED' as const,
     ownershipVerified:false as const,mayPublish:false as const}),
   analyzeOwnerPair:req=>analyzeAuthorizedPrivateEvidencePair(req,pairDeps)
 }));
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const addr=server.address();if(!addr||typeof addr==='string')throw Error('NO_PORT');
 const base='/internal/v1/evidence/'+caseId;
 let serial=0;
 async function upload(bytes:Buffer,kind:EvidenceKind,
   mime:'application/pdf'|'image/png'='application/pdf',page:number|null=null){
   const result=await http(addr!.port,base+'/upload',bytes,{
     'X-Idempotency-Key':'synthetic_pair_upload_'+(++serial),
     'X-Evidence-Kind':kind,'X-Page-Number':page===null?'null':String(page),
     'X-Claimed-Mime':mime
   });
   assert.equal(result.status,200,JSON.stringify(result.body));
   assert.equal(result.body.ownershipVerified,false);
   assert.equal(result.body.mayPublish,false);
   return result.body.evidenceId as string;
 }
 async function compare(cert:string,id:string|string[]){
   const ids=Array.isArray(id)?id:[id];
   const body=Buffer.from(JSON.stringify({certificateEvidenceIds:[cert],identityEvidenceIds:ids}));
   return http(addr!.port,base+'/compare-pair',body,{'Content-Type':'application/json'});
 }
 try{
   const certId=await upload(pdf([['Certificat de propriete','Proprietaire: Amira Ben Salem',
     'CIN: 12345678','Titre foncier: 987654']]),'OWNERSHIP_CERTIFICATE');
   const sameId=await upload(pdf([
     ['Carte nationale identite','Nom et prenom: Amira Ben Salem','CIN: 12345678'],
     ['Verso document exemple','Adresse: exemple imaginaire']
   ]),'IDENTITY');
   const yes=await compare(certId,sameId);
   assert.equal(yes.status,200,JSON.stringify({status:yes.status,code:yes.body.code}));
   assert.equal(yes.body.report.comparison.assessment,'PRELIMINARY_TEXT_AGREEMENT');
   assert.equal(yes.body.report.cinComparison.status,'SAME_NUMBER_UNVERIFIED');
   assert.equal(yes.body.report.identitySides,'MULTI_PAGE_UNCONFIRMED');
   assert.equal(yes.body.report.identityPagesReceived,2);
   assert.equal(yes.body.report.ownershipVerified,false);
   assert.equal(yes.body.report.identityVerified,false);
   assert.equal(yes.body.report.mayPublish,false);
   assert.ok(!JSON.stringify(yes.body.report.cinComparison).includes('12345678'));
   // Real two-photo path: HTTP upload -> immutable sealed store -> Python OCR
   // on each image -> ordered page receipts -> private comparison.
   const front=syntheticPng([
     'CARTE NATIONALE IDENTITE',
     'Nom et prenom: Amira Ben Salem',
     'CIN: 12345678'
   ]);
   const back=syntheticPng([
     'VERSO CARTE NATIONALE',
     'Adresse: Exemple Fictif'
   ]);
   const frontId=await upload(front,'IDENTITY','image/png',1);
   const backId=await upload(back,'IDENTITY','image/png',2);
   front.fill(0);back.fill(0);
   const imagePair=await compare(certId,[frontId,backId]);
   assert.equal(imagePair.status,200,JSON.stringify({status:imagePair.status,
     code:imagePair.body.code}));
   assert.equal(imagePair.body.report.identityPagesReceived,2);
   assert.equal(imagePair.body.report.identitySides,'TWO_FILES_UNCONFIRMED');
   assert.equal(imagePair.body.report.comparison.assessment,'PRELIMINARY_TEXT_AGREEMENT');
   assert.equal(imagePair.body.report.cinComparison.status,'SAME_NUMBER_UNVERIFIED');
   assert.equal(imagePair.body.report.ownershipVerified,false);
   assert.equal(imagePair.body.report.identityVerified,false);
   assert.equal(imagePair.body.report.mayPublish,false);
   const reversed=await compare(certId,[backId,frontId]);
   assert.equal(reversed.status,403);
   assert.equal(reversed.body.report,undefined);
   const duplicate=await compare(certId,[frontId,frontId]);
   assert.equal(duplicate.status,400);
   const missingBack=await compare(certId,frontId);
   assert.equal(missingBack.status,200);
   assert.equal(missingBack.body.report.identitySides,'MISSING_REVERSE');
   assert.equal(missingBack.body.report.comparison.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
   const differentId=await upload(pdf([
     ['Carte nationale identite','Nom et prenom: Amira Ben Salem','CIN: 87654321'],
     ['Verso document exemple','Adresse: autre exemple']
   ]),'IDENTITY');
   const no=await compare(certId,differentId);
   assert.equal(no.status,200);
   assert.equal(no.body.report.comparison.assessment,'TEXT_DIFFERENCE_NEEDS_REVIEW');
   assert.equal(no.body.report.cinComparison.status,'NUMBER_DIFFERS_NEEDS_REVIEW');
   const row=uploaded.find(x=>x.evidenceId===certId)!;
   const sealed=await store.readPrivate(row.objectKey);
   assert.equal(Buffer.from(sealed).subarray(0,10).toString('ascii'),'MALAK-EV1\0');
   assert.equal(Buffer.from(sealed).includes(Buffer.from('Amira Ben Salem')),false);
   sealed.fill(0);
   consent=null;
   const revoked=await compare(certId,sameId);
   assert.equal(revoked.status,403);assert.equal(revoked.body.report,undefined);
   assert.equal(events.at(-1)?.reason,'FORBIDDEN');
   assert.deepEqual(await readdir(tmpOcr),[]);
 }finally{
   server.closeAllConnections();
   await new Promise<void>(res=>server.close(()=>res()));
   for(const key of keys.values())key.fill(0);
   await rm(tmpOcr,{recursive:true,force:true});
   await rm(tmpStore,{recursive:true,force:true});
 }
});
