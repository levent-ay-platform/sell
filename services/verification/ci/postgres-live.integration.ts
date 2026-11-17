/**
 * Disposable PostgreSQL integration proof for individual-only encrypted evidence.
 * Requires 0001+0002 schema loaded into GitHub-hosted ephemeral PostGIS.
 * No real owners, documents or external services; must NEVER use production DSN.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {join} from 'node:path';
import {randomBytes} from 'node:crypto';
import {PostgresIndividualEvidenceRepository} from '../src/postgres-evidence-repository.ts';
import {uploadIndividualEvidence} from '../src/evidence-upload.ts';
import type {UploadDependencies} from '../src/evidence-upload.ts';
import type {SqlPool} from '../src/postgres-evidence-repository.ts';

const runner = process.env.RUNNER_TEMP;
if (!runner || process.env.MALAK_DISPOSABLE_PG !== 'yes') {
  throw new Error('DISPOSABLE_GITHUB_POSTGRES_ONLY');
}
const require = createRequire(join(runner, 'malak-pg', 'driver.cjs'));
const {Pool} = require('pg');
const database = new Pool({
  host: '127.0.0.1', port: 5432, user: 'sell_ci',
  password: process.env.PGPASSWORD, database: 'sell_ci',
  max: 4, connectionTimeoutMillis: 4000,
});
const repo = new PostgresIndividualEvidenceRepository(database as SqlPool);
const pdf = Buffer.from('%PDF-1.7\n% SYNTHETIC FILE ONLY\n1 0 obj<<>>endobj\n%%EOF\n');
const png = Buffer.concat([
  Buffer.from([137,80,78,71,13,10,26,10]),
  Buffer.from('synthetic image magic; not actual image content')
]);
const objects = new Map<string,Uint8Array>();

async function createFixture() {
  const owner=(await database.query('INSERT INTO sell.users(phone_e164) VALUES ($1) RETURNING id',
    ['+21690009881'])).rows[0].id as string;
  const stranger=(await database.query('INSERT INTO sell.users(phone_e164) VALUES ($1) RETURNING id',
    ['+21690009882'])).rows[0].id as string;
  const party=(await database.query("INSERT INTO sell.parties(kind) VALUES ('PERSON') RETURNING id")).rows[0].id as string;
  await database.query('INSERT INTO sell.party_memberships(party_id,user_id,capacity) VALUES ($1,$2,$3)',
    [party,owner,'OWNER']);
  const property=(await database.query("INSERT INTO sell.properties(kind) VALUES('LAND') RETURNING id")).rows[0].id as string;
  const revision=(await database.query('INSERT INTO sell.property_revisions(property_id,revision_number,snapshot) VALUES ($1,1,$2::jsonb) RETURNING id',
    [property,JSON.stringify({test_case:'synthetic-f1'})])).rows[0].id as string;
  const claim=(await database.query('INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity) VALUES($1,$2,$3) RETURNING id',
    [property,party,'SOLE_OWNER'])).rows[0].id as string;
  const caseId=(await database.query('INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,status) VALUES($1,$2,$3) RETURNING id',
    [revision,claim,'PENDING'])).rows[0].id as string;
  await database.query('INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version) VALUES($1,$2,$3,$4)',
    [caseId,owner,'GRANTED','synthetic-v1']);
  return {caseId,owner,stranger};
}

test('LIVE disposable PostGIS: authorization, consent, AES-GCM upload, page 2, replay, revocation and atomic SQL audit', async () => {
  try {
    const {caseId,owner,stranger}=await createFixture();
    assert.equal((await repo.getUploadCase(caseId,stranger)),null);
    const access=await repo.getUploadCase(caseId,owner);
    assert.equal(access?.claimantKind,'PERSON');
    assert.equal(access?.actorMayUpload,true);
    assert.equal(access?.consent?.purpose,'PROPERTY_OWNERSHIP_EVIDENCE');

    const deps:UploadDependencies={
      auth:{authenticate:async token => token==='synthetic-session' ? {userId:owner}:null},
      cases:repo,
      scanner:{scan:async ()=>'CLEAN'}, // synthetic CI only, not a real malware scan
      objects:{putPrivate:async (key,encrypted)=>{objects.set(key,encrypted);},
        deletePrivate:async key=>{objects.delete(key);}},
      generateDataKey:async ()=>({plaintextKey:new Uint8Array(randomBytes(32)),
        wrappedKey:'SYNTHETIC_WRAPPED_KEY_NOT_REAL',keyVersion:'synthetic-v1'})
    };
    const request={bearerToken:'synthetic-session',caseId,
      idempotencyKey:'PG_LIVE_PAGE_1_ABC',bytes:pdf,
      evidenceKind:'OWNERSHIP_CERTIFICATE' as const,pageNumber:1,
      claimedMime:'application/pdf'};
    const first=await uploadIndividualEvidence(request,deps);
    assert.equal(first.ok,true,JSON.stringify(first));
    if(!first.ok)throw Error('first upload not accepted');
    assert.equal(first.uploaded,true);
    assert.equal(first.mayPublish,false);
    assert.equal(first.ownershipVerified,false);
    // Same-case and same-uploader lookup must never expose private object
    // keys to an unrelated user or case even if the UUID is known.
    const scoped=await repo.getPrivateEvidence(caseId,owner,first.evidenceId);
    assert.equal(scoped?.evidenceId,first.evidenceId);
    assert.equal(scoped?.caseId,caseId);
    assert.equal(scoped?.uploadedBy,owner);
    assert.equal(scoped?.kind,'OWNERSHIP_CERTIFICATE');
    assert.ok(scoped?.objectKey.startsWith('private/evidence/'));
    assert.equal(await repo.getPrivateEvidence(caseId,stranger,first.evidenceId),null);
    assert.equal(await repo.getPrivateEvidence('00000000-0000-4000-8000-000000000000',
      owner,first.evidenceId),null);
    assert.equal(await repo.getPrivateEvidence(caseId,owner,
      '00000000-0000-4000-8000-000000000000'),null);
    const repeat=await uploadIndividualEvidence(request,deps);
    assert.equal(repeat.ok,true);
    if(repeat.ok){assert.equal(repeat.uploaded,false);assert.equal(repeat.evidenceId,first.evidenceId);}
    const collision=await uploadIndividualEvidence({...request,bytes:Buffer.concat([pdf,Buffer.from('X')])},deps);
    assert.equal(collision.ok,false);
    if(!collision.ok)assert.equal(collision.code,'REPLAY_CONFLICT');

    // A scanned 2-page certificate can be submitted as two numbered photos.
    const second=await uploadIndividualEvidence({...request,idempotencyKey:'PG_LIVE_PAGE_2_ABC',
      bytes:png,pageNumber:2,claimedMime:'image/png'},deps);
    assert.equal(second.ok,true,JSON.stringify(second));
    const receipts=await database.query(`SELECT r.evidence_id::text,r.page_number,r.evidence_kind,r.wrapped_data_key,
      f.sha256,f.media_kind,ce.case_id::text FROM sell.evidence_upload_receipts r
      JOIN sell.evidence_files f ON f.id=r.evidence_id
      JOIN sell.case_evidence ce ON ce.evidence_id=r.evidence_id
      WHERE r.case_id=$1::uuid ORDER BY r.uploaded_at,r.evidence_id`,[caseId]);
    assert.equal(receipts.rows.length,2);
    assert.deepEqual(receipts.rows.map((r:any)=>r.page_number).sort(),[1,2]);
    assert.ok(receipts.rows.every((r:any)=>r.case_id===caseId && r.sha256.length===64));
    const audit=await database.query(`SELECT count(*)::integer AS n FROM sell.audit_events
      WHERE entity_kind='PRIVATE_EVIDENCE' AND action='UPLOADED'
      AND entity_id IN (SELECT evidence_id FROM sell.evidence_upload_receipts WHERE case_id=$1)`,[caseId]);
    assert.equal(audit.rows[0].n,2);
    assert.equal(objects.size,2);
    assert.ok([...objects.values()].every(ciphertext=>!Buffer.from(ciphertext).includes(Buffer.from('SYNTHETIC FILE ONLY'))));

    await database.query(`INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
      VALUES($1,$2,'REVOKED','synthetic-v1')`,[caseId,owner]);
    assert.equal((await repo.getUploadCase(caseId,owner))?.consent,null);
    const blocked=await uploadIndividualEvidence({...request,idempotencyKey:'PG_LIVE_AFTER_REVOKE',
      pageNumber:3},deps);
    assert.equal(blocked.ok,false);
    if(!blocked.ok)assert.equal(blocked.code,'CONSENT_REQUIRED');
    assert.equal(objects.size,2);
  } finally {
    await database.end();
  }
});
