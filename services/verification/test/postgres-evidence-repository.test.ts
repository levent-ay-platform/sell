import test from 'node:test';
import assert from 'node:assert/strict';
import {PostgresIndividualEvidenceRepository} from '../src/postgres-evidence-repository.ts';
import type {SqlClient,SqlPool,SqlRows} from '../src/postgres-evidence-repository.ts';
import type {EvidenceRow} from '../src/evidence-upload.ts';

const ID='00000000-0000-4000-8000-000000000001';
const OWNER='00000000-0000-4000-8000-000000000002';
const EVID='00000000-0000-4000-8000-000000000003';
const QUERY_ACCESS={id:ID,claimant_kind:'PERSON',status:'PENDING',actor_may_upload:true,
  has_decision:false,consent_decision:'GRANTED',consent_policy:'v1',consent_recorded_at:new Date('2026-11-10T00:00:00Z')};
const row:EvidenceRow={evidenceId:EVID,caseId:ID,uploadedBy:OWNER,objectKey:'private/evidence/randomkey',
  digest:'a'.repeat(64),mime:'application/pdf',kind:'OWNERSHIP_CERTIFICATE',pageNumber:1,
  fileSize:1000,wrappedDataKey:'wrapped-key-placeholder',keyVersion:'v1',
  idempotencyKey:'SYNTHETIC_KEY_12345'};
type Q={sql:string;args:readonly unknown[]};
class FakeDB implements SqlPool,SqlClient {
  calls:Q[]=[];
  released=0;
  failOn='';
  access:Record<string,unknown>|null=QUERY_ACCESS;
  replay:Record<string,unknown>|null=null;
  async connect():Promise<SqlClient>{return this;}
  release(){this.released++;}
  async query<T extends object = Record<string,unknown>>(sql:string,args:readonly unknown[]=[]):Promise<SqlRows<T>>{
    this.calls.push({sql,args});
    if(this.failOn&&sql.includes(this.failOn))throw Error('synthetic DB fault');
    if(sql.includes('FROM sell.verification_cases c\nJOIN sell.ownership_claims'))return {rows:(this.access?[this.access]:[]) as T[]};
    if(sql.includes('FROM sell.evidence_upload_receipts r'))return {rows:(this.replay?[this.replay]:[]) as T[]};
    if(sql.includes('FOR UPDATE'))return {rows:[{id:ID} as T]};
    return {rows:[]};
  }
}
const uses=(db:FakeDB,s:string)=>db.calls.find(x=>x.sql.includes(s));
test('server-scoped access queries are parameterized and consent maps correctly',async()=>{
  const db=new FakeDB();const r=new PostgresIndividualEvidenceRepository(db);
  const access=await r.getUploadCase(ID,OWNER);
  assert.equal(access?.claimantKind,'PERSON');
  assert.equal(access?.actorMayUpload,true);
  assert.equal(access?.consent?.acceptedAt,'2026-11-10T00:00:00.000Z');
  assert.deepEqual(uses(db,'FROM sell.verification_cases c')?.args,[ID,OWNER]);
  assert.ok(!db.calls[0]!.sql.includes(OWNER));
});
test('other user, revoked consent and finished decisions never pass',async()=>{
  const db=new FakeDB(),r=new PostgresIndividualEvidenceRepository(db);
  db.access={...QUERY_ACCESS,actor_may_upload:false};
  assert.equal(await r.getUploadCase(ID,OWNER),null);
  db.access={...QUERY_ACCESS,consent_decision:'REVOKED'};
  assert.equal((await r.getUploadCase(ID,OWNER))?.consent,null);
  db.access={...QUERY_ACCESS,has_decision:true};
  assert.equal((await r.getUploadCase(ID,OWNER))?.actorMayUpload,false);
});
test('case lock brackets callbacks with committed transaction and releases client',async()=>{
  const db=new FakeDB(),r=new PostgresIndividualEvidenceRepository(db);
  const v=await r.inCaseLock(ID,async()=>{await r.getUploadCase(ID,OWNER);return 'ok';});
  assert.equal(v,'ok');assert.equal(db.calls[0]?.sql,'BEGIN');
  assert.deepEqual(uses(db,'FOR UPDATE')?.args,[ID]);
  assert.equal(db.calls.at(-1)?.sql,'COMMIT');assert.equal(db.released,1);
});
test('failed callback rolls transaction back and releases client',async()=>{
  const db=new FakeDB(),r=new PostgresIndividualEvidenceRepository(db);
  await assert.rejects(()=>r.inCaseLock(ID,async()=>{throw Error('failure');}));
  assert.equal(db.calls.at(-1)?.sql,'ROLLBACK');assert.equal(db.released,1);
});
test('replay lookup is transaction-scoped and returns matched immutable evidence metadata',async()=>{
  const db=new FakeDB(),r=new PostgresIndividualEvidenceRepository(db);
  await assert.rejects(()=>r.findReplay(ID,OWNER,'same-key'),/TRANSACTION_REQUIRED/);
  db.replay={evidence_id:EVID,case_id:ID,uploader_user_id:OWNER,
    storage_key:row.objectKey,sha256:row.digest,media_kind:row.mime,
    evidence_kind:row.kind,page_number:1,file_size:1000,wrapped_data_key:row.wrappedDataKey,
    key_version:'v1',idempotency_key:row.idempotencyKey};
  const replay=await r.inCaseLock(ID,()=>r.findReplay(ID,OWNER,row.idempotencyKey));
  assert.deepEqual(replay,row);
  assert.deepEqual(uses(db,'FROM sell.evidence_upload_receipts r')?.args,[ID,OWNER,row.idempotencyKey]);
});
test('evidence+receipt+audit are inserted only within one transaction and savepoint',async()=>{
  const db=new FakeDB(),r=new PostgresIndividualEvidenceRepository(db);
  await r.inCaseLock(ID,()=>r.attachEvidence(row));
  assert.ok(uses(db,'INSERT INTO sell.evidence_files'));
  assert.ok(uses(db,'INSERT INTO sell.case_evidence'));
  assert.ok(uses(db,'INSERT INTO sell.evidence_upload_receipts'));
  assert.ok(uses(db,'INSERT INTO sell.audit_events'));
  assert.equal(db.calls.at(-1)?.sql,'COMMIT');
  const insert=uses(db,'INSERT INTO sell.evidence_upload_receipts');
  assert.deepEqual(insert?.args.slice(0,5),[ID,OWNER,EVID,row.idempotencyKey,row.digest]);
  assert.ok(!insert?.sql.includes(row.digest));
  const audit=uses(db,'INSERT INTO sell.audit_events');
  assert.ok(!audit?.sql.includes(row.objectKey));
  assert.ok(!audit?.args.includes(row.objectKey));
});
test('database attach error restores savepoint and does not keep partially linked evidence',async()=>{
  const db=new FakeDB(),r=new PostgresIndividualEvidenceRepository(db);
  db.failOn='INSERT INTO sell.evidence_upload_receipts';
  await assert.rejects(()=>r.inCaseLock(ID,()=>r.attachEvidence(row)),/synthetic DB fault/);
  assert.ok(db.calls.some(c=>c.sql==='ROLLBACK TO SAVEPOINT individual_evidence_attach'));
  assert.equal(db.calls.at(-1)?.sql,'ROLLBACK');
  assert.equal(db.released,1);
});
test('rejects nested transaction use to prevent re-entrant case lock',async()=>{
  const db=new FakeDB(),r=new PostgresIndividualEvidenceRepository(db);
  await assert.rejects(()=>r.inCaseLock(ID,()=>r.inCaseLock(ID,async()=>null)),/NESTED_EVIDENCE_TRANSACTION_DENIED/);
  assert.equal(db.calls.at(-1)?.sql,'ROLLBACK');
});
