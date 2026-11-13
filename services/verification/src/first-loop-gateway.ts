/**
 * Composition root for Malak's first verification loop.
 *
 * NO HTTP/HTTPS listener, environment variable, public route, fixed test PIN,
 * credential, document, or deployment side effect is created here.
 * A deployment MUST inject a separately reviewed +216 phone-verified provider,
 * a real PostgreSQL pool, malware scanner, private encrypted object store,
 * Vault Transit, OCR worker, and secret-backed distributed rate limit/audit.
 * Mount only behind an authorized isolated TLS endpoint; not from a PR runner.
 */
import type {Authenticator,PrivateObjectStore,MalwareScanner,UploadDependencies} from './evidence-upload.ts';
import {uploadIndividualEvidence} from './evidence-upload.ts';
import type {SqlPool} from './postgres-evidence-repository.ts';
import {PostgresIndividualEvidenceRepository} from './postgres-evidence-repository.ts';
import {PostgresPrivateEvidenceRateLimiter} from './postgres-evidence-rate-limit.ts';
import {PostgresPrivateEvidenceDenialAudit} from './postgres-evidence-denial-audit.ts';
import {createPrivateEvidenceHttpHandler} from './private-evidence-http.ts';
import type {PrivateCiphertextReader} from './local-private-store.ts';
import type {PrivateOcrWorker} from './seller-ocr-service.ts';
import type {CinV5Worker} from './private-cin-v5-service.ts';
import {previewOwnerCin,sanitizeCinFields} from './private-cin-v5-service.ts';
import {SameServicePilotAuth} from './pilot-auth.ts';
import type {PrivatePairDependencies} from './private-pair-service.ts';
import {analyzeAuthorizedPrivateEvidencePair} from './private-pair-service.ts';
import {provisionIndividualOwnerCase,revokeIndividualOwnerConsent} from './private-owner-case.ts';

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PHONE=/^\+216[2-9][0-9]{7}$/;
const BEARER=/^[A-Za-z0-9._~+\/-]{20,4096}$/;
const MAX_SESSION_MS=12*60*60*1000;

/** A provider must validate OTP challenge or OIDC token signature/server-side.
 * A client cannot assert these fields to the gateway.
 */
export interface VerifiedOwnerProviderClaim {
  readonly userId:string;
  readonly verifiedPhoneE164:string;
  readonly assurance:'VERIFIED_PHONE_OTP'|'OIDC_VERIFIED_PHONE';
  readonly verifiedAtUnixMs:number;
  readonly expiresAtUnixMs:number;
}
export interface VerifiedOwnerSessionProvider {
  /** NEVER implement by accepting a user-supplied userId, phone or fixed 1234. */
  verifyBearer(bearerToken:string):Promise<VerifiedOwnerProviderClaim|null>;
}

/** Whitelist only short-lived, independently verified phone sessions.
 * Return ONLY userId to the evidence domain; strip phone and provider metadata.
 */
export function authenticatorFromVerifiedOwnerProvider(
  provider:VerifiedOwnerSessionProvider,now:()=>number=Date.now
):Authenticator {
  if(!provider||typeof provider.verifyBearer!=='function'||typeof now!=='function')
    throw new Error('VERIFIED_OWNER_PROVIDER_REQUIRED');
  return {async authenticate(token:string){
    if(typeof token!=='string'||!BEARER.test(token))return null;
    let claim:VerifiedOwnerProviderClaim|null;
    try{claim=await provider.verifyBearer(token);}
    catch{return null;} // provider failure is never permission
    if(!claim||typeof claim!=='object'||!UUID.test(claim.userId)||
      !PHONE.test(claim.verifiedPhoneE164)||
      !['VERIFIED_PHONE_OTP','OIDC_VERIFIED_PHONE'].includes(claim.assurance)||
      !Number.isSafeInteger(claim.verifiedAtUnixMs)||
      !Number.isSafeInteger(claim.expiresAtUnixMs))return null;
    const instant=now();
    if(!Number.isSafeInteger(instant)||claim.verifiedAtUnixMs>instant||
      claim.expiresAtUnixMs<=instant||
      claim.expiresAtUnixMs<=claim.verifiedAtUnixMs||
      claim.expiresAtUnixMs-claim.verifiedAtUnixMs>MAX_SESSION_MS||
      instant-claim.verifiedAtUnixMs>=MAX_SESSION_MS)return null;
    return {userId:claim.userId};
  }};
}
export interface UnboundFirstLoopGatewayDeps {
  readonly provider:VerifiedOwnerSessionProvider;
  readonly pool:SqlPool;
  readonly objects:PrivateObjectStore&PrivateCiphertextReader;
  readonly scanner:MalwareScanner;
  readonly keys:Pick<PrivatePairDependencies['keys'],'unwrapDataKey'> &
    {generateDataKey:UploadDependencies['generateDataKey']};
  readonly worker:PrivateOcrWorker;
  /** Optional Huawei-only v5 recognizer, injected after private host acceptance. */
  readonly cinWorker?:CinV5Worker;
  /** QA pilot may use this SAME service for OCR after server-issued login.
   * Explicit private deployment opt-in; off for production by default.
   */
  readonly pilotQa?:{enabled:true;deviceHmacSecret:Uint8Array};
  readonly rateLimitHmacSecret:Uint8Array;
  readonly policyVersion:string;
  /** Optional deterministic clock for isolated synthetic-only tests. */
  readonly now?:()=>number;
}

/** Private case → authenticated upload → ciphertext → OCR → preliminary pair.
 * Returns only an unmounted handler. No reviewer/plaintext-inspect endpoint is
 * enabled. Pilot login is deliberately not connected to this composition.
 */
export function composeUnboundMalakFirstLoop(deps:UnboundFirstLoopGatewayDeps) {
  if(!deps||!deps.pool||!deps.objects||!deps.scanner||!deps.keys||
    !deps.worker||!deps.rateLimitHmacSecret||
    typeof deps.policyVersion!=='string'||!/^[A-Za-z0-9_-]{3,70}$/.test(deps.policyVersion))
    throw new Error('FIRST_LOOP_CONFIGURATION_INCOMPLETE');
  const verifiedAuth=authenticatorFromVerifiedOwnerProvider(deps.provider,deps.now);
  const pilotAuth=deps.pilotQa?.enabled===true
    ?new SameServicePilotAuth(deps.pool,{
      enabled:true,deviceHmacSecret:deps.pilotQa.deviceHmacSecret
    }):null;
  // A pilot is still unverified for all real owner/case/publishing routes.
  const auth:Authenticator={authenticate:async token=>
    (await verifiedAuth.authenticate(token))??(
      pilotAuth?await pilotAuth.authenticate(token):null)};
  const cases=new PostgresIndividualEvidenceRepository(deps.pool);
  const limits=new PostgresPrivateEvidenceRateLimiter(deps.pool,deps.rateLimitHmacSecret);
  const audit=new PostgresPrivateEvidenceDenialAudit(deps.pool);
  const caseDeps={auth,pool:deps.pool,policyVersion:deps.policyVersion};
  return createPrivateEvidenceHttpHandler({
    auth,limits,audit,
    upload:request=>uploadIndividualEvidence(request,{
      auth,cases,objects:deps.objects,scanner:deps.scanner,
      generateDataKey:scope=>deps.keys.generateDataKey(scope)
    }),
    inspect:async()=>({ok:false as const,code:'ACCESS_DENIED' as const,
      ownershipVerified:false as const,mayPublish:false as const}),
    previewCin:deps.cinWorker?request=>previewOwnerCin(request,{
      auth,cases,worker:deps.cinWorker!
    }):undefined,
    loginPilot:pilotAuth?input=>pilotAuth.login(input):undefined,
    previewPilotCin:pilotAuth&&deps.cinWorker?
      async(bearer,bytes,mime)=>{
        // Explicit pilot OCR is transient and never provisions a case.
        const before=await pilotAuth.authenticate(bearer);
        if(!before?.userId||before.pilotUnverified!==true)
          throw Error('PILOT_OCR_AUTH');
        const fields=await deps.cinWorker!.readFront(bytes,mime);
        const after=await pilotAuth.authenticate(bearer);
        if(!after?.userId||after.pilotUnverified!==true||
           after.userId!==before.userId)throw Error('PILOT_OCR_EXPIRED');
        return sanitizeCinFields(fields);
      }:undefined,
    analyzeOwnerPair:request=>analyzeAuthorizedPrivateEvidencePair(request,{
      auth,cases,objects:deps.objects,worker:deps.worker,
      keys:{unwrapDataKey:key=>deps.keys.unwrapDataKey(key)}
    }),
    provisionOwnerCase:(bearer,input)=>provisionIndividualOwnerCase(bearer,input,caseDeps),
    withdrawOwnerConsent:(bearer,caseId)=>
      revokeIndividualOwnerConsent(bearer,caseId,caseDeps)
  });
}
