/**
 * SELL/Malak F1 PostgreSQL adapter for the private individual-evidence domain.
 *
 * No database credentials, connection strings, HTTP endpoint or real owner data
 * exist here. Production injects a least-privilege SQL pool and an authenticated
 * identity boundary. All SQL values are bound parameters, not interpolated.
 */
import {AsyncLocalStorage} from 'node:async_hooks';
import type {
  AuthorizedUploadCase, CaseEvidenceRepository, EvidenceRow,
  ServerConsent,
} from './evidence-upload.ts';

export interface SqlRows<T> { readonly rows: readonly T[]; }
export interface SqlClient {
  query<T extends object = Record<string,unknown>>(sql:string,values?:readonly unknown[]):Promise<SqlRows<T>>;
  release():void;
}
export interface SqlPool {
  connect():Promise<SqlClient>;
  query<T extends object = Record<string,unknown>>(sql:string,values?:readonly unknown[]):Promise<SqlRows<T>>;
}

interface AccessRow {
  id:string;
  claimant_kind:'PERSON'|'COMPANY';
  status:AuthorizedUploadCase['status'];
  actor_may_upload:boolean;
  has_decision:boolean;
  consent_decision:'GRANTED'|'REVOKED'|null;
  consent_policy:string|null;
  consent_recorded_at:Date|string|null;
}
interface ReplayRow {
  evidence_id:string; case_id:string; uploader_user_id:string;
  storage_key:string; sha256:string;
  media_kind:EvidenceRow['mime']; evidence_kind:EvidenceRow['kind'];
  page_number:number|null; file_size:number;
  wrapped_data_key:string; key_version:string; idempotency_key:string;
}

const GET_ACCESS = `
SELECT c.id::text AS id, p.kind AS claimant_kind, c.status,
       EXISTS (SELECT 1 FROM sell.party_memberships pm
               WHERE pm.party_id=p.id AND pm.user_id=$2::uuid
                 AND pm.capacity='OWNER') AS actor_may_upload,
       EXISTS (SELECT 1 FROM sell.verification_decisions vd
               WHERE vd.case_id=c.id) AS has_decision,
       consent.decision AS consent_decision,
       consent.policy_version AS consent_policy,
       consent.recorded_at AS consent_recorded_at
FROM sell.verification_cases c
JOIN sell.ownership_claims claim ON claim.id=c.ownership_claim_id
JOIN sell.parties p ON p.id=claim.claimant_party_id
LEFT JOIN LATERAL (
 SELECT e.decision,e.policy_version,e.recorded_at
 FROM sell.evidence_consent_events e
 WHERE e.case_id=c.id AND e.person_user_id=$2::uuid
       AND e.purpose='PROPERTY_OWNERSHIP_EVIDENCE'
 ORDER BY e.id DESC LIMIT 1
) consent ON true
WHERE c.id=$1::uuid`;

const FIND_REPLAY = `
SELECT r.evidence_id::text,r.case_id::text,r.uploader_user_id::text,
       f.storage_key,f.sha256,r.media_kind,r.evidence_kind,
       r.page_number,r.file_size,r.wrapped_data_key,r.key_version,r.idempotency_key
FROM sell.evidence_upload_receipts r
JOIN sell.evidence_files f ON f.id=r.evidence_id
WHERE r.case_id=$1::uuid AND r.uploader_user_id=$2::uuid
      AND r.idempotency_key=$3`;

// Read only immutable, attached evidence for the *same* authenticated case owner.
// No caller-supplied object-key is accepted and no unscoped ID lookup is allowed.
const GET_PRIVATE_PAIR_EVIDENCE = 
  FIND_REPLAY.replace('AND r.idempotency_key=$3','AND r.evidence_id=$3::uuid');

export class PostgresIndividualEvidenceRepository implements CaseEvidenceRepository {
  private readonly transaction=new AsyncLocalStorage<SqlClient>();
  private readonly pool:SqlPool;
  constructor(pool:SqlPool) {this.pool=pool;}

  private db():SqlClient|SqlPool {return this.transaction.getStore()??this.pool;}
  private requireTransaction():SqlClient {
    const client=this.transaction.getStore();
    if(!client) throw new Error('EVIDENCE_TRANSACTION_REQUIRED');
    return client;
  }

  /** Locks the same case row protected by SQL consent/upload triggers. */
  async inCaseLock<T>(caseId:string,action:()=>Promise<T>):Promise<T> {
    if(this.transaction.getStore())throw new Error('NESTED_EVIDENCE_TRANSACTION_DENIED');
    const client=await this.pool.connect();
    let began=false;
    try {
      await client.query('BEGIN');began=true;
      // Do not let an abandoned worker hold case/consent locks forever.
      await client.query("SET LOCAL lock_timeout = '5s'");
      await client.query("SET LOCAL idle_in_transaction_session_timeout = '90s'");
      const r=await client.query<{id:string}>(
        'SELECT id::text AS id FROM sell.verification_cases WHERE id=$1::uuid FOR UPDATE',
        [caseId]);
      if(r.rows.length!==1)throw new Error('EVIDENCE_CASE_NOT_FOUND');
      const value=await this.transaction.run(client,action);
      await client.query('COMMIT');began=false;
      return value;
    } catch (err) {
      if(began)try{await client.query('ROLLBACK');}catch{/* fail closed */}
      throw err;
    } finally {client.release();}
  }

  async getUploadCase(caseId:string,userId:string):Promise<AuthorizedUploadCase|null> {
    const result=await this.db().query<AccessRow>(GET_ACCESS,[caseId,userId]);
    const a=result.rows[0];
    if(!a||a.actor_may_upload!==true)return null;
    const consent:ServerConsent|null=a.consent_decision==='GRANTED'&&
      typeof a.consent_policy==='string'&&a.consent_policy.length>0&&
      a.consent_recorded_at!==null?{
        personUserId:userId,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',
        policyVersion:a.consent_policy,
        acceptedAt:a.consent_recorded_at instanceof Date?
          a.consent_recorded_at.toISOString():String(a.consent_recorded_at),
        revokedAt:null,
      }:null;
    return {id:a.id,claimantKind:a.claimant_kind,status:a.status,
      actorMayUpload:!a.has_decision,consent};
  }

  async findReplay(caseId:string,userId:string,key:string):Promise<EvidenceRow|null> {
    const db=this.requireTransaction();
    const result=await db.query<ReplayRow>(FIND_REPLAY,[caseId,userId,key]);
    const a=result.rows[0];
    if(!a)return null;
    return {evidenceId:a.evidence_id,caseId:a.case_id,uploadedBy:a.uploader_user_id,
      objectKey:a.storage_key,digest:a.sha256,mime:a.media_kind,
      kind:a.evidence_kind,pageNumber:a.page_number,fileSize:a.file_size,
      wrappedDataKey:a.wrapped_data_key,keyVersion:a.key_version,
      idempotencyKey:a.idempotency_key};
  }

  async getPrivateEvidence(caseId:string,userId:string,evidenceId:string):Promise<EvidenceRow|null> {
    const result=await this.db().query<ReplayRow>(GET_PRIVATE_PAIR_EVIDENCE,[caseId,userId,evidenceId]);
    if(result.rows.length!==1)return null;
    const a=result.rows[0]!;
    if(a.case_id!==caseId||a.uploader_user_id!==userId||a.evidence_id!==evidenceId)
      return null;
    return {evidenceId:a.evidence_id,caseId:a.case_id,uploadedBy:a.uploader_user_id,
      objectKey:a.storage_key,digest:a.sha256,mime:a.media_kind,
      kind:a.evidence_kind,pageNumber:a.page_number,fileSize:a.file_size,
      wrappedDataKey:a.wrapped_data_key,keyVersion:a.key_version,
      idempotencyKey:a.idempotency_key};
  }

  async attachEvidence(row:EvidenceRow):Promise<void> {
    const db=this.requireTransaction();
    // A failed receipt trigger must never leave an unattached evidence row.
    await db.query('SAVEPOINT individual_evidence_attach');
    try {
      await db.query(`INSERT INTO sell.evidence_files
        (id,storage_key,sha256,media_kind)
        VALUES ($1::uuid,$2,$3,$4)`,
        [row.evidenceId,row.objectKey,row.digest,row.mime]);
      await db.query(`INSERT INTO sell.case_evidence(case_id,evidence_id)
        VALUES ($1::uuid,$2::uuid)`,[row.caseId,row.evidenceId]);
      await db.query(`INSERT INTO sell.evidence_upload_receipts
        (case_id,uploader_user_id,evidence_id,idempotency_key,sha256,
         wrapped_data_key,key_version,media_kind,file_size,evidence_kind,page_number)
        VALUES ($1::uuid,$2::uuid,$3::uuid,$4,$5,$6,$7,$8,$9,$10,$11)`,
        [row.caseId,row.uploadedBy,row.evidenceId,row.idempotencyKey,row.digest,
         row.wrappedDataKey,row.keyVersion,row.mime,row.fileSize,row.kind,row.pageNumber]);
      await db.query(`INSERT INTO sell.audit_events(actor_user_id,entity_kind,entity_id,action,metadata)
        VALUES($1::uuid,'PRIVATE_EVIDENCE',$2::uuid,'UPLOADED',
               jsonb_build_object('kind',$3::text,'page',$4::smallint))`,
        [row.uploadedBy,row.evidenceId,row.kind,row.pageNumber]);
      await db.query('RELEASE SAVEPOINT individual_evidence_attach');
    } catch(err) {
      try {
        await db.query('ROLLBACK TO SAVEPOINT individual_evidence_attach');
        await db.query('RELEASE SAVEPOINT individual_evidence_attach');
      } catch{/* outer transaction rolls back on error */}
      throw err;
    }
  }
}
