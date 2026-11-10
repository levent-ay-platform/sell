/**
 * Privileged reviewer-only lookup for immutable private evidence.
 * Not exposed to clients, and never uses a user-supplied identity or role flag.
 */
import type {SqlPool} from './postgres-evidence-repository.ts';
import type {AuthorizedEvidence,ReviewerEvidenceLedger} from './evidence-review.ts';

interface AccessRow {
  case_id:string;evidence_id:string;storage_key:string;wrapped_data_key:string;
  key_version:string;file_size:number;sha256:string;evidence_kind:AuthorizedEvidence['kind'];
}
const READ_ASSIGNED = `
SELECT c.id::text AS case_id, f.id::text AS evidence_id,
       f.storage_key, r.wrapped_data_key, r.key_version, r.file_size,
       r.sha256, r.evidence_kind
FROM sell.verification_cases c
JOIN sell.property_revisions pr ON pr.id=c.property_revision_id
JOIN sell.ownership_claims claim ON claim.id=c.ownership_claim_id
  AND claim.property_id=pr.property_id
JOIN sell.parties party ON party.id=claim.claimant_party_id AND party.kind='PERSON'
JOIN sell.case_evidence ce ON ce.case_id=c.id
JOIN sell.evidence_files f ON f.id=ce.evidence_id
JOIN sell.evidence_upload_receipts r ON r.evidence_id=f.id AND r.case_id=c.id
JOIN sell.evidence_consent_events latest ON latest.id=(
  SELECT e.id FROM sell.evidence_consent_events e
  WHERE e.case_id=c.id AND e.person_user_id=r.uploader_user_id
    AND e.purpose='PROPERTY_OWNERSHIP_EVIDENCE'
  ORDER BY e.id DESC LIMIT 1
) AND latest.decision='GRANTED'
WHERE c.id=$1::uuid AND f.id=$2::uuid AND c.assigned_reviewer_user_id=$3::uuid
  AND c.status IN ('PENDING','NEEDS_INFO')
  AND (c.expires_at IS NULL OR c.expires_at>now())
  AND NOT EXISTS(SELECT 1 FROM sell.verification_decisions vd WHERE vd.case_id=c.id)
  AND r.sha256=f.sha256 AND r.media_kind=f.media_kind
FOR UPDATE OF c`;

export class PostgresReviewerEvidenceLedger implements ReviewerEvidenceLedger {
  constructor(private readonly pool:SqlPool) {}
  async authorizeAndAudit(caseId:string,evidenceId:string,reviewerId:string):Promise<AuthorizedEvidence|null> {
    const client=await this.pool.connect();let begun=false;
    try {
      await client.query('BEGIN');begun=true;
      await client.query("SET LOCAL lock_timeout='5s'");
      await client.query("SET LOCAL statement_timeout='10s'");
      const found=await client.query<AccessRow>(READ_ASSIGNED,[caseId,evidenceId,reviewerId]);
      const evidence=found.rows[0];
      if(!evidence){await client.query('COMMIT');begun=false;return null;}
      // Immutable audit is committed BEFORE ciphertext can be opened.
      await client.query(`INSERT INTO sell.audit_events
        (actor_user_id,entity_kind,entity_id,action,metadata)
        VALUES ($1::uuid,'PRIVATE_EVIDENCE',$2::uuid,'ACCESS_STARTED',
          jsonb_build_object('purpose','HUMAN_OWNERSHIP_REVIEW'))`,
          [reviewerId,evidenceId]);
      await client.query('COMMIT');begun=false;
      return {caseId:evidence.case_id,evidenceId:evidence.evidence_id,
        objectKey:evidence.storage_key,wrappedKey:evidence.wrapped_data_key,
        keyVersion:evidence.key_version,fileSize:evidence.file_size,
        digest:evidence.sha256,kind:evidence.evidence_kind};
    } catch(error) {
      if(begun)try{await client.query('ROLLBACK');}catch{/* denied */}
      throw error;
    } finally {client.release();}
  }
}
