import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {createPrivateEvidenceHttpHandler} from '../src/private-evidence-http.ts';
import type {PilotPairPreview} from '../src/pilot-private-pair.ts';

const token='synthetic-private-ocr-pilot-session-token-valid';
const id=randomUUID(),jpeg=Buffer.concat([
 Buffer.from([255,216,255,224]),Buffer.alloc(44,88)
]);
const pdf=Buffer.from('%PDF-1.4\nSYNTHETIC LAND DOC\n%%EOF');
const envelope=(doc=pdf,front=jpeg)=>{
 const a=Buffer.alloc(2+4+doc.length+4+front.length);
 a[0]=1;a[1]=1;let at=2;
 a.writeUInt32BE(doc.length,at);at+=4;doc.copy(a,at);at+=doc.length;
 a.writeUInt32BE(front.length,at);at+=4;front.copy(a,at);
 return a;
};
const safe: PilotPairPreview={
 detectedKind:'SALE_DEED',assessment:'NAME_ONLY_REVIEW',
 matchedRole:'BUYER',documentCinStatus:'NOT_PRINTED',
 matchedRightsHolder:null,
 propertyFacts:[{key:'soldPlotNumber',value:'82',page:1}],
 certificatePages:1,identityPages:1,
 ownershipVerified:false,identityVerified:false,
 mayPublish:false,requiresHumanReview:true
};
async function server(run:(url:string,state:{pairs:number;denials:number})=>Promise<void>,
 disabled=false){
 const state={pairs:0,denials:0};
 const srv=createServer(createPrivateEvidenceHttpHandler({
  auth:{authenticate:async t=>t===token?{userId:id,pilotUnverified:true as const}:null},
  limits:{consume:async()=>true},
  audit:{recordDenied:async()=>{state.denials++;}},
  upload:async()=>{throw Error('MUST_NOT_OPEN_OWNER_ARCHIVE');},
  inspect:async()=>{throw Error('MUST_NOT_INSPECT_EXISTING_OWNER');},
  ...(!disabled?{previewPilotPair:async (_:string,docs:any[],front:any)=>{
   state.pairs++;
   assert.equal(docs.length,1);
   assert.deepEqual(Buffer.from(docs[0].bytes),pdf);
   assert.deepEqual(Buffer.from(front.bytes),jpeg);
   return safe;
  }}:{})
 }));
 srv.listen(0,'127.0.0.1');await once(srv,'listening');
 const addr=srv.address();assert.ok(addr&&typeof addr!=='string');
 try{await run('http://127.0.0.1:'+addr.port,state);}
 finally{srv.closeAllConnections();await new Promise<void>(r=>srv.close(()=>r()));}
}
async function call(url:string,body=envelope(),extras:Record<string,string>={}){
 return fetch(url+'/internal/v1/pilot/compare-pair',{
  method:'POST',
  headers:{Authorization:'Bearer '+token,
   'Content-Type':'application/octet-stream',
   'X-Pilot-Ocr-Consent':'1',...extras},
  body:new Uint8Array(body)
 });
}
test('pilot compares just freshly submitted documents without owner-case route',async()=>{
 await server(async(base,state)=>{
  const r=await call(base);
  assert.equal(r.status,200);
  const j=await r.json() as any;
  assert.equal(j.pilotOcr,true);
  assert.equal(j.comparison.matchedRole,'BUYER');
  assert.equal(j.comparison.propertyFacts[0].value,'82');
  assert.equal(j.identityVerified,false);
  assert.equal(j.ownershipVerified,false);
  assert.equal(j.mayPublish,false);
  assert.equal(state.pairs,1);
  assert.equal(state.denials,0);
  assert.equal(r.headers.get('Cache-Control'),'no-store');
  for(const text of [token,'Leila','00001122'])
   assert.equal(JSON.stringify(j).includes(text),false);
 });
});
test('missing consent, malformed frame and arbitrary document mime fail closed',async()=>{
 await server(async(base,state)=>{
  assert.equal((await call(base,envelope(),{'X-Pilot-Ocr-Consent':'0'})).status,400);
  const a=envelope();a[0]=99;
  assert.equal((await call(base,a)).status,400);
  const bad=envelope();bad[1]=3;
  assert.equal((await call(base,bad)).status,422);
  assert.equal(state.pairs,0);
  assert.equal(state.denials,3);
 });
});
test('missing injected worker path returns unavailable; no fake success',async()=>{
 await server(async(base,state)=>{
  const r=await call(base);
  assert.equal(r.status,503);
  assert.equal(state.pairs,0);
 },true);
});
test('pilot cannot access owner case route or disclose stored evidence',async()=>{
 await server(async(base,state)=>{
  const r=await fetch(base+'/internal/v1/owner/cases',{
   method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},
   body:'{}'
  });
  assert.equal(r.status,403);
  assert.equal(state.pairs,0);
 });
});
test('expired or unknown session denied before any OCR',async()=>{
 await server(async(base,state)=>{
  const r=await call(base,envelope(),{Authorization:'Bearer expired-not-real-token-123456789'});
  assert.equal(r.status,401);
  assert.equal(state.pairs,0);
 });
});
