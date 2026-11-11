/**
 * Append-only denial audit adapter. Does not capture raw URL, token, IP or
 * document content. Independent of case existence to cover malicious attempts.
 */
import type {SqlPool} from './postgres-evidence-repository.ts';
import type {DenialAuditRecord} from './private-evidence-http.ts';
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ROUTES=new Set(['UPLOAD','INSPECT','OCR','PAIR','OWNER_CASE','CONSENT_REVOKE','UNKNOWN']);
const REASONS=new Set(['BAD_ROUTE','BAD_REQUEST','UNAUTHENTICATED','FORBIDDEN','RATE_LIMITED','FILE_REJECTED','REPLAY_CONFLICT','UPSTREAM_UNAVAILABLE']);
export class PostgresPrivateEvidenceDenialAudit {
  private readonly pool:SqlPool;
  constructor(pool:SqlPool) {this.pool=pool;}
  async recordDenied(record:DenialAuditRecord):Promise<void> {
    if(!record||!ROUTES.has(record.route)||!REASONS.has(record.reason)||
       !UUID.test(record.requestId)||
       (record.actorUserId!==null&&!UUID.test(record.actorUserId)))
      throw new Error('AUDIT_RECORD_INVALID');
    await this.pool.query(`INSERT INTO sell.audit_events
      (actor_user_id,entity_kind,entity_id,action,metadata)
      VALUES($1::uuid,'PRIVATE_EVIDENCE_REQUEST',$2::uuid,'DENIED',$3::jsonb)`,
      [record.actorUserId,record.requestId,JSON.stringify({route:record.route,reason:record.reason})]);
  }
}
