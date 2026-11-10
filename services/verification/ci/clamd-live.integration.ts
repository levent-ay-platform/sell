/**
 * GitHub-hosted Ubuntu ONLY: real clamd + private ciphertext filesystem proof.
 * clamd loads one CI-only fabricated signature, not official malware feeds.
 * No real document, production credential, Huawei or external scanner.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm, stat} from 'node:fs/promises';
import {join} from 'node:path';
import {randomBytes, randomUUID, createDecipheriv} from 'node:crypto';
import {LocalClamdScanner} from '../src/clamav-scanner.ts';
import {openPrivateEvidenceStore} from '../src/local-private-store.ts';
import {uploadIndividualEvidence} from '../src/evidence-upload.ts';
import type {EvidenceRow, UploadDependencies} from '../src/evidence-upload.ts';

if(process.env.MALAK_DISPOSABLE_CLAMD!=='yes'||
   !process.env.RUNNER_TEMP||
   !process.env.MALAK_CLAMD_TEST_SOCKET?.startsWith(process.env.RUNNER_TEMP+'/'))
  throw new Error('DISPOSABLE_GITHUB_CLAMD_ONLY');

const socket=process.env.MALAK_CLAMD_TEST_SOCKET!;
const runner=process.env.RUNNER_TEMP!;
const clean=Buffer.from('%PDF-1.7\nMALAK_CI_SYNTHETIC_CLEAN_DOCUMENT_ONLY\n');
const flagged=Buffer.from('%PDF-1.7\nMALAK_CI_SYNTHETIC_FLAGGED_PATTERN_20261110\n');
const owner=randomUUID(), caseId=randomUUID(), secret=Uint8Array.from(randomBytes(32));
const scanner=new LocalClamdScanner({path:socket},{timeoutMs:10000});

test('live isolated clamd detects CI signature; private ciphertext store and upload fail closed',async()=>{
  assert.equal(await scanner.scan(clean,'application/pdf'),'CLEAN');
  assert.equal(await scanner.scan(flagged,'application/pdf'),'INFECTED');

  const root=await mkdtemp(join(runner,'malak-ciphertext-only-'));
  const rows:EvidenceRow[]=[];
  try {
    const objects=await openPrivateEvidenceStore(root);
    const deps:UploadDependencies={
      auth:{authenticate:async t=>t==='isolated-test-session'?{userId:owner}:null},
      scanner,
      objects,
      generateDataKey:async()=>({plaintextKey:Uint8Array.from(secret),
        wrappedKey:'CI_PLACEHOLDER_NOT_A_REAL_KMS_KEY',keyVersion:'ci-only'}),
      cases:{
        inCaseLock:async(_id,action)=>action(),
        getUploadCase:async(id,user)=>id===caseId&&user===owner?{
          id,claimantKind:'PERSON',actorMayUpload:true,status:'PENDING',
          consent:{personUserId:owner,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',
            policyVersion:'ci-only',acceptedAt:'2026-11-10T00:00:00Z',revokedAt:null}}:null,
        findReplay:async(id,user,key)=>rows.find(r=>r.caseId===id&&r.uploadedBy===user&&r.idempotencyKey===key)??null,
        attachEvidence:async row=>{rows.push(row);},
      },
    };
    const base={bearerToken:'isolated-test-session',caseId,
      evidenceKind:'OWNERSHIP_CERTIFICATE' as const,pageNumber:1,
      claimedMime:'application/pdf'};
    const accepted=await uploadIndividualEvidence({...base,idempotencyKey:'ci_live_clamd_clean_01',bytes:clean},deps);
    assert.equal(accepted.ok,true,JSON.stringify(accepted));
    assert.equal(accepted.ownershipVerified,false);
    assert.equal(accepted.mayPublish,false);
    assert.equal(rows.length,1);

    const path=join(root,'private','evidence',rows[0]!.objectKey.split('/').at(-1)!+'.sealed');
    const disk=await readFile(path);
    assert.equal((await stat(path)).mode&0o777,0o600);
    assert.equal(disk.subarray(0,10).toString(),'MALAK-EV1\0');
    assert.equal(disk.includes(clean),false);
    const iv=disk.subarray(10,22),tag=disk.subarray(22,38),encrypted=disk.subarray(38);
    const decipher=createDecipheriv('aes-256-gcm',secret,iv);
    decipher.setAAD(Buffer.from(`malak-evidence-v1:${caseId}:${rows[0]!.evidenceId}`));
    decipher.setAuthTag(tag);
    assert.deepEqual(Buffer.concat([decipher.update(encrypted),decipher.final()]),clean);

    const blocked=await uploadIndividualEvidence({...base,idempotencyKey:'ci_live_clamd_flagged_02',bytes:flagged},deps);
    assert.equal(blocked.ok,false);
    if(!blocked.ok) assert.equal(blocked.code,'FILE_UNSAFE');
    assert.equal(rows.length,1);

    const unavailable=new LocalClamdScanner({path:join(root,'missing-clamd.socket')},{timeoutMs:500});
    const failClosed=await uploadIndividualEvidence({...base,idempotencyKey:'ci_live_clamd_offline_03',bytes:clean},
      {...deps,scanner:unavailable});
    assert.equal(failClosed.ok,false);
    if(!failClosed.ok) assert.equal(failClosed.code,'FILE_UNSAFE');
    assert.equal(rows.length,1);
  } finally {
    secret.fill(0);
    await rm(root,{recursive:true,force:true});
  }
});
