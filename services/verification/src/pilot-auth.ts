/**
 * Optional pilot login for Malak's SAME private service. A shared test code
 * is never a legal identity assertion. Each installation+phone gets its own
 * private, nontransferable pilot namespace to avoid cross-account evidence.
 * Disabled by default; never accepted without explicit server activation.
 */
import {createHash,createHmac,randomBytes,timingSafeEqual} from 'node:crypto';
import type {Authenticator,AuthenticatedIdentity} from './evidence-upload.ts';
import type {SqlPool} from './postgres-evidence-repository.ts';

const PHONE=/^\+216[2-9][0-9]{7}$/;
const DEVICE=/^[A-Za-z0-9_-]{43}$/;
const TOKEN=/^[A-Za-z0-9_-]{43}$/;
export class PilotLoginDenied extends Error { constructor(){super('PILOT_LOGIN_DENIED');} }
export class PilotLoginUnavailable extends Error { constructor(){super('PILOT_LOGIN_UNAVAILABLE');} }

export class SameServicePilotAuth implements Authenticator {
  private readonly secret:Buffer;
  private readonly enabled:boolean;
  constructor(private readonly pool:SqlPool,params:{enabled:boolean;deviceHmacSecret:Uint8Array}){
    if(!(params.deviceHmacSecret instanceof Uint8Array)||params.deviceHmacSecret.length<32||
      params.deviceHmacSecret.length>128)throw new PilotLoginUnavailable();
    this.secret=Buffer.from(params.deviceHmacSecret);
    this.enabled=params.enabled===true;
  }
  async authenticate(token:string):Promise<AuthenticatedIdentity|null>{
    if(!this.enabled||typeof token!=='string'||!TOKEN.test(token))return null;
    const digest=createHash('sha256').update(token).digest('hex');
    const found=await this.pool.query<{user_id:string}>(
      'SELECT user_id::text FROM sell.pilot_sessions WHERE token_digest=$1 AND expires_at>now()',
      [digest]);
    if(found.rows.length!==1)return null;
    return {userId:found.rows[0]!.user_id};
  }
  async login(input:unknown):Promise<{bearer:string;expiresInSeconds:number;pilotConfirmed:true}>{
    if(!this.enabled)throw new PilotLoginUnavailable();
    if(!input||typeof input!=='object'||Array.isArray(input))throw new PilotLoginDenied();
    const row=input as Record<string,unknown>;
    if(Object.keys(row).sort().join(',')!=='code,deviceKey,phone'||
       typeof row.phone!=='string'||!PHONE.test(row.phone)||
       typeof row.code!=='string'||!/^\d{4}$/.test(row.code)||
       typeof row.deviceKey!=='string'||!DEVICE.test(row.deviceKey))
      throw new PilotLoginDenied();
    if(!timingSafeEqual(Buffer.from(row.code,'ascii'),Buffer.from('1234','ascii')))
      throw new PilotLoginDenied();
    const binding=createHmac('sha256',this.secret).update(row.phone).update(':')
      .update(row.deviceKey).digest('hex');
    const token=randomBytes(32).toString('base64url');
    const digest=createHash('sha256').update(token).digest('hex');
    const db=await this.pool.connect().catch(()=>{throw new PilotLoginUnavailable();});
    let active=false;
    try {
      await db.query('BEGIN');active=true;
      await db.query("SET LOCAL lock_timeout = '5s'");
      await db.query("SET LOCAL statement_timeout = '15s'");
      await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1::text,0))',[binding]);
      const prior=await db.query<{user_id:string}>(
        'SELECT user_id::text FROM sell.pilot_accounts WHERE device_phone_digest=$1',[binding]);
      if(prior.rows.length>1)throw new PilotLoginUnavailable();
      let user=prior.rows[0]?.user_id;
      if(!user){
        const inserted=await db.query<{id:string}>(
          'INSERT INTO sell.users(phone_e164) VALUES(NULL) RETURNING id::text AS id');
        user=inserted.rows[0]?.id;
        if(!user)throw new PilotLoginUnavailable();
        await db.query(
          'INSERT INTO sell.pilot_accounts(user_id,device_phone_digest,phone_e164) VALUES($1::uuid,$2,$3)',
          [user,binding,row.phone]);
      }
      await db.query(
        "INSERT INTO sell.pilot_sessions(token_digest,user_id,expires_at) VALUES($1,$2::uuid,now()+interval '12 hours')",
        [digest,user]);
      await db.query('COMMIT');active=false;
      return {bearer:token,expiresInSeconds:43200,pilotConfirmed:true};
    }catch(err){
      if(active)try{await db.query('ROLLBACK');}catch{}
      if(err instanceof PilotLoginDenied||err instanceof PilotLoginUnavailable)throw err;
      throw new PilotLoginUnavailable();
    }finally{db.release();}
  }
}
