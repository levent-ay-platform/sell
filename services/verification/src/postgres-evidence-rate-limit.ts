/**
 * PostgreSQL-backed atomic request limiter for Malak's private API ingress.
 * A configured high-entropy server secret HMACs the observed remote address;
 * raw IPs and bearer tokens never enter the database. For production, ensure
 * the transport gets the *real verified* remote from a trusted TLS gateway.
 */
import {createHmac} from 'node:crypto';
import {isIP} from 'node:net';
import type {SqlPool} from './postgres-evidence-repository.ts';
import type {PrivateRoute} from './private-evidence-http.ts';

const ROUTES=new Set(['UPLOAD','INSPECT','OCR','PAIR','OWNER_CASE','CONSENT_REVOKE','PILOT_LOGIN','UNKNOWN']);
export class PostgresPrivateEvidenceRateLimiter {
  private readonly pool:SqlPool;
  private readonly secret:Buffer;
  private readonly maxPerMinute:number;
  constructor(pool:SqlPool,serverSecret:Uint8Array,{maxPerMinute=12}:{maxPerMinute?:number}={}) {
    if(!(serverSecret instanceof Uint8Array)||serverSecret.byteLength<32||serverSecret.byteLength>128)
      throw new Error('RATE_LIMIT_SECRET_REQUIRED');
    if(!Number.isInteger(maxPerMinute)||maxPerMinute<1||maxPerMinute>60)
      throw new Error('RATE_LIMIT_POLICY_INVALID');
    this.pool=pool;this.secret=Buffer.from(serverSecret);this.maxPerMinute=maxPerMinute;
  }
  async consume(input:{route:PrivateRoute|'UNKNOWN';remote:string}):Promise<boolean> {
    if(!input || !ROUTES.has(input.route)||typeof input.remote!=='string'||
       !(isIP(input.remote)>0))throw new Error('RATE_LIMIT_REMOTE_INVALID');
    const bucket=createHmac('sha256',this.secret)
      .update(`malak-private-http-v1:${input.route}:${input.remote}`,'utf8').digest('hex');
    const result=await this.pool.query<{hits:number}>(`
      INSERT INTO sell.private_evidence_rate_limits(bucket_hash,route,window_started,hits)
      VALUES($1,$2,date_trunc('minute',clock_timestamp()),1)
      ON CONFLICT(bucket_hash,route,window_started)
      DO UPDATE SET hits=sell.private_evidence_rate_limits.hits+1
      WHERE sell.private_evidence_rate_limits.hits < $3
      RETURNING hits`,[bucket,input.route,this.maxPerMinute]);
    return result.rows.length===1 && Number.isInteger(result.rows[0]?.hits) &&
      result.rows[0]!.hits>0 && result.rows[0]!.hits<=this.maxPerMinute;
  }
}
