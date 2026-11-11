/**
 * Real disposable PostgreSQL acceptance of SAME-service pilot session isolation.
 * Synthetic-only, no phone owner or real documents.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {SameServicePilotAuth,PilotLoginDenied,PilotLoginUnavailable} from '../src/pilot-auth.ts';
import {provisionIndividualOwnerCase,revokeIndividualOwnerConsent,OwnerCaseDenied} from '../src/private-owner-case.ts';
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

test('disposable DB: 1234 pilot login signs into existing owner case service but never shares evidence by phone',async()=>{
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

  const data={idempotencyKey:'pilot-real-case-synthetic-001',title:'Terrain fictif',
    region:'Kélibia',areaSquareMetres:'200',priceTnd:'18900',
    capacity:'owner',consentAccepted:true};
  const deps={auth,pool:pool as SqlPool,policyVersion:'pilot-consent-v1'};
  const caseA=await provisionIndividualOwnerCase(replay.bearer,data,deps);
  assert.equal(caseA.ownershipVerified,false);
  assert.equal(caseA.identityVerified,false);
  assert.equal(caseA.mayPublish,false);
  const caseB=await provisionIndividualOwnerCase(otherDevice.bearer,data,deps);
  assert.notEqual(caseA.caseId,caseB.caseId);
  await assert.rejects(revokeIndividualOwnerConsent(otherDevice.bearer,caseA.caseId,deps),
    (err:unknown)=>err instanceof OwnerCaseDenied&&err.code==='CASE_UNAVAILABLE');
  const access=await pool.query(
    'SELECT sell.case_accepts_individual_evidence($1::uuid,$2::uuid) AS allowed',
    [caseA.caseId,otherId]);
  assert.equal(access.rows[0].allowed,false);
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
