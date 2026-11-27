import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {createPrivateEvidenceHttpHandler} from '../src/private-evidence-http.ts';

const caseId=randomUUID(),owner=randomUUID();
const fakeToken='synthetic-verified-owner-token-not-real';
const syntheticJpeg=Buffer.concat([Buffer.from([0xff,0xd8,0xff,0xe0]),Buffer.alloc(48,0x11)]);
function setup({disabled=false,unverified=false}={}) {
  let scans=0,denials=0;
  const worker=async (args:{bearerToken:string;caseId:string;bytes:Uint8Array;mime:string})=>{
    assert.equal(args.caseId,caseId);
    assert.equal(args.bearerToken,fakeToken);
    assert.equal(args.mime,'image/jpeg');
    assert.deepEqual(Buffer.from(args.bytes),syntheticJpeg);
    scans++;
    return {surname:'بن مثال',givenName:'سالم',cinLast4:null,birthDate:null,confidencePercent:95};
  };
  const deps={
    auth:{authenticate:async(token:string)=>token===fakeToken
      ?{userId:owner,pilotUnverified:unverified}:null},
    limits:{consume:async()=>true},
    audit:{recordDenied:async()=>{denials++;}},
    upload:async()=>{throw Error('UNEXPECTED_PERSISTENT_UPLOAD');},
    inspect:async()=>{throw Error('UNEXPECTED_PERSISTENT_INSPECTION');},
    ...(disabled?{}:{previewCin:worker})
  };
  const server=createServer(createPrivateEvidenceHttpHandler(deps as any));
  return {
    server,
    get scans(){return scans;},
    get denials(){return denials;}
  };
}
async function inServer(
  harness:ReturnType<typeof setup>,
  action:(base:string)=>Promise<void>
) {
  harness.server.listen(0,'127.0.0.1');
  await once(harness.server,'listening');
  const address=harness.server.address();
  assert.ok(address&&typeof address==='object');
  try{await action('http://127.0.0.1:'+address.port);}
  finally{await new Promise<void>(resolve=>harness.server.close(()=>resolve()));}
}
const endpoint='/internal/v1/evidence/'+caseId+'/preview-cin';
function headers(){
  return {'Authorization':'Bearer '+fakeToken,'Content-Type':'application/octet-stream',
    'X-Claimed-Mime':'image/jpeg'};
}
test('synthetic Huawei CIN preview HTTP returns only unverified field candidates',async()=>{
  const h=setup();
  await inServer(h,async base=>{
    const response=await fetch(base+endpoint,{
      method:'POST',headers:headers(),body:syntheticJpeg
    });
    assert.equal(response.status,200);
    const result=await response.json() as Record<string,unknown>;
    assert.equal(result.ok,true);
    assert.equal(result.schemaVersion,1);
    assert.equal(result.engine,'arabic-v5');
    assert.equal(result.ownershipVerified,false);
    assert.equal(result.identityVerified,false);
    assert.equal(result.mayPublish,false);
    assert.equal(result.surname,'بن مثال');
    assert.equal(JSON.stringify(result).includes(fakeToken),false);
    assert.equal(h.scans,1);
  });
});
test('unverified pilot cannot upload a CIN preview, even with a token',async()=>{
  const h=setup({unverified:true});
  await inServer(h,async base=>{
    const response=await fetch(base+endpoint,{
      method:'POST',headers:headers(),body:syntheticJpeg
    });
    assert.equal(response.status,403);
    assert.equal(h.scans,0);
    assert.equal(h.denials,1);
  });
});
test('absent Huawei OCR worker fails closed with no preview or document persistence',async()=>{
  const h=setup({disabled:true});
  await inServer(h,async base=>{
    const response=await fetch(base+endpoint,{
      method:'POST',headers:headers(),body:syntheticJpeg
    });
    assert.equal(response.status,503);
    assert.equal(h.scans,0);
  });
});
