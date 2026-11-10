/** GitHub-hosted disposable PostgreSQL ONLY, never run against Huawei/production. */
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {join} from 'node:path';
import {randomBytes,randomUUID} from 'node:crypto';
import {PostgresPrivateEvidenceDenialAudit} from '../src/postgres-evidence-denial-audit.ts';
import {PostgresPrivateEvidenceRateLimiter} from '../src/postgres-evidence-rate-limit.ts';
import type {SqlPool} from '../src/postgres-evidence-repository.ts';
const runner=process.env.RUNNER_TEMP;
if(!runner||process.env.MALAK_DISPOSABLE_PG!=='yes')throw Error('DISPOSABLE_PG_ONLY');
const require=createRequire(join(runner,'malak-pg','driver.cjs'));
const {Pool}=require('pg');
const db=new Pool({host:'127.0.0.1',port:5432,user:'sell_ci',
  password:process.env.PGPASSWORD,database:'sell_ci',max:6,connectionTimeoutMillis:4000});
const pool=db as SqlPool;
test('LIVE disposable Postgres: HMAC rate window atomic under concurrency and denial audit anonymous/user',async()=>{
 try {
   const privateSecret=randomBytes(32), remote='127.0.0.1';
   try {
     const limiter=new PostgresPrivateEvidenceRateLimiter(pool,privateSecret,{maxPerMinute:3});
     const outcomes=await Promise.all(Array.from({length:12},()=>limiter.consume({route:'UPLOAD',remote})));
     assert.equal(outcomes.filter(Boolean).length,3);
     assert.equal(outcomes.filter(x=>!x).length,9);
     assert.equal(await limiter.consume({route:'UPLOAD',remote}),false);
     assert.equal(await limiter.consume({route:'INSPECT',remote}),true);
     assert.equal(await limiter.consume({route:'OCR',remote}),true);
     const buckets=await db.query('SELECT bucket_hash,route,hits FROM sell.private_evidence_rate_limits');
     assert.equal(buckets.rows.length,3);
     assert.ok(buckets.rows.every((r:any)=>/^[a-f0-9]{64}$/.test(r.bucket_hash)));
     assert.ok(!JSON.stringify(buckets.rows).includes(remote));
     const audit=new PostgresPrivateEvidenceDenialAudit(pool);
     const requestId=randomUUID();
     await audit.recordDenied({route:'UPLOAD',reason:'RATE_LIMITED',actorUserId:null,requestId});
     const user=(await db.query("INSERT INTO sell.users(phone_e164) VALUES('+21690009887') RETURNING id")).rows[0].id;
     const second=randomUUID();
     await audit.recordDenied({route:'INSPECT',reason:'FORBIDDEN',actorUserId:user,requestId:second});
     const rows=await db.query(`SELECT entity_id::text,actor_user_id::text,action,metadata
       FROM sell.audit_events WHERE entity_id IN ($1::uuid,$2::uuid) ORDER BY id`,[requestId,second]);
     assert.equal(rows.rows.length,2);
     assert.equal(rows.rows[0].actor_user_id,null);
     assert.equal(rows.rows[0].action,'DENIED');
     assert.deepEqual(rows.rows[0].metadata,{route:'UPLOAD',reason:'RATE_LIMITED'});
     assert.equal(rows.rows[1].actor_user_id,user);
     assert.deepEqual(rows.rows[1].metadata,{route:'INSPECT',reason:'FORBIDDEN'});
     assert.ok(!JSON.stringify(rows.rows).includes(remote));
   } finally {privateSecret.fill(0);}
 } finally {await db.end();}
});
