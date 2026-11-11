/**
 * Malak: private, idempotent individual land-case creation and consent withdrawal.
 *
 * This is an UNMOUNTED server-side domain service. A trusted +216 OTP/session
 * provider must inject Authenticator and a least-privilege PostgreSQL pool.
 * It never issues tokens, impersonates users, exposes document bytes, or grants
 * identity/ownership verification or listing publication.
 */
import {createHash} from 'node:crypto';
import type {Authenticator} from './evidence-upload.ts';
import type {SqlClient,SqlPool} from './postgres-evidence-repository.ts';

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TOKEN=/^[A-Za-z0-9._~+\/-]{20,4096}$/;
const KEY=/^[A-Za-z0-9_-]{16,100}$/;
const POLICY=/^[A-Za-z0-9_.-]{1,100}$/;
const AREA=/^[1-9][0-9]{0,8}(?:\.[0-9]{1,3})?$/;
const PRICE=/^[1-9][0-9]{0,11}$/;
const CONTROL=/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/;
const CAPACITY:Readonly<Record<string,string>>={
  owner:'SOLE_OWNER',partner:'CO_OWNER',heir:'HEIR',mandate:'MANDATE'
};

export type OwnerCaseFailure='AUTH_REQUIRED'|'INVALID_REQUEST'|'REPLAY_CONFLICT'|'CASE_UNAVAILABLE';
export class OwnerCaseDenied extends Error {
  readonly code:OwnerCaseFailure;
  constructor(code:OwnerCaseFailure){super(code);this.code=code;}
}
export class OwnerCaseUnavailable extends Error {
  constructor(){super('OWNER_CASE_SERVICE_UNAVAILABLE');}
}
export interface OwnerCaseDependencies {
  readonly auth:Authenticator;
  readonly pool:SqlPool;
  /** Reviewed, server-controlled consent policy identifier; never supplied by client. */
  readonly policyVersion:string;
}
export interface OwnerCaseInput {
  readonly bearerToken:string;
  /** Generated once for a draft, retained only as an opaque request key. */
  readonly idempotencyKey:string;
  readonly title:string;
  readonly region:string;
  readonly areaSquareMetres:string;
  readonly priceTnd:string;
  readonly capacity:'owner'|'partner'|'heir'|'mandate';
  readonly consentAccepted:true;
}
export interface OwnerCaseResult {
  readonly caseId:string;
  readonly reused:boolean;
  readonly consentRecorded:true;
  readonly ownershipVerified:false;
  readonly identityVerified:false;
  readonly mayPublish:false;
}

function validate(input:unknown):Omit<OwnerCaseInput,'bearerToken'> {
  if(!input||typeof input!=='object'||Array.isArray(input))
    throw new OwnerCaseDenied('INVALID_REQUEST');
  const row=input as Record<string,unknown>;
  const expected=['areaSquareMetres','capacity','consentAccepted','idempotencyKey','priceTnd','region','title'];
  if(Object.keys(row).sort().join(',')!==expected.join(',')||
     row.consentAccepted!==true||typeof row.idempotencyKey!=='string'||
     !KEY.test(row.idempotencyKey)||typeof row.title!=='string'||
     typeof row.region!=='string'||typeof row.areaSquareMetres!=='string'||
     typeof row.priceTnd!=='string'||typeof row.capacity!=='string')
    throw new OwnerCaseDenied('INVALID_REQUEST');
  const title=row.title.trim(),region=row.region.trim();
  if(title.length<2||title.length>100||region.length<2||region.length>100||
     CONTROL.test(title)||CONTROL.test(region)||
     !AREA.test(row.areaSquareMetres)||!PRICE.test(row.priceTnd)||
     Number(row.areaSquareMetres)>1_000_000_000||!Object.hasOwn(CAPACITY,row.capacity))
    throw new OwnerCaseDenied('INVALID_REQUEST');
  return {
    idempotencyKey:row.idempotencyKey,
    title,region,areaSquareMetres:row.areaSquareMetres,priceTnd:row.priceTnd,
    capacity:row.capacity as OwnerCaseInput['capacity'],consentAccepted:true
  };
}
async function requireUser(auth:Authenticator,bearer:string):Promise<string> {
  if(typeof bearer!=='string'||!TOKEN.test(bearer))
    throw new OwnerCaseDenied('AUTH_REQUIRED');
  let identity;
  try {identity=await auth.authenticate(bearer);}
  catch {throw new OwnerCaseDenied('AUTH_REQUIRED');}
  if(!identity?.userId||identity.pilotUnverified===true||!UUID.test(identity.userId))
    throw new OwnerCaseDenied('AUTH_REQUIRED');
  return identity.userId;
}
async function transaction<T>(pool:SqlPool,action:(db:SqlClient)=>Promise<T>):Promise<T> {
  let db:SqlClient;
  try {db=await pool.connect();}catch{throw new OwnerCaseUnavailable();}
  let active=false;
  try {
    await db.query('BEGIN');active=true;
    await db.query("SET LOCAL lock_timeout = '5s'");
    await db.query("SET LOCAL statement_timeout = '15s'");
    const result=await action(db);
    await db.query('COMMIT');active=false;
    return result;
  }catch(err){
    if(active)try{await db.query('ROLLBACK');}catch{/* fail closed */}
    if(err instanceof OwnerCaseDenied||err instanceof OwnerCaseUnavailable)throw err;
    throw new OwnerCaseUnavailable();
  }finally{db.release();}
}
async function stillAuthenticated(deps:OwnerCaseDependencies,token:string,actor:string):Promise<void> {
  if(await requireUser(deps.auth,token)!==actor)throw new OwnerCaseDenied('AUTH_REQUIRED');
}
function one(rows:readonly Record<string,unknown>[]):string {
  const id=rows[0]?.id;
  if(rows.length!==1||typeof id!=='string'||!UUID.test(id))throw new OwnerCaseUnavailable();
  return id;
}

/**
 * Creates PERSON membership, LAND, immutable revision, claim, pending case
 * and append-only consent together. Advisory lock + immutable intent row make
 * a reused key deterministic under concurrent retries; no partial case can commit.
 */
export async function provisionIndividualOwnerCase(
  bearerToken:string,input:unknown,deps:OwnerCaseDependencies
):Promise<OwnerCaseResult> {
  if(!POLICY.test(deps.policyVersion))throw new OwnerCaseUnavailable();
  const actor=await requireUser(deps.auth,bearerToken);
  const data=validate(input);
  const intent=JSON.stringify([
    data.title,data.region,data.areaSquareMetres,data.priceTnd,
    data.capacity,deps.policyVersion
  ]);
  const digest=createHash('sha256').update(intent).digest('hex');
  return transaction(deps.pool,async db=>{
    // User row is provisioned by the REAL identity service, not by this endpoint.
    const principal=await db.query<{id:string}>(
      'SELECT id::text AS id FROM sell.users WHERE id=$1::uuid FOR SHARE',[actor]);
    if(principal.rows.length!==1)throw new OwnerCaseDenied('AUTH_REQUIRED');
    await db.query(
      "SELECT pg_advisory_xact_lock(hashtextextended('malak.owner.case:' || $1::text || ':' || $2::text,0))",
      [actor,data.idempotencyKey]);
    const existing=await db.query<{case_id:string;request_sha256:string}>(
      'SELECT case_id::text,request_sha256 FROM sell.private_owner_case_intents WHERE actor_user_id=$1::uuid AND idempotency_key=$2',
      [actor,data.idempotencyKey]);
    if(existing.rows.length>1)throw new OwnerCaseUnavailable();
    if(existing.rows.length===1){
      const record=existing.rows[0]!;
      if(record.request_sha256!==digest)throw new OwnerCaseDenied('REPLAY_CONFLICT');
      // Synchronize with concurrent revocations before exposing a reusable case.
      await db.query('SELECT id FROM sell.verification_cases WHERE id=$1::uuid FOR UPDATE',[record.case_id]);
      const active=await db.query<{allowed:boolean}>(
        'SELECT sell.case_accepts_individual_evidence($1::uuid,$2::uuid) AS allowed',
        [record.case_id,actor]);
      if(active.rows[0]?.allowed!==true)throw new OwnerCaseDenied('CASE_UNAVAILABLE');
      await stillAuthenticated(deps,bearerToken,actor);
      return {caseId:record.case_id,reused:true,consentRecorded:true,
        ownershipVerified:false,identityVerified:false,mayPublish:false};
    }
    const party=one((await db.query<{id:string}>(
      "INSERT INTO sell.parties(kind) VALUES('PERSON') RETURNING id::text AS id")).rows);
    await db.query(
      "INSERT INTO sell.party_memberships(party_id,user_id,capacity) VALUES($1::uuid,$2::uuid,'OWNER')",
      [party,actor]);
    const property=one((await db.query<{id:string}>(
      "INSERT INTO sell.properties(kind,country_code) VALUES('LAND','TN') RETURNING id::text AS id")).rows);
    const snapshot=JSON.stringify({title:data.title,region:data.region,
      areaSquareMetres:data.areaSquareMetres,priceTnd:data.priceTnd,
      declaredCapacity:CAPACITY[data.capacity]});
    const revision=one((await db.query<{id:string}>(
      'INSERT INTO sell.property_revisions(property_id,revision_number,snapshot) VALUES($1::uuid,1,$2::jsonb) RETURNING id::text AS id',
      [property,snapshot])).rows);
    const claim=one((await db.query<{id:string}>(
      'INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity) VALUES($1::uuid,$2::uuid,$3) RETURNING id::text AS id',
      [property,party,CAPACITY[data.capacity]])).rows);
    const caseId=one((await db.query<{id:string}>(
      "INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,status) VALUES($1::uuid,$2::uuid,'PENDING') RETURNING id::text AS id",
      [revision,claim])).rows);
    await db.query(
      "INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version) VALUES($1::uuid,$2::uuid,'GRANTED',$3)",
      [caseId,actor,deps.policyVersion]);
    await db.query(
      'INSERT INTO sell.private_owner_case_intents(actor_user_id,idempotency_key,request_sha256,case_id) VALUES($1::uuid,$2,$3,$4::uuid)',
      [actor,data.idempotencyKey,digest,caseId]);
    await db.query(
      "INSERT INTO sell.audit_events(actor_user_id,entity_kind,entity_id,action,metadata) VALUES($1::uuid,'VERIFICATION_CASE',$2::uuid,'CREATED','{}'::jsonb)",
      [actor,caseId]);
    // If identity was revoked/switched during the transaction, rollback it all.
    await stillAuthenticated(deps,bearerToken,actor);
    return {caseId,reused:false,consentRecorded:true,
      ownershipVerified:false,identityVerified:false,mayPublish:false};
  });
}

/** Revoke consent even on a closed case. The evidence remains sealed/immutable. */
export async function revokeIndividualOwnerConsent(
  bearerToken:string,caseId:string,deps:OwnerCaseDependencies
):Promise<{revoked:true;ownershipVerified:false;identityVerified:false;mayPublish:false}> {
  if(!POLICY.test(deps.policyVersion)||!UUID.test(caseId))throw new OwnerCaseDenied('INVALID_REQUEST');
  const actor=await requireUser(deps.auth,bearerToken);
  return transaction(deps.pool,async db=>{
    const access=await db.query<{owns:boolean}>(
      `SELECT EXISTS (
         SELECT 1 FROM sell.ownership_claims oc
         JOIN sell.parties p ON p.id=oc.claimant_party_id AND p.kind='PERSON'
         JOIN sell.party_memberships pm ON pm.party_id=p.id
         WHERE oc.id=c.ownership_claim_id AND pm.user_id=$2::uuid AND pm.capacity='OWNER'
       ) AS owns FROM sell.verification_cases c WHERE c.id=$1::uuid FOR UPDATE OF c`,
      [caseId,actor]);
    if(access.rows.length!==1||access.rows[0]?.owns!==true)
      throw new OwnerCaseDenied('CASE_UNAVAILABLE');
    const current=await db.query<{decision:string}>(
      `SELECT decision FROM sell.evidence_consent_events
       WHERE case_id=$1::uuid AND person_user_id=$2::uuid
       ORDER BY id DESC LIMIT 1`,[caseId,actor]);
    if(current.rows[0]?.decision!=='REVOKED'){
      await db.query(
        "INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version) VALUES($1::uuid,$2::uuid,'REVOKED',$3)",
        [caseId,actor,deps.policyVersion]);
      await db.query(
        "INSERT INTO sell.audit_events(actor_user_id,entity_kind,entity_id,action,metadata) VALUES($1::uuid,'VERIFICATION_CASE',$2::uuid,'CONSENT_REVOKED','{}'::jsonb)",
        [actor,caseId]);
    }
    await stillAuthenticated(deps,bearerToken,actor);
    return {revoked:true,ownershipVerified:false,identityVerified:false,mayPublish:false};
  });
}
