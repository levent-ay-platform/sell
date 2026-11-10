import test from 'node:test';
import assert from 'node:assert/strict';
import {createDecipheriv} from 'node:crypto';
import {uploadIndividualEvidence} from '../src/evidence-upload.ts';
import type {UploadDependencies, EvidenceRow, AuthorizedUploadCase, EvidenceUploadRequest} from '../src/evidence-upload.ts';

const CASE='c7c7c7c7-c7c7-47c7-87c7-c7c7c7c7c7c7';
const USER='fictional-user';
const key=Uint8Array.from({length:32},(_,i)=>i+1); // synthetic, never a deployment key
const fixture=Uint8Array.from(Buffer.concat([Buffer.from('%PDF-1.4\n'),Buffer.from('FAKE DOCUMENT FIXTURE PDF ONLY'.repeat(4))]));
const defaults:EvidenceUploadRequest={bearerToken:'fake-token',caseId:CASE,idempotencyKey:'abc123___________',bytes:fixture,
 evidenceKind:'OWNERSHIP_CERTIFICATE',pageNumber:1,claimedMime:'application/pdf'};
function harness(){
 const objects=new Map<string,Uint8Array>();
 const rows:EvidenceRow[]=[];
 const state:{access:AuthorizedUploadCase|null,scan:'CLEAN'|'INFECTED'|'UNKNOWN',attachFails:boolean}={
  access:{id:CASE,claimantKind:'PERSON',actorMayUpload:true,status:'PENDING',consent:{
   personUserId:USER,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',policyVersion:'consent-v1',acceptedAt:'2026-01-01T00:00:00Z',revokedAt:null}},
  scan:'CLEAN',attachFails:false
 };
 let calls={scanner:0,put:0,auth:0};
 let lock=Promise.resolve();
 const deps:UploadDependencies={
   auth:{authenticate:async t=>{calls.auth++;return t==='fake-token'?{userId:USER}:null;}},
   scanner:{scan:async()=>{calls.scanner++;return state.scan;}},
   objects:{putPrivate:async(id,encrypted)=>{calls.put++;objects.set(id,encrypted);},deletePrivate:async id=>{objects.delete(id);}},
   generateDataKey:async()=>({plaintextKey:Uint8Array.from(key),wrappedKey:"TEST_WRAPPED_KEY_NOT_REAL",keyVersion:"test-kms-v1"}),
   cases:{
    getUploadCase:async()=>state.access,
    findReplay:async(caseId,actor,key)=>rows.find(r=>r.caseId===caseId&&r.uploadedBy===actor&&r.idempotencyKey===key)??null,
    attachEvidence:async r=>{if(state.attachFails)throw Error('db unavailable');rows.push(r);},
    inCaseLock:async(_id,work)=>{
       const previous=lock;
       let release!:()=>void;
       lock=new Promise<void>(resolve=>{release=resolve;});
       await previous;
       try{return await work();}finally{release();}
    }
   }
 };
 return {objects,rows,state,calls,deps};
}
test('upload produces AES-GCM sealed payload and links an immutable private evidence record',async()=>{
 const h=harness();const out=await uploadIndividualEvidence(defaults,h.deps);
 assert.equal(out.ok,true);if(!out.ok)return;
 assert.equal(out.uploaded,true);assert.equal(out.mayPublish,false);
 assert.equal(h.rows.length,1);assert.equal(h.objects.size,1);
 assert.equal(h.rows[0].keyVersion,'test-kms-v1');
 assert.equal(h.rows[0].wrappedDataKey,'TEST_WRAPPED_KEY_NOT_REAL');
 const [k,data]=[h.rows[0].objectKey,h.objects.values().next().value!];
 assert.ok(k.startsWith('private/evidence/'));
 assert.ok(!Buffer.from(data).includes(Buffer.from('FAKE DOCUMENT')));
 assert.equal(Buffer.from(data.subarray(0,10)).toString('ascii'),'MALAK-EV1\0');
 const iv=data.subarray(10,22),tag=data.subarray(22,38),ciphertext=data.subarray(38);
 const decipher=createDecipheriv('aes-256-gcm',key,iv);
 decipher.setAAD(Buffer.from(`malak-evidence-v1:${CASE}:${out.evidenceId}`));
 decipher.setAuthTag(tag);
 const text=Buffer.concat([decipher.update(ciphertext),decipher.final()]);
 assert.deepEqual(text,Buffer.from(fixture));
});
test('idempotent retry returns original evidence without another private object',async()=>{
 const h=harness();const a=await uploadIndividualEvidence(defaults,h.deps);
 const b=await uploadIndividualEvidence(defaults,h.deps);
 assert.equal(a.ok,true);assert.equal(b.ok,true);
 if(a.ok&&b.ok){assert.equal(a.evidenceId,b.evidenceId);assert.equal(b.uploaded,false);}
 assert.equal(h.rows.length,1);assert.equal(h.calls.put,1);
});
test('idempotency race serializes concurrent uploads on a case lock',async()=>{
 const h=harness();const [a,b]=await Promise.all([uploadIndividualEvidence(defaults,h.deps),uploadIndividualEvidence(defaults,h.deps)]);
 assert.equal(a.ok,true);assert.equal(b.ok,true);
 assert.equal(h.rows.length,1);assert.equal(h.calls.put,1);
});
test('same idempotency key with different bytes is rejected',async()=>{
 const h=harness();await uploadIndividualEvidence(defaults,h.deps);
 const different=Uint8Array.from([...fixture,1]);
 const result=await uploadIndividualEvidence({...defaults,bytes:different},h.deps);
 assert.deepEqual(result,{ok:false,code:'REPLAY_CONFLICT',mayPublish:false,ownershipVerified:false});
});
test('invalid session cannot scan or store',async()=>{
 const h=harness();const result=await uploadIndividualEvidence({...defaults,bearerToken:'wrong'},h.deps);
 assert.equal(result.ok,false);assert.equal(h.calls.scanner,0);assert.equal(h.calls.put,0);
});
test('companies and denied party scopes cannot upload',async()=>{
 const h=harness();h.state.access={...h.state.access!,claimantKind:'COMPANY'};
 assert.deepEqual((await uploadIndividualEvidence(defaults,h.deps)).ok,false);
 h.state.access={...h.state.access!,claimantKind:'PERSON',actorMayUpload:false};
 assert.equal((await uploadIndividualEvidence(defaults,h.deps)).ok,false);
 assert.equal(h.calls.put,0);
});
test('absence of consent or revoked consent is fail-closed',async()=>{
 const h=harness();h.state.access={...h.state.access!,consent:null};
 assert.equal((await uploadIndividualEvidence(defaults,h.deps)).ok,false);
 h.state.access={...h.state.access!,consent:{personUserId:USER,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',policyVersion:'v1',acceptedAt:'x',revokedAt:'2026'}};
 assert.equal((await uploadIndividualEvidence(defaults,h.deps)).ok,false);
 assert.equal(h.calls.scanner,0);
});
test('consent revocation between scan and DB lock rejects upload',async()=>{
 const h=harness();h.deps.scanner.scan=async()=>{h.state.access={...h.state.access!,consent:null};return 'CLEAN';};
 const result=await uploadIndividualEvidence(defaults,h.deps);
 assert.equal(result.ok,false);assert.equal(h.objects.size,0);
});
test('malware positive or unavailable scanners always block upload',async()=>{
 for(const scan of ['INFECTED','UNKNOWN'] as const){
  const h=harness();h.state.scan=scan;
  assert.deepEqual((await uploadIndividualEvidence(defaults,h.deps)).ok,false);
  assert.equal(h.objects.size,0);
 }
});
test('unsupported MIME, client MIME lie, bad page and >12MiB fail',async()=>{
 const h=harness();const wrong=Uint8Array.from(Buffer.from('UNKNOWN FILE NO VALID MAGIC'));
 for(const x of [
  {...defaults,bytes:wrong},
  {...defaults,claimedMime:'image/png'},
  {...defaults,pageNumber:99},
  {...defaults,bytes:new Uint8Array(12*1024*1024+1)}
 ])assert.equal((await uploadIndividualEvidence(x,h.deps)).ok,false);
 assert.equal(h.calls.scanner,0);
});
test('metadata transaction failure cleans up encrypted object',async()=>{
 const h=harness();h.state.attachFails=true;
 const result=await uploadIndividualEvidence(defaults,h.deps);
 assert.deepEqual(result,{ok:false,code:'STORAGE_UNAVAILABLE',mayPublish:false,ownershipVerified:false});
 assert.equal(h.objects.size,0);
});
test('identity/owner claims never make an upload an ownership approval',async()=>{
 const h=harness();const result=await uploadIndividualEvidence(defaults,h.deps);
 assert.equal(result.mayPublish,false);assert.equal(result.ownershipVerified,false);
});

test('case reference returned by repository must match server-authorized case',async()=>{
 const h=harness();h.state.access={...h.state.access!,id:'another-case'};
 const result=await uploadIndividualEvidence(defaults,h.deps);
 assert.equal(result.ok,false);assert.equal(h.calls.scanner,0);
});
test('post-review terminal case blocks new evidence submissions',async()=>{
 const h=harness();h.state.access={...h.state.access!,status:'VERIFIED'};
 const result=await uploadIndividualEvidence(defaults,h.deps);
 assert.equal(result.ok,false);assert.equal(h.calls.scanner,0);
});
test('KMS must provide a fresh, wrapped, versioned key before any write',async()=>{
 const h=harness();h.deps.generateDataKey=async()=>({plaintextKey:Uint8Array.from(key),wrappedKey:'',keyVersion:''});
 const result=await uploadIndividualEvidence(defaults,h.deps);
 assert.deepEqual(result,{ok:false,code:'STORAGE_UNAVAILABLE',mayPublish:false,ownershipVerified:false});
 assert.equal(h.objects.size,0);
});
test('temporary KMS key bytes are zeroized following successful encryption',async()=>{
 const h=harness();const plaintext=Uint8Array.from(key);
 h.deps.generateDataKey=async()=>({plaintextKey:plaintext,wrappedKey:'wrapped-test-only',keyVersion:'v1'});
 const r=await uploadIndividualEvidence(defaults,h.deps);
 assert.equal(r.ok,true);
 assert.ok(plaintext.every(x=>x===0));
});
test('record store faults fail closed and never publish',async()=>{
 const h=harness();h.deps.cases.inCaseLock=async()=>{throw Error('db connection unavailable')};
 const r=await uploadIndividualEvidence(defaults,h.deps);
 assert.deepEqual(r,{ok:false,code:'STORAGE_UNAVAILABLE',mayPublish:false,ownershipVerified:false});
});
