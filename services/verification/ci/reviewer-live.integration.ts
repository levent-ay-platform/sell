/** Disposable PostGIS reviewer-access proof. NO real owner documents or tokens. */
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {join} from 'node:path';
import {mkdtemp,rm} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {PostgresIndividualEvidenceRepository} from '../src/postgres-evidence-repository.ts';
import {PostgresReviewerEvidenceLedger} from '../src/postgres-reviewer-evidence.ts';
import {openPrivateEvidenceStore} from '../src/local-private-store.ts';
import {uploadIndividualEvidence} from '../src/evidence-upload.ts';
import {inspectReviewerEvidence} from '../src/evidence-review.ts';
import type {UploadDependencies} from '../src/evidence-upload.ts';
import type {SqlPool} from '../src/postgres-evidence-repository.ts';

const runner=process.env.RUNNER_TEMP;
if(!runner||process.env.MALAK_DISPOSABLE_PG!=='yes')throw Error('DISPOSABLE_POSTGRES_ONLY');
const require=createRequire(join(runner,'malak-pg','driver.cjs'));
const {Pool}=require('pg');
const database=new Pool({host:'127.0.0.1',port:5432,user:'sell_ci',
  password:process.env.PGPASSWORD,database:'sell_ci',max:3,connectionTimeoutMillis:4000});
const pg=database as SqlPool;

test('LIVE PostGIS assigned reviewer: audit-before-read, AES-GCM integrity and revocation deny',async()=>{
  const root=await mkdtemp(join(runner,'malak-reviewer-private-'));
  const master=Uint8Array.from(randomBytes(32));
  try {
    const create=async(query:string,values:unknown[]=[])=> (await database.query(query,values)).rows[0].id as string;
    const seller=await create("INSERT INTO sell.users(phone_e164) VALUES('+21690008721') RETURNING id");
    const reviewer=await create("INSERT INTO sell.users(phone_e164) VALUES('+21690008722') RETURNING id");
    const stranger=await create("INSERT INTO sell.users(phone_e164) VALUES('+21690008723') RETURNING id");
    const party=await create("INSERT INTO sell.parties(kind) VALUES('PERSON') RETURNING id");
    await database.query("INSERT INTO sell.party_memberships(party_id,user_id,capacity) VALUES ($1,$2,'OWNER')",[party,seller]);
    const property=await create("INSERT INTO sell.properties(kind) VALUES('LAND') RETURNING id");
    const revision=await create("INSERT INTO sell.property_revisions(property_id,revision_number,snapshot) VALUES($1,1,'{\"ci\":true}') RETURNING id",[property]);
    const claim=await create("INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity) VALUES($1,$2,'SOLE_OWNER') RETURNING id",[property,party]);
    const caseId=await create("INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,assigned_reviewer_user_id,status) VALUES($1,$2,$3,'PENDING') RETURNING id",[revision,claim,reviewer]);
    await database.query("INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version) VALUES($1,$2,'GRANTED','synthetic-ci')",[caseId,seller]);

    const store=await openPrivateEvidenceStore(root);
    const repo=new PostgresIndividualEvidenceRepository(pg);
    const doc=Buffer.from('%PDF-1.7\nFAKE REVIEWER INTEGRATION ONLY\n%%EOF');
    const upload:UploadDependencies={
      auth:{authenticate:async token=>token==='synthetic-seller-session'?{userId:seller}:null},
      cases:repo,scanner:{scan:async()=> 'CLEAN'},objects:store,
      generateDataKey:async()=>({plaintextKey:Uint8Array.from(master),
        wrappedKey:'SYNTHETIC_WRAPPED_DO_NOT_USE',keyVersion:'v1'}),
    };
    const saved=await uploadIndividualEvidence({bearerToken:'synthetic-seller-session',caseId,
      idempotencyKey:'CI_REVIEWER_PAGE_001',bytes:doc,
      evidenceKind:'OWNERSHIP_CERTIFICATE',pageNumber:1,claimedMime:'application/pdf'},upload);
    assert.equal(saved.ok,true,JSON.stringify(saved));
    if(!saved.ok)throw Error('synthetic fixture creation denied');
    const request={bearerToken:'synthetic-reviewer-session',caseId,evidenceId:saved.evidenceId};
    let consumed=0;
    const core={
      auth:{authenticate:async(token:string)=>token==='synthetic-reviewer-session'?{userId:reviewer}:
        token==='synthetic-stranger'?{userId:stranger}:null},
      ledger:new PostgresReviewerEvidenceLedger(pg),objects:store,
      keys:{unwrapDataKey:async(scope:{caseId:string;evidenceId:string})=>{
        assert.equal(scope.caseId,caseId);assert.equal(scope.evidenceId,saved.evidenceId);
        return Uint8Array.from(master);
      }},
      inspect:async(plain:Uint8Array)=>{consumed++;assert.deepEqual(Buffer.from(plain),doc);}
    };
    const deny=await inspectReviewerEvidence({...request,bearerToken:'synthetic-stranger'},core);
    assert.equal(deny.ok,false);assert.equal(consumed,0);
    const prior=await database.query("SELECT count(*)::integer AS n FROM sell.audit_events WHERE action='ACCESS_STARTED' AND entity_id=$1",[saved.evidenceId]);
    assert.equal(prior.rows[0].n,0);

    const inspected=await inspectReviewerEvidence(request,core);
    assert.deepEqual(inspected,{ok:true,inspected:true,mayPublish:false,ownershipVerified:false});
    assert.equal(consumed,1);
    const audit=await database.query("SELECT actor_user_id::text,metadata FROM sell.audit_events WHERE action='ACCESS_STARTED' AND entity_id=$1",[saved.evidenceId]);
    assert.equal(audit.rows.length,1);assert.equal(audit.rows[0].actor_user_id,reviewer);
    assert.deepEqual(audit.rows[0].metadata,{purpose:'HUMAN_OWNERSHIP_REVIEW'});

    await database.query("INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version) VALUES($1,$2,'REVOKED','synthetic-ci')",[caseId,seller]);
    const afterRevoke=await inspectReviewerEvidence(request,core);
    assert.equal(afterRevoke.ok,false);assert.equal(consumed,1);
    const remaining=await database.query("SELECT count(*)::integer AS n FROM sell.audit_events WHERE action='ACCESS_STARTED' AND entity_id=$1",[saved.evidenceId]);
    assert.equal(remaining.rows[0].n,1);
  } finally {
    master.fill(0);
    await database.end();
    await rm(root,{recursive:true,force:true});
  }
});
