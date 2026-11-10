import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer,request} from 'node:http';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {createPrivateEvidenceHttpHandler} from '../src/private-evidence-http.ts';
import {PrivateOcrAccessDenied} from '../src/seller-ocr-service.ts';
import type {PrivateEvidenceHttpDeps,DenialAuditRecord} from '../src/private-evidence-http.ts';

const caseId=randomUUID(),evidenceId=randomUUID(),userId=randomUUID();
const token='private_qa_seller_0123456789';
const pdf=Buffer.from('%PDF-1.4\nSYNTHETIC_PRIVATE_REQUEST_ONLY\n%%EOF');
const path=`/internal/v1/evidence/${caseId}/upload-ocr`;
const reading={schemaVersion:1 as const,access:'AUTHORIZED_OWNER_OR_REVIEWER_ONLY' as const,
  documentKind:'CPF_OWNERSHIP_CERTIFICATE' as const,pagesReceived:1,
  missingPages:[],qualityReviewPages:[],facts:[],
  declaredAreaComparison:'NOT_COMPARABLE' as const,
  ownerIdentityMatch:'NOT_STARTED' as const,officialCpfOwnershipCheck:'NOT_STARTED' as const,
  requiresIndependentReview:true as const,ownershipVerified:false as const,mayPublish:false as const};
async function withHttp(run:(port:number,events:DenialAuditRecord[],state:{calls:number;read:number})=>Promise<void>,
  analyzer:PrivateEvidenceHttpDeps['analyzeOwnerUpload']|null=(async()=>reading)) {
 const events:DenialAuditRecord[]=[];const state={calls:0,read:0};
 const handler=createPrivateEvidenceHttpHandler({
  auth:{authenticate:async t=>t===token?{userId}:null},
  audit:{recordDenied:async e=>{events.push(e);}},limits:{consume:async()=>true},
  upload:async()=>{state.calls++;return {ok:true,evidenceId,uploaded:true,ownershipVerified:false,mayPublish:false};},
  inspect:async()=>({ok:false,code:'ACCESS_DENIED',ownershipVerified:false,mayPublish:false}),
  ...(analyzer?{analyzeOwnerUpload:async arg=>{state.read++;return analyzer(arg);}}:{}),
 });
 const server=createServer(handler);server.listen(0,'127.0.0.1');await once(server,'listening');
 const addr=server.address();if(!addr||typeof addr==='string')throw Error('NO_ADDRESS');
 try {await run(addr.port,events,state);}finally{server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));}
}
function send(port:number,headers:Record<string,string|string[]>={},url=path) {
 return new Promise<{status:number;body:any}>((resolve,reject)=>{
  const req=request({hostname:'127.0.0.1',port,path:url,method:'POST',headers:{
   Authorization:'Bearer '+token,'Content-Type':'application/octet-stream',
   'Content-Length':String(pdf.length),'X-Idempotency-Key':'synthetic_ci_doc_00001',
   'X-Evidence-Kind':'OWNERSHIP_CERTIFICATE','X-Page-Number':'null','X-Claimed-Mime':'application/pdf',
   'X-Declared-Area-M2':'240',...headers}},res=>{
    const chunks:Buffer[]=[];res.on('data',x=>chunks.push(x));res.on('end',()=>{
      try{resolve({status:res.statusCode??0,body:JSON.parse(Buffer.concat(chunks).toString())});}catch(err){reject(err);}
    });
  });req.setTimeout(2000,()=>req.destroy(Error('TIMEOUT')));req.on('error',reject);req.end(pdf);
 });
}
test('OCR route returns typed private field DTO only after a successful upload',async()=>{
 await withHttp(async(port,events,state)=>{
  const r=await send(port);assert.equal(r.status,200);
  assert.equal(r.body.ocrStatus,'READY');assert.equal(r.body.reading.schemaVersion,1);
  assert.equal(r.body.mayPublish,false);assert.equal(r.body.ownershipVerified,false);
  assert.equal(state.calls,1);assert.equal(state.read,1);assert.equal(events.length,0);
  assert.ok(!JSON.stringify(r.body).includes('SYNTHETIC_PRIVATE_REQUEST_ONLY'));
 });
});
test('OCR consent revoked after encrypted upload => no extracted fields',async()=>{
 await withHttp(async(port,events,state)=>{
  const r=await send(port);assert.equal(r.status,403);
  assert.equal(r.body.ok,false);assert.equal(r.body.reading,undefined);
  assert.equal(events[0]?.route,'OCR');assert.equal(events[0]?.reason,'FORBIDDEN');
  assert.equal(state.calls,1);assert.equal(state.read,1);
 },async()=>{throw new PrivateOcrAccessDenied();});
});
test('OCR down returns 202 with retained opaque evidence ID, never a fake result',async()=>{
 await withHttp(async(port,events,state)=>{
  const r=await send(port);assert.equal(r.status,202);
  assert.equal(r.body.evidenceId,evidenceId);assert.equal(r.body.ocrStatus,'UNAVAILABLE');
  assert.equal(r.body.reading,undefined);assert.equal(events.length,0);
  assert.equal(state.calls,1);assert.equal(state.read,1);
 },async()=>{throw Error('synthetic python process unavailable');});
});
test('OCR route remains disabled when no private analyzer is injected',async()=>{
 await withHttp(async(port,events,state)=>{
  const r=await send(port);assert.equal(r.status,503);assert.equal(state.calls,0);
  assert.equal(events[0]?.route,'OCR');
 },null);
});
test('invalid area and repeated declared-area headers are rejected before upload',async()=>{
 await withHttp(async(port,events,state)=>{
  assert.equal((await send(port,{'X-Declared-Area-M2':'area-with-client-identity'})).status,400);
  assert.equal((await send(port,{'X-Declared-Area-M2':['240','240']})).status,400);
  assert.equal(state.calls,0);assert.equal(events.length,2);
 });
});
