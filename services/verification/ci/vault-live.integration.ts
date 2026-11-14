/**
 * Ephemeral GitHub-hosted VAULT DEV mode ONLY; NOT safe for any deployment.
 * Real Vault Transit derived keys, rotation and private encrypted evidence.
 * No actual Tunisian owner document, ID, credential or persistent KMS.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {join} from 'node:path';
import {readFile, mkdtemp, rm, stat} from 'node:fs/promises';
import {Buffer} from 'node:buffer';
import {randomUUID,createDecipheriv} from 'node:crypto';
import {VaultTransitDataKeys} from '../src/vault-transit.ts';
import {openPrivateEvidenceStore} from '../src/local-private-store.ts';
import {uploadIndividualEvidence} from '../src/evidence-upload.ts';
import type {UploadDependencies, EvidenceRow} from '../src/evidence-upload.ts';

if(process.env.MALAK_DISPOSABLE_VAULT!=='yes'|| !process.env.RUNNER_TEMP ||
   process.env.MALAK_VAULT_TEST_PORT!=='18200')throw Error('DISPOSABLE_GITHUB_VAULT_ONLY');
const runner=process.env.RUNNER_TEMP;
const tokenPath=join(runner,'malak-vault-ci','app-token');
const api='http://127.0.0.1:18200/';
const vault=new VaultTransitDataKeys({baseUrl:api,keyName:'malak-evidence',
  token:async()=> (await readFile(tokenPath,'utf8')).trim(),timeoutMs:5000});
const DOC=Buffer.from('%PDF-1.7\nSYNTHETIC ANONYMOUS OWNERSHIP PROOF - NO REAL PERSONAL DATA\n%%EOF');
const rootDevToken='MALAK_CI_DEV_ROOT_ONLY';

async function open(key:Uint8Array,sealed:Buffer,caseId:string,evidenceId:string):Promise<Buffer>{
 try {
  assert.equal(sealed.subarray(0,10).toString('ascii'),'MALAK-EV1\0');
  const d=createDecipheriv('aes-256-gcm',key,sealed.subarray(10,22));
  d.setAuthTag(sealed.subarray(22,38));
  d.setAAD(Buffer.from(`malak-evidence-v1:${caseId}:${evidenceId}`));
  return Buffer.concat([d.update(sealed.subarray(38)),d.final()]);
 } finally {key.fill(0);}
}
test('REAL Vault Transit + private disk + AES-GCM: original recoverable after root rotation, wrong owner scope denied',async()=>{
 const caseId=randomUUID(),owner=randomUUID(),records:EvidenceRow[]=[];
 const root=await mkdtemp(join(runner,'malak-vault-private-'));
 try {
  const store=await openPrivateEvidenceStore(root);
  const deps:UploadDependencies={
   auth:{authenticate:async token=>token==='fictional-session-only'?{userId:owner}:null},
   scanner:{scan:async()=> 'CLEAN'}, // Real clamd independently proven in prior exact-head gate
   cases:{
    inCaseLock:async(_id,work)=>work(),
    getUploadCase:async(id,user)=>id===caseId&&user===owner?{id,claimantKind:'PERSON',actorMayUpload:true,status:'PENDING',consent:{personUserId:owner,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',policyVersion:'ci',acceptedAt:'2026-11-10',revokedAt:null}}:null,
    findReplay:async(id,user,key)=>records.find(r=>r.caseId===id&&r.uploadedBy===user&&r.idempotencyKey===key)??null,
    attachEvidence:async row=>{records.push(row);},
   },objects:store,
   generateDataKey:scope=>vault.generateDataKey(scope)
  };
  const base={bearerToken:'fictional-session-only',caseId,bytes:DOC,evidenceKind:'OWNERSHIP_CERTIFICATE' as const,pageNumber:1,claimedMime:'application/pdf'};
  const a=await uploadIndividualEvidence({...base,idempotencyKey:'CI_REAL_VAULT_EVIDENCE_001'},deps);
  assert.equal(a.ok,true,JSON.stringify(a));
  assert.equal(a.mayPublish,false);assert.equal(a.ownershipVerified,false);
  assert.equal(records.length,1);
  const r1=records[0]!;
  assert.equal(r1.keyVersion,'v1');
  assert.ok(r1.wrappedDataKey.startsWith('vault:v1:'));
  const disk=join(root,'private','evidence',r1.objectKey.split('/').at(-1)!+'.sealed');
  const bytes=await readFile(disk);
  assert.equal((await stat(disk)).mode&0o777,0o600);
  assert.ok(!bytes.includes(DOC),'only ciphertext on disk');
  const key=await vault.unwrapDataKey({caseId,evidenceId:r1.evidenceId,wrappedKey:r1.wrappedDataKey,keyVersion:r1.keyVersion});
  assert.deepEqual(await open(key,bytes,caseId,r1.evidenceId),DOC);
  await assert.rejects(()=>vault.unwrapDataKey({caseId:randomUUID(),evidenceId:r1.evidenceId,wrappedKey:r1.wrappedDataKey,keyVersion:r1.keyVersion}),/VAULT_TRANSIT_DENIED/);
  await assert.rejects(()=>vault.unwrapDataKey({caseId,evidenceId:randomUUID(),wrappedKey:r1.wrappedDataKey,keyVersion:r1.keyVersion}),/VAULT_TRANSIT_DENIED/);
  await assert.rejects(()=>vault.unwrapDataKey({caseId,evidenceId:r1.evidenceId,wrappedKey:r1.wrappedDataKey,keyVersion:'v2'}),/VAULT_VERSION_MISMATCH/);

  // The runtime's narrow token must NOT have master-key rotation permissions.
  const limited=await fetch(new URL('v1/transit/keys/malak-evidence/rotate',api),{
   method:'POST',headers:{'X-Vault-Token':(await readFile(tokenPath,'utf8')).trim()},body:'{}'
  });
  assert.equal(limited.status,403);
  await limited.body?.cancel();
  // Dedicated CI admin rotates the master key. Old ciphertext must remain decryptable.
  const rotated=await fetch(new URL('v1/transit/keys/malak-evidence/rotate',api),{
   method:'POST',headers:{'X-Vault-Token':rootDevToken},body:'{}'
  });
  assert.ok(rotated.ok,`failed master key rotation status ${rotated.status}`);
  await rotated.body?.cancel();
  const old=await vault.unwrapDataKey({caseId,evidenceId:r1.evidenceId,wrappedKey:r1.wrappedDataKey,keyVersion:r1.keyVersion});
  assert.deepEqual(await open(old,bytes,caseId,r1.evidenceId),DOC);

  const b=await uploadIndividualEvidence({...base,idempotencyKey:'CI_REAL_VAULT_EVIDENCE_002'},deps);
  assert.equal(b.ok,true,JSON.stringify(b));assert.equal(records.length,2);
  assert.equal(records[1]!.keyVersion,'v2');
  const r2=records[1]!;
  const disk2=await readFile(join(root,'private','evidence',r2.objectKey.split('/').at(-1)!+'.sealed'));
  const nextKey=await vault.unwrapDataKey({caseId,evidenceId:r2.evidenceId,wrappedKey:r2.wrappedDataKey,keyVersion:r2.keyVersion});
  assert.deepEqual(await open(nextKey,disk2,caseId,r2.evidenceId),DOC);

  // Vault unavailable -> upload cannot store or approve a new document.
  const bad=new VaultTransitDataKeys({baseUrl:'http://127.0.0.1:1/',keyName:'malak-evidence',token:async()=> 'TEST_NON_SECRET_TOKEN'});
  const failed=await uploadIndividualEvidence({...base,idempotencyKey:'CI_REAL_VAULT_OFFLINE_003'},
    {...deps,generateDataKey:scope=>bad.generateDataKey(scope)});
  assert.equal(failed.ok,false);if(!failed.ok)assert.equal(failed.code,'STORAGE_UNAVAILABLE');
  assert.equal(records.length,2);
 }finally{await rm(root,{recursive:true,force:true});}
});
