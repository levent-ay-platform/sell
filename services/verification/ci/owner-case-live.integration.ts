/**
 * Disposable PostGIS acceptance for authenticated private owner-case lifecycle.
 * Synthetic identities and metadata ONLY. Must NEVER run with production DSN.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {provisionIndividualOwnerCase,revokeIndividualOwnerConsent,OwnerCaseDenied} from '../src/private-owner-case.ts';
import type {OwnerCaseDependencies} from '../src/private-owner-case.ts';
import type {SqlPool} from '../src/postgres-evidence-repository.ts';

const runner=process.env.RUNNER_TEMP;
if(!runner||process.env.MALAK_DISPOSABLE_PG!=='yes')throw Error('DISPOSABLE_GITHUB_POSTGRES_ONLY');
const require=createRequire(join(runner,'malak-pg','driver.cjs'));
const {Pool}=require('pg');
const pool=new Pool({host:'127.0.0.1',port:5432,user:'sell_ci',
 password:process.env.PGPASSWORD,database:'sell_ci',max:4,connectionTimeoutMillis:4000});
const ownerToken='synthetic-owner-session-valid-123456789';
const strangerToken='synthetic-stranger-session-1234567';
const key='private-case-idempotency-001';
const payload={idempotencyKey:key,title:'Terrain fictif',region:'Kélibia',
 areaSquareMetres:'200',priceTnd:'18900',capacity:'owner',consentAccepted:true};

test('disposable PostgreSQL: create once, authorize case, reject conflicts, revoke and rollback',async()=>{
 try {
  const owner=randomUUID(),stranger=randomUUID();
  await pool.query('INSERT INTO sell.users(id) VALUES($1::uuid),($2::uuid)',[owner,stranger]);
  const deps:OwnerCaseDependencies={pool:pool as SqlPool,policyVersion:'synthetic-consent-v1',
    auth:{authenticate:async token=>token===ownerToken?{userId:owner}:
      token===strangerToken?{userId:stranger}:null}};
  await assert.rejects(provisionIndividualOwnerCase('bad',payload,deps),
    (e:unknown)=>e instanceof OwnerCaseDenied&&e.code==='AUTH_REQUIRED');
  await assert.rejects(provisionIndividualOwnerCase(ownerToken,{...payload,consentAccepted:false},deps),
    (e:unknown)=>e instanceof OwnerCaseDenied&&e.code==='INVALID_REQUEST');
  await assert.rejects(provisionIndividualOwnerCase(ownerToken,{...payload,actorUserId:stranger},deps),
    (e:unknown)=>e instanceof OwnerCaseDenied&&e.code==='INVALID_REQUEST');
  const [first,replayed]=await Promise.all([
    provisionIndividualOwnerCase(ownerToken,payload,deps),
    provisionIndividualOwnerCase(ownerToken,payload,deps)
  ]);
  assert.equal(first.caseId,replayed.caseId);
  assert.deepEqual([first.reused,replayed.reused].sort(),[false,true]);
  assert.equal(first.mayPublish,false);
  assert.equal(first.ownershipVerified,false);
  assert.equal(first.identityVerified,false);
  const row=await pool.query(`SELECT c.status,oc.declared_capacity,
        sell.case_accepts_individual_evidence(c.id,$2::uuid) AS allowed,
        count(*) OVER()::integer AS total
        FROM sell.verification_cases c
        JOIN sell.ownership_claims oc ON oc.id=c.ownership_claim_id
        JOIN sell.private_owner_case_intents i ON i.case_id=c.id
        WHERE i.case_id=$1::uuid`,[first.caseId,owner]);
  assert.equal(row.rows.length,1);
  assert.equal(row.rows[0].allowed,true);
  assert.equal(row.rows[0].declared_capacity,'SOLE_OWNER');
  assert.equal(row.rows[0].status,'PENDING');
  const strangerMayRead=await pool.query(
    'SELECT sell.case_accepts_individual_evidence($1::uuid,$2::uuid) AS allowed',
    [first.caseId,stranger]);
  assert.equal(strangerMayRead.rows[0].allowed,false);
  await assert.rejects(provisionIndividualOwnerCase(ownerToken,{...payload,priceTnd:'18901'},deps),
    (e:unknown)=>e instanceof OwnerCaseDenied&&e.code==='REPLAY_CONFLICT');
  await assert.rejects(revokeIndividualOwnerConsent(strangerToken,first.caseId,deps),
    (e:unknown)=>e instanceof OwnerCaseDenied&&e.code==='CASE_UNAVAILABLE');
  const revoked=await revokeIndividualOwnerConsent(ownerToken,first.caseId,deps);
  assert.equal(revoked.revoked,true);assert.equal(revoked.mayPublish,false);
  await revokeIndividualOwnerConsent(ownerToken,first.caseId,deps);
  const decisions=await pool.query(
    'SELECT decision FROM sell.evidence_consent_events WHERE case_id=$1::uuid ORDER BY id',
    [first.caseId]);
  assert.deepEqual(decisions.rows.map((r:any)=>r.decision),['GRANTED','REVOKED']);
  const blocked=await pool.query(
    'SELECT sell.case_accepts_individual_evidence($1::uuid,$2::uuid) AS allowed',
    [first.caseId,owner]);
  assert.equal(blocked.rows[0].allowed,false);
  await assert.rejects(provisionIndividualOwnerCase(ownerToken,payload,deps),
    (e:unknown)=>e instanceof OwnerCaseDenied&&e.code==='CASE_UNAVAILABLE');
  const audit=await pool.query(`SELECT action FROM sell.audit_events
    WHERE entity_kind='VERIFICATION_CASE' AND entity_id=$1::uuid ORDER BY id`,[first.caseId]);
  assert.deepEqual(audit.rows.map((r:any)=>r.action),['CREATED','CONSENT_REVOKED']);
  // A malicious storage edit must not turn one idempotency key into another case.
  await assert.rejects(pool.query(
    'UPDATE sell.private_owner_case_intents SET request_sha256=$1 WHERE case_id=$2::uuid',
    ['a'.repeat(64),first.caseId]));
  // Another verified user with the same client key gets an independent case.
  const second=await provisionIndividualOwnerCase(strangerToken,payload,deps);
  assert.notEqual(second.caseId,first.caseId);
  const count=await pool.query(
    'SELECT count(*)::integer AS n FROM sell.private_owner_case_intents WHERE idempotency_key=$1',
    [key]);
  assert.equal(count.rows[0].n,2);

  // Token invalidated midway: both case and consent roll back, no orphan.
  let calls=0;
  const expiring:OwnerCaseDependencies={...deps,auth:{authenticate:async token=>{
    calls++;return token===ownerToken&&calls===1?{userId:owner}:null;
  }}};
  const invalidKey='private-session-expired-001';
  await assert.rejects(
    provisionIndividualOwnerCase(ownerToken,{...payload,idempotencyKey:invalidKey},expiring),
    (e:unknown)=>e instanceof OwnerCaseDenied&&e.code==='AUTH_REQUIRED');
  const rolled=await pool.query(
    'SELECT count(*)::integer AS n FROM sell.private_owner_case_intents WHERE idempotency_key=$1',
    [invalidKey]);
  assert.equal(rolled.rows[0].n,0);
 }finally{await pool.end();}
});
