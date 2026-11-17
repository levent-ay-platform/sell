import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {createServer,request} from 'node:http';
import {once} from 'node:events';
import {createPrivateEvidenceHttpHandler} from '../src/private-evidence-http.ts';
import {PrivatePairAccessDenied} from '../src/private-pair-service.ts';
import type {PrivatePairRequest,PrivatePairReport} from '../src/private-pair-service.ts';

const userId=randomUUID(),caseId=randomUUID(),cert=randomUUID(),identity=randomUUID();
const bearer='synthetic-secure-session-token-12345';
const route='/internal/v1/evidence/'+caseId+'/compare-pair';
const payload={certificateEvidenceIds:[cert],identityEvidenceIds:[identity]};
const preliminary:PrivatePairReport={
 access:'AUTHORIZED_CASE_OWNER_ONLY',certificatePagesReceived:1,identityPagesReceived:1,
 identityCapture:'FRONT_IMAGE_ONLY_UNVERIFIED',
 comparison:{
   assessment:'PRELIMINARY_TEXT_AGREEMENT',ownershipVerified:false,identityVerified:false,
   mayPublish:false,requiresHumanReview:true,
   certificateName:{value:'Amira Ben Salem',page:1,source:'pdf-text',confidence:null},
   identityName:{value:'Amira Ben Salem',page:1,source:'pdf-text',confidence:null},
   reasons:['OFFICIAL_CPF_AND_DOCUMENT_AUTHENTICITY_NOT_CHECKED']
 },
 cinComparison:{status:'NOT_COMPARABLE',certificateEvidence:null,identityEvidence:null,
   mayVerifyIdentity:false},
 ownershipVerified:false,identityVerified:false,mayPublish:false,requiresHumanReview:true
};
function call(port:number,body:string,token=bearer,contentType='application/json'){
 return new Promise<{status:number;body:any}>((resolve,reject)=>{
  const req=request({hostname:'127.0.0.1',port,path:route,method:'POST',
   headers:{Authorization:'Bearer '+token,'Content-Type':contentType,
    'Content-Length':String(Buffer.byteLength(body))}},res=>{
    const chunks:Buffer[]=[];res.on('data',part=>chunks.push(part));res.on('end',()=>{
      try{resolve({status:res.statusCode??0,body:JSON.parse(Buffer.concat(chunks).toString())});}
      catch(e){reject(e);}
    });
  });
  req.on('error',reject);req.end(body);
 });
}
async function withServer(run:(p:number,seen:PrivatePairRequest[],audit:any[])=>Promise<void>,
 handler:((x:PrivatePairRequest)=>Promise<PrivatePairReport>)|null=async()=>preliminary,
 pilot=false){
 const seen:PrivatePairRequest[]=[],audit:any[]=[];
 const server=createServer(createPrivateEvidenceHttpHandler({
  auth:{authenticate:async t=>t===bearer?
    (pilot?{userId,pilotUnverified:true as const}:{userId}):null},
  limits:{consume:async()=>true},
  audit:{recordDenied:async e=>{audit.push(e);}},
  upload:async()=>{throw Error('PAIR_MUST_NOT_UPLOAD');},
  inspect:async()=>{throw Error('PAIR_MUST_NOT_INSPECT');},
  ...(handler?{analyzeOwnerPair:async r=>{seen.push(r);return handler(r);}}:{})
 }));
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const address=server.address();if(!address||typeof address==='string')throw Error('NO_LOCALHOST');
 try {await run(address.port,seen,audit);}
 finally {server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));}
}
test('authenticated pair POST returns private provisional report without publishing or uploading',async()=>{
 await withServer(async(port,seen,audit)=>{
  const r=await call(port,JSON.stringify(payload));
  assert.equal(r.status,200);
  assert.equal(r.body.report.comparison.assessment,'PRELIMINARY_TEXT_AGREEMENT');
  assert.equal(r.body.report.comparison.certificateName.value,'Amira Ben Salem');
  assert.equal(r.body.report.identityCapture,'FRONT_IMAGE_ONLY_UNVERIFIED');
  assert.equal(r.body.ownershipVerified,false);
  assert.equal(r.body.identityVerified,false);
  assert.equal(r.body.mayPublish,false);
  assert.equal(seen.length,1);assert.equal(seen[0]?.caseId,caseId);
  assert.equal(seen[0]?.bearerToken,bearer);
  assert.equal(audit.length,0);
 });
});
test('no valid session, no comparison and sanitized audited denial',async()=>{
 await withServer(async(port,seen,audit)=>{
  const r=await call(port,JSON.stringify(payload),'invalid-session-but-still-long-enough');
  assert.equal(r.status,401);assert.equal(seen.length,0);
  assert.equal(audit[0]?.route,'PAIR');assert.equal(audit[0]?.reason,'UNAUTHENTICATED');
  assert.ok(!JSON.stringify(r.body).includes('Amira'));
  assert.ok(!JSON.stringify(r.body).includes('invalid-session'));
 });
});
test('reject client evidence replay, unexpected keys, body type and missing evidence IDs',async()=>{
 await withServer(async(port,seen,audit)=>{
  assert.equal((await call(port,JSON.stringify({certificateEvidenceIds:[cert],
    identityEvidenceIds:[cert]}))).status,400);
  assert.equal((await call(port,JSON.stringify({...payload,mayPublish:true}))).status,400);
  assert.equal((await call(port,JSON.stringify({...payload,
    identityEvidenceIds:[identity,randomUUID()]}))).status,400);
  assert.equal((await call(port,JSON.stringify({certificateEvidenceIds:[],
    identityEvidenceIds:[identity]}))).status,400);
  assert.equal((await call(port,JSON.stringify(payload),bearer,'text/plain')).status,400);
  assert.equal(seen.length,0);assert.equal(audit.length,5);
 });
});
test('revocation or forbidden evidence returns 403 and no private OCR fields',async()=>{
 await withServer(async(port,seen,audit)=>{
  const r=await call(port,JSON.stringify(payload));
  assert.equal(r.status,403);
  assert.equal(r.body.report,undefined);
  assert.equal(audit[0]?.route,'PAIR');assert.equal(audit[0]?.reason,'FORBIDDEN');
  assert.equal(seen.length,1);
 },async()=>{throw new PrivatePairAccessDenied();});
});
test('disabled dependency and worker outage fail closed, never return fabricated match',async()=>{
 await withServer(async(port,seen,audit)=>{
  const r=await call(port,JSON.stringify(payload));
  assert.equal(r.status,503);assert.equal(r.body.report,undefined);
  assert.equal(seen.length,0);assert.equal(audit[0]?.reason,'UPSTREAM_UNAVAILABLE');
 },null);
 await withServer(async(port,seen,audit)=>{
  const r=await call(port,JSON.stringify(payload));
  assert.equal(r.status,503);assert.equal(r.body.report,undefined);
  assert.equal(seen.length,1);assert.equal(audit[0]?.reason,'UPSTREAM_UNAVAILABLE');
 },async()=>{throw Error('SYNTHETIC_PRIVATE_FILE_PATH_MUST_NOT_LEAK');});
});
test('malicious injected analyzer cannot return a verified flag',async()=>{
 await withServer(async(port,seen,audit)=>{
  const r=await call(port,JSON.stringify(payload));
  assert.equal(r.status,503);assert.equal(r.body.report,undefined);assert.equal(seen.length,1);
  assert.equal(audit[0]?.reason,'UPSTREAM_UNAVAILABLE');
 },async()=>({...preliminary,mayPublish:true} as unknown as PrivatePairReport));
});

test('shared-code pilot credential is rejected before reading any private document or returning an owner report',async()=>{
 await withServer(async(port,seen,audit)=>{
   const r=await call(port,JSON.stringify(payload));
   assert.equal(r.status,403);
   assert.equal(r.body.report,undefined);
   assert.equal(seen.length,0);
   assert.equal(audit[0]?.route,'PAIR');
   assert.equal(audit[0]?.reason,'FORBIDDEN');
   assert.equal(r.body.ownershipVerified,undefined);
 },async()=>preliminary,true);
});
