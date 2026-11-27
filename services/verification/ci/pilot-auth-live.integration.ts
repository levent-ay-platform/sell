/**
 * Real disposable PostgreSQL acceptance of SAME-service pilot session isolation.
 * Synthetic-only, no phone owner or real documents.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {join} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {SameServicePilotAuth,PilotLoginDenied,PilotLoginUnavailable} from '../src/pilot-auth.ts';
import {provisionIndividualOwnerCase,revokeIndividualOwnerConsent,OwnerCaseDenied} from '../src/private-owner-case.ts';
import {uploadIndividualEvidence} from '../src/evidence-upload.ts';
import type {SqlPool} from '../src/postgres-evidence-repository.ts';

const runner=process.env.RUNNER_TEMP;
if(!runner||process.env.MALAK_DISPOSABLE_PG!=='yes')throw Error('DISPOSABLE_GITHUB_POSTGRES_ONLY');
const require=createRequire(join(runner,'malak-pg','driver.cjs'));
const {Pool}=require('pg');
const pool=new Pool({host:'127.0.0.1',port:5432,user:'sell_ci',
 password:process.env.PGPASSWORD,database:'sell_ci',max:4,connectionTimeoutMillis:4000});
const deviceA='A'.repeat(43),deviceB='B'.repeat(43);
const phone='+21629500000',otherPhone='+21655500001';
const secret=Buffer.alloc(48,0x42);

test('disposable DB: shared 1234 pilot sessions are scoped, but cannot open private cases or upload identity documents',async()=>{
 try{
  const auth=new SameServicePilotAuth(pool as SqlPool,{enabled:true,deviceHmacSecret:secret});
  const disabled=new SameServicePilotAuth(pool as SqlPool,{enabled:false,deviceHmacSecret:secret});
  await assert.rejects(disabled.login({phone,code:'1234',deviceKey:deviceA}),
    (err:unknown)=>err instanceof PilotLoginUnavailable);
  await assert.rejects(auth.login({phone,code:'0000',deviceKey:deviceA}),
    (err:unknown)=>err instanceof PilotLoginDenied);
  await assert.rejects(auth.login({phone,code:'1234',deviceKey:'wrong'}),
    (err:unknown)=>err instanceof PilotLoginDenied);
  const first=await auth.login({phone,code:'1234',deviceKey:deviceA});
  assert.equal(first.pilotConfirmed,true);
  assert.equal(first.expiresInSeconds,43200);
  assert.equal(first.bearer.length,43);
  assert.equal(await disabled.authenticate(first.bearer),null);
  const firstId=(await auth.authenticate(first.bearer))?.userId;
  assert.match(firstId!,/^[0-9a-f-]{36}$/);
  const replay=await auth.login({phone,code:'1234',deviceKey:deviceA});
  assert.notEqual(replay.bearer,first.bearer);
  assert.equal((await auth.authenticate(replay.bearer))?.userId,firstId);
  const otherDevice=await auth.login({phone,code:'1234',deviceKey:deviceB});
  const otherId=(await auth.authenticate(otherDevice.bearer))?.userId;
  assert.notEqual(firstId,otherId);
  const changedPhone=await auth.login({phone:otherPhone,code:'1234',deviceKey:deviceA});
  assert.notEqual((await auth.authenticate(changedPhone.bearer))?.userId,firstId);

  assert.equal((await auth.authenticate(first.bearer))?.pilotUnverified,true);
  assert.equal((await auth.authenticate(otherDevice.bearer))?.pilotUnverified,true);
  const data={idempotencyKey:'pilot-private-case-must-deny-001',title:'Terrain fictif',
    region:'Kélibia',areaSquareMetres:'200',priceTnd:'18900',
    capacity:'owner',consentAccepted:true};
  const deps={auth,pool:pool as SqlPool,policyVersion:'pilot-consent-v1'};
  for(const token of [first.bearer,replay.bearer,otherDevice.bearer]){
    await assert.rejects(provisionIndividualOwnerCase(token,data,deps),
      (err:unknown)=>err instanceof OwnerCaseDenied&&err.code==='AUTH_REQUIRED');
    await assert.rejects(revokeIndividualOwnerConsent(token,randomUUID(),deps),
      (err:unknown)=>err instanceof OwnerCaseDenied&&err.code==='AUTH_REQUIRED');
  }
  // Defense in depth: even a badly configured upload dependency must never
  // accept a pilot bearer as authority for any document bytes.
  const upload=await uploadIndividualEvidence({
    bearerToken:replay.bearer,caseId:randomUUID(),idempotencyKey:'pilot_must_not_upload_001',
    bytes:Buffer.from('%PDF-1.4\\nSYNTHETIC ONLY\\n%%EOF'),
    evidenceKind:'IDENTITY',pageNumber:null,claimedMime:'application/pdf'
  },{
    auth,cases:{
      inCaseLock:async()=>{throw Error('PILOT_REACHED_DB_LOCK');},
      getUploadCase:async()=>{throw Error('PILOT_REACHED_PRIVATE_CASE');},
      findReplay:async()=>null,attachEvidence:async()=>{throw Error('PILOT_ATTACHED');}
    },
    scanner:{scan:async()=>{throw Error('PILOT_REACHED_SCANNER');}},
    objects:{putPrivate:async()=>{throw Error('PILOT_STORED_DOCUMENT');},
      deletePrivate:async()=>{}},
    generateDataKey:async()=>{throw Error('PILOT_REACHED_VAULT');}
  });
  assert.deepEqual(upload,{ok:false,code:'AUTH_REQUIRED',mayPublish:false,ownershipVerified:false});
  await pool.query(
    "UPDATE sell.pilot_sessions SET expires_at=now()-interval '1 second' WHERE token_digest=$1",
    [createHash('sha256').update(replay.bearer).digest('hex')]);
  assert.equal(await auth.authenticate(replay.bearer),null);
  await assert.rejects(provisionIndividualOwnerCase(replay.bearer,
    {...data,idempotencyKey:'pilot-expired-session-key-001'},deps),
    (err:unknown)=>err instanceof OwnerCaseDenied&&err.code==='AUTH_REQUIRED');
  assert.equal((await auth.authenticate(otherDevice.bearer))?.userId,otherId);
 }finally{await pool.end();}
});
