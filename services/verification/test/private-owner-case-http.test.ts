import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {request,createServer} from 'node:http';
import {once} from 'node:events';
import {createPrivateEvidenceHttpHandler} from '../src/private-evidence-http.ts';
import {OwnerCaseDenied} from '../src/private-owner-case.ts';

const userId=randomUUID(),caseId=randomUUID();
const token='synthetic-authenticated-token-123456789';
const casePath='/internal/v1/owner/cases';
const revokePath=casePath+'/'+caseId+'/revoke-consent';
const consent={idempotencyKey:'synthetic-owner-idempotency-0001',
  title:'Terrain fictif',region:'Kélibia',areaSquareMetres:'200',
  priceTnd:'18900',capacity:'owner',consentAccepted:true};

function post(port:number,path:string,body:string|null,auth=token){
 return new Promise<{status:number;json:any}>((resolve,reject)=>{
  const headers:Record<string,string>={Authorization:'Bearer '+auth};
  if(body!==null){headers['Content-Type']='application/json';
    headers['Content-Length']=String(Buffer.byteLength(body));}
  const req=request({hostname:'127.0.0.1',port,path,method:'POST',headers},res=>{
    const parts:Buffer[]=[];
    res.on('data',part=>parts.push(part));
    res.on('end',()=>{
      try{resolve({status:res.statusCode??0,json:JSON.parse(Buffer.concat(parts).toString())});}
      catch(e){reject(e);}
    });
  });
  req.on('error',reject);req.end(body??undefined);
 });
}
async function withServer(run:(port:number,record:{seen:unknown[];audit:any[]})=>Promise<void>,
 options:{disable?:boolean;fail?:'conflict'|'unauthorized'|'forbidden'|'unavailable';evil?:boolean}={}){
 const seen:unknown[]=[],audit:any[]=[];
 const server=createServer(createPrivateEvidenceHttpHandler({
  auth:{authenticate:async bearer=>bearer===token?{userId}:null},
  audit:{recordDenied:async event=>{audit.push(event);}},
  limits:{consume:async ()=>true},
  upload:async()=>{throw Error('UNEXPECTED_UPLOAD');},
  inspect:async()=>{throw Error('UNEXPECTED_INSPECT');},
  ...(!options.disable?{
   provisionOwnerCase:async(bearer:string,body:unknown)=>{
    seen.push({bearer,body});
    if(options.fail==='conflict')throw new OwnerCaseDenied('REPLAY_CONFLICT');
    if(options.fail==='unauthorized')throw new OwnerCaseDenied('AUTH_REQUIRED');
    if(options.fail==='forbidden')throw new OwnerCaseDenied('CASE_UNAVAILABLE');
    if(options.fail==='unavailable')throw Error('DO_NOT_LEAK_TITLE_OR_DOCUMENT');
    return {caseId,reused:false,consentRecorded:true,ownershipVerified:options.evil===true,
      identityVerified:false,mayPublish:false};
   },
   withdrawOwnerConsent:async(bearer:string,id:string)=>{
    seen.push({bearer,caseId:id});
    if(options.fail==='forbidden')throw new OwnerCaseDenied('CASE_UNAVAILABLE');
    return {revoked:true,ownershipVerified:false,identityVerified:false,mayPublish:false};
   }
  }:{})
 }));
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const address=server.address();if(!address||typeof address==='string')throw Error('NO_SERVER');
 try {await run(address.port,{seen,audit});}
 finally{server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));}
}
test('private owner case HTTP returns only new opaque case and unverified status',async()=>{
 await withServer(async(port,{seen,audit})=>{
  const reply=await post(port,casePath,JSON.stringify(consent));
  assert.equal(reply.status,201);
  assert.equal(reply.json.caseId,caseId);
  assert.equal(reply.json.consentRecorded,true);
  assert.equal(reply.json.mayPublish,false);
  assert.equal(reply.json.identityVerified,false);
  assert.equal(reply.json.ownershipVerified,false);
  assert.deepEqual(seen,[{bearer:token,body:consent}]);
  assert.equal(audit.length,0);
 });
});
test('revocation must be authorized, does not return personal data',async()=>{
 await withServer(async(port,{seen,audit})=>{
  const r=await post(port,revokePath,null);
  assert.equal(r.status,200);assert.equal(r.json.revoked,true);
  assert.equal(r.json.mayPublish,false);
  assert.deepEqual(seen,[{bearer:token,caseId}]);assert.equal(audit.length,0);
 });
});
test('unauthenticated, unsupported, replay conflict and server failures fail closed',async()=>{
 await withServer(async(port,{seen,audit})=>{
  const r=await post(port,casePath,JSON.stringify(consent),'invalid-session-token-123456789');
  assert.equal(r.status,401);assert.equal(seen.length,0);
  assert.equal(audit[0].reason,'UNAUTHENTICATED');
 },{});
 await withServer(async(port,{seen,audit})=>{
  const r=await post(port,casePath,JSON.stringify(consent));
  assert.equal(r.status,409);assert.equal(r.json.caseId,undefined);
  assert.equal(audit[0].route,'OWNER_CASE');
  assert.equal(audit[0].reason,'REPLAY_CONFLICT');
  assert.equal(seen.length,1);
 },{fail:'conflict'});
 await withServer(async(port,{seen,audit})=>{
  const r=await post(port,casePath,JSON.stringify(consent));
  assert.equal(r.status,503);assert.equal(r.json.caseId,undefined);
  assert.equal(JSON.stringify(r.json).includes('DO_NOT_LEAK'),false);
  assert.equal(audit[0].reason,'UPSTREAM_UNAVAILABLE');
 },{fail:'unavailable'});
 await withServer(async(port,{seen,audit})=>{
  const r=await post(port,casePath,JSON.stringify(consent));
  assert.equal(r.status,503);assert.equal(seen.length,0);
  assert.equal(audit[0].reason,'UPSTREAM_UNAVAILABLE');
 },{disable:true});
});
test('compromised case adapter cannot claim verified ownership',async()=>{
 await withServer(async(port,{seen,audit})=>{
  const r=await post(port,casePath,JSON.stringify(consent));
  assert.equal(r.status,503);assert.equal(r.json.caseId,undefined);
  assert.equal(audit[0].reason,'UPSTREAM_UNAVAILABLE');
 },{evil:true});
});
