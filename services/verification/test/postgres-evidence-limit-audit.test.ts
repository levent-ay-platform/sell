import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {PostgresPrivateEvidenceRateLimiter} from '../src/postgres-evidence-rate-limit.ts';
import {PostgresPrivateEvidenceDenialAudit} from '../src/postgres-evidence-denial-audit.ts';
import type {SqlPool} from '../src/postgres-evidence-repository.ts';

const secret=Uint8Array.from({length:32},(_,i)=>i+1);
function mockDb(){
  const calls:{sql:string;values:readonly unknown[]}[]=[];
  const pool={query:async(sql:string,values:readonly unknown[]=[])=>{
    calls.push({sql,values});return {rows:[{hits:1}]};
  }} as unknown as SqlPool;
  return {calls,pool};
}
test('atomic rate limiter uses HMAC IP hash with bound parameters not a raw user IP or token',async()=>{
  const db=mockDb();const limit=new PostgresPrivateEvidenceRateLimiter(db.pool,secret);
  assert.equal(await limit.consume({route:'UPLOAD',remote:'127.0.0.1'}),true);
  assert.equal(db.calls.length,1);
  assert.equal(db.calls[0].values[1],'UPLOAD');
  assert.equal(db.calls[0].values[2],12);
  assert.match(String(db.calls[0].values[0]),/^[a-f0-9]{64}$/);
  assert.ok(!JSON.stringify(db.calls).includes('127.0.0.1'));
  assert.ok(db.calls[0].sql.includes('ON CONFLICT'));
});
test('rate limiter fails closed on non-IP identities and short entropy secrets',async()=>{
  const db=mockDb();assert.throws(()=>new PostgresPrivateEvidenceRateLimiter(db.pool,new Uint8Array(8)),/SECRET/);
  const limiter=new PostgresPrivateEvidenceRateLimiter(db.pool,secret);
  await assert.rejects(()=>limiter.consume({route:'UPLOAD',remote:'attacker-controlled'}),/REMOTE_INVALID/);
  assert.equal(db.calls.length,0);
});
test('denial audit only accepts bounded route/decision enum and redacted request ID',async()=>{
  const db=mockDb(),audit=new PostgresPrivateEvidenceDenialAudit(db.pool),requestId=randomUUID();
  await audit.recordDenied({route:'UNKNOWN',reason:'BAD_ROUTE',actorUserId:null,requestId});
  assert.equal(db.calls.length,1);
  assert.deepEqual(db.calls[0].values,[null,requestId,JSON.stringify({route:'UNKNOWN',reason:'BAD_ROUTE'})]);
  await assert.rejects(()=>audit.recordDenied({route:'UNKNOWN',reason:'BAD_ROUTE',actorUserId:'a token',requestId}),/INVALID/);
  assert.equal(db.calls.length,1);
});
