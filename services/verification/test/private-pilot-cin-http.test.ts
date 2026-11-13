import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {createPrivateEvidenceHttpHandler} from '../src/private-evidence-http.ts';

const qaToken='synthetic-qa-token-with-sufficient-length';
const ownerToken='synthetic-owner-token-with-sufficient-length';
const image=Buffer.concat([Buffer.from([0xff,0xd8,0xff,0xe0]),Buffer.alloc(50,0x55)]);
const path='/internal/v1/pilot/preview-cin';
const newFields={surname:'بن مثال',givenName:'سالم',cinLast4:'5678',
  birthDate:'2001-03-06',confidencePercent:89};

async function withGateway(action:(base:string,state:{scans:number;denials:number})=>Promise<void>,
 config:{disable?:boolean;wrongAssurance?:boolean;expireOnReplay?:boolean}={}){
 const state={scans:0,denials:0};
 let authReads=0;
 const gateway=createServer(createPrivateEvidenceHttpHandler({
   auth:{authenticate:async(token:string)=>{
     authReads++;
     if(token===qaToken)return {
       userId:randomUUID(),pilotUnverified:!config.wrongAssurance
     };
     if(token===ownerToken)return {userId:randomUUID()};
     return null;
   }},
   audit:{recordDenied:async()=>{state.denials++;}},
   limits:{consume:async()=>true},
   upload:async()=>{throw Error('NEVER_UPLOAD_PILOT_IMAGE');},
   inspect:async()=>{throw Error('NEVER_INSPECT_PILOT_OWNER_EVIDENCE');},
   ...(config.disable?{}:{
     loginPilot:async()=>({bearer:'x'.repeat(43),expiresInSeconds:43200,pilotConfirmed:true as const}),
     previewPilotCin:async(bearer:string,data:Uint8Array,mime:'image/jpeg'|'image/png')=>{
       assert.equal(bearer,qaToken);
       assert.equal(mime,'image/jpeg');
       assert.deepEqual(Buffer.from(data),image);
       state.scans++;
       return newFields;
     }
   })
 } as any));
 gateway.listen(0,'127.0.0.1');
 await once(gateway,'listening');
 const address=gateway.address();
 assert.ok(address&&typeof address!=='string');
 try{await action('http://127.0.0.1:'+address.port,state);}
 finally{
   gateway.closeAllConnections();
   await new Promise<void>(resolve=>gateway.close(()=>resolve()));
 }
}
async function scan(base:string,{consent=true,bearer=qaToken,body=image}={}){
 return fetch(base+path,{
   method:'POST',
   headers:{Authorization:'Bearer '+bearer,'Content-Type':'application/octet-stream',
     'X-Claimed-Mime':'image/jpeg',...(consent?{'X-Pilot-Ocr-Consent':'1'}:{})},
   body
 });
}
test('server-issued pilot may read transient front fields without case or legal approval',async()=>{
 await withGateway(async(base,state)=>{
   const response=await scan(base);
   assert.equal(response.status,200);
   const parsed=await response.json() as Record<string,unknown>;
   assert.equal(parsed.ok,true);
   assert.equal(parsed.pilotOcr,true);
   assert.equal(parsed.engine,'arabic-v5');
   assert.equal(parsed.surname,newFields.surname);
   assert.equal(parsed.cinLast4,newFields.cinLast4);
   assert.equal(parsed.identityVerified,false);
   assert.equal(parsed.ownershipVerified,false);
   assert.equal(parsed.mayPublish,false);
   assert.equal(JSON.stringify(parsed).includes(qaToken),false);
   assert.equal(state.scans,1);
 });
});
test('no pilot consent blocks reading before worker and does not persist',async()=>{
 await withGateway(async(base,state)=>{
   const response=await scan(base,{consent:false});
   assert.equal(response.status,400);
   assert.equal(state.scans,0);
   assert.equal(state.denials,1);
 });
});
test('real owner bearer cannot use pilot OCR route and pilot never gains owner routes',async()=>{
 await withGateway(async(base,state)=>{
   assert.equal((await scan(base,{bearer:ownerToken})).status,403);
   assert.equal(state.scans,0);
   const caseReply=await fetch(base+'/internal/v1/owner/cases',{
      method:'POST',headers:{Authorization:'Bearer '+qaToken,
        'Content-Type':'application/json'},
      body:'{}'
   });
   assert.equal(caseReply.status,403);
 });
});
test('missing pilot worker cannot fake OCR success',async()=>{
 await withGateway(async(base,state)=>{
   assert.equal((await scan(base)).status,503);
   assert.equal(state.scans,0);
 },{disable:true});
});
test('unknown bearer cannot read even a synthetically generated card',async()=>{
 await withGateway(async(base,state)=>{
   assert.equal((await scan(base,{bearer:'synthetic-unknown-bearer-too-long'})).status,401);
   assert.equal(state.scans,0);
 });
});
