/**
 * Malak F1 natural-person evidence upload use-case.
 * NO public route is mounted here. Production must inject trusted auth, SQL,
 * a private object store, a malware scanning service and a KMS-managed key.
 * Never log these bytes: they can contain Tunisian CINs and land title details.
 */
import {createCipheriv, createHash, randomBytes, randomUUID} from 'node:crypto';
import type {MediaType} from './capture.ts';

const MAX_BYTES = 12 * 1024 * 1024;
const CONSENT_PURPOSE = 'PROPERTY_OWNERSHIP_EVIDENCE';
export type EvidenceKind = 'IDENTITY'|'OWNERSHIP_CERTIFICATE'|'TITLE_EXTRACT'|'OTHER_LEGAL_PROOF';
export type EvidenceUploadFailure =
  | 'AUTH_REQUIRED'|'CASE_UNAVAILABLE'|'CONSENT_REQUIRED'|'FILE_REJECTED'
  | 'FILE_UNSAFE'|'UPLOAD_KEY_INVALID'|'REPLAY_CONFLICT'|'STORAGE_UNAVAILABLE';
export interface AuthenticatedIdentity {
  readonly userId:string;
  /** Explicitly deny all private-document operations for shared-code pilot sessions.
   * A pilot token is NOT evidence of ownership of a +216 phone number.
   * Trusted production authenticators must perform their own identity verification.
   */
  readonly pilotUnverified?:true;
}
export interface Authenticator {
  /** Authenticates a verified, server-supplied bearer credential, never a userId parameter. */
  authenticate(token:string):Promise<AuthenticatedIdentity|null>;
}
export interface ServerConsent {
  readonly personUserId:string;
  readonly purpose:typeof CONSENT_PURPOSE;
  readonly policyVersion:string;
  readonly acceptedAt:string;
  readonly revokedAt:null|string;
}
export interface AuthorizedUploadCase {
  readonly id:string;
  readonly claimantKind:'PERSON'|'COMPANY';
  readonly actorMayUpload:boolean; // derived from CASE+party membership by DB-backed access policy
  readonly status:'PENDING'|'NEEDS_INFO'|'VERIFIED'|'REJECTED'|'SUSPENDED'|'EXPIRED';
  readonly consent:ServerConsent|null;
}
export interface EvidenceRow {
  readonly evidenceId:string;
  readonly caseId:string;
  readonly uploadedBy:string;
  readonly objectKey:string;
  readonly digest:string;
  readonly mime:MediaType;
  readonly kind:EvidenceKind;
  readonly pageNumber:number|null;
  readonly fileSize:number;
  /** Opaque encrypted data key from KMS; private metadata, never exposed to client. */
  readonly wrappedDataKey:string;
  readonly keyVersion:string;
  readonly idempotencyKey:string;
}
export interface CaseEvidenceRepository {
  /** One transaction/advisory lock PER CASE; protects replay, revocation and attach. */
  inCaseLock<T>(caseId:string,action:()=>Promise<T>):Promise<T>;
  /** Must evaluate user membership/consent/case status from trusted records. */
  getUploadCase(caseId:string,userId:string):Promise<AuthorizedUploadCase|null>;
  findReplay(caseId:string,userId:string,key:string):Promise<EvidenceRow|null>;
  /** Must atomically link immutable evidence, idempotency and audit to the case. */
  attachEvidence(row:EvidenceRow):Promise<void>;
}
export interface MalwareScanner {
  scan(bytes:Uint8Array,mime:MediaType):Promise<'CLEAN'|'INFECTED'|'UNKNOWN'>;
}
export interface PrivateObjectStore {
  /** Implementation must enforce private-only objects and encryption key access controls. */
  putPrivate(objectKey:string,encryptedBytes:Uint8Array):Promise<void>;
  deletePrivate(objectKey:string):Promise<void>;
}
export interface UploadDependencies {
  auth:Authenticator;
  cases:CaseEvidenceRepository;
  scanner:MalwareScanner;
  objects:PrivateObjectStore;
  /** Fresh per-object KMS data key. wrappedKey is a KMS-encrypted DEK, NOT plaintext. */
  generateDataKey(scope:{caseId:string;evidenceId:string}):Promise<{plaintextKey:Uint8Array;wrappedKey:string;keyVersion:string}>;
}
export interface EvidenceUploadRequest {
  bearerToken:string;
  caseId:string;
  idempotencyKey:string;
  bytes:Uint8Array;
  evidenceKind:EvidenceKind;
  pageNumber:number|null;
  /** Submitted MIME is NOT trusted, only used to reject inconsistent metadata. */
  claimedMime?:string;
}
export type EvidenceUploadResult =
  | {ok:true;evidenceId:string;uploaded:boolean;mayPublish:false;ownershipVerified:false}
  | {ok:false;code:EvidenceUploadFailure;mayPublish:false;ownershipVerified:false};
const deny=(code:EvidenceUploadFailure):EvidenceUploadResult=>({ok:false,code,mayPublish:false,ownershipVerified:false});
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const KEY=/^[A-Za-z0-9_-]{16,100}$/;
const KINDS=new Set<EvidenceKind>(['IDENTITY','OWNERSHIP_CERTIFICATE','TITLE_EXTRACT','OTHER_LEGAL_PROOF']);
function detectMedia(bytes:Uint8Array):MediaType|null {
  if(bytes.length>=8 && Buffer.from(bytes.subarray(0,8)).equals(Buffer.from([137,80,78,71,13,10,26,10])))return 'image/png';
  if(bytes.length>=4 && bytes[0]===255&&bytes[1]===216&&bytes[2]===255) return 'image/jpeg';
  if(bytes.length>=4 && ((bytes[0]===73&&bytes[1]===73&&bytes[2]===42&&bytes[3]===0)||
     (bytes[0]===77&&bytes[1]===77&&bytes[2]===0&&bytes[3]===42)))return 'image/tiff';
  if(bytes.length>=5 && Buffer.from(bytes.subarray(0,5)).toString('ascii')==='%PDF-')return 'application/pdf';
  return null;
}
function consentValid(access:AuthorizedUploadCase|null,userId:string):boolean {
  const c=access?.consent;
  return !!(c && c.personUserId===userId && c.purpose===CONSENT_PURPOSE &&
    c.policyVersion.length>0 && c.acceptedAt.length>0 && c.revokedAt===null);
}
function allowed(access:AuthorizedUploadCase|null):boolean {
  return !!(access && access.actorMayUpload && access.claimantKind==='PERSON' &&
   (access.status==='PENDING'||access.status==='NEEDS_INFO'));
}
/** AES-256-GCM sealed blob, includes versioned header, random IV, auth tag and AAD. */
function sealEvidence(bytes:Uint8Array,key:Uint8Array,caseId:string,evidenceId:string):Uint8Array {
  if(key.byteLength!==32)throw new Error('INVALID_KMS_DATA_KEY');
  const iv=randomBytes(12), cipher=createCipheriv('aes-256-gcm',key,iv);
  const aad=Buffer.from(`malak-evidence-v1:${caseId}:${evidenceId}`);
  cipher.setAAD(aad);
  const body=Buffer.concat([cipher.update(bytes),cipher.final()]);
  return Buffer.concat([Buffer.from('MALAK-EV1\0'),iv,cipher.getAuthTag(),body]);
}
/**
 * Does NOT accept caller-provided authorization decisions or owner claims.
 * The storage ledger, scanner and authenticated case access are mandatory.
 */
export async function uploadIndividualEvidence(input:EvidenceUploadRequest,deps:UploadDependencies):Promise<EvidenceUploadResult> {
  if(!input||typeof input.bearerToken!=='string'||!input.bearerToken ||
    typeof input.caseId!=='string'||!UUID.test(input.caseId) ||
    typeof input.idempotencyKey!=='string'||!KEY.test(input.idempotencyKey))return deny('UPLOAD_KEY_INVALID');
  const identity=await deps.auth.authenticate(input.bearerToken);
  if(!identity?.userId||identity.pilotUnverified===true)return deny('AUTH_REQUIRED');
  if(!(input.bytes instanceof Uint8Array)||input.bytes.byteLength<20||input.bytes.byteLength>MAX_BYTES||
    !KINDS.has(input.evidenceKind)||
    (input.pageNumber!==null&&(!Number.isInteger(input.pageNumber)||input.pageNumber<1||input.pageNumber>8)))return deny('FILE_REJECTED');
  const mime=detectMedia(input.bytes);
  if(!mime || (input.claimedMime && input.claimedMime!==mime) ||
    (mime!=='application/pdf'&&input.pageNumber===null)||
    (input.evidenceKind==='IDENTITY' &&
      (input.pageNumber!==1||!['image/jpeg','image/png'].includes(mime))))
    return deny('FILE_REJECTED');
  let access=await deps.cases.getUploadCase(input.caseId,identity.userId);
  if(!allowed(access) || access?.id!==input.caseId)return deny('CASE_UNAVAILABLE');
  if(!consentValid(access,identity.userId))return deny('CONSENT_REQUIRED');
  let clean:'CLEAN'|'INFECTED'|'UNKNOWN';
  try {clean=await deps.scanner.scan(input.bytes,mime);}catch{return deny('FILE_UNSAFE');}
  if(clean!=='CLEAN')return deny('FILE_UNSAFE');
  const digest=createHash('sha256').update(input.bytes).digest('hex');
  try {return await deps.cases.inCaseLock(input.caseId,async()=>{
    // Recheck while under lock to prevent consent revocation/decision race.
    access=await deps.cases.getUploadCase(input.caseId,identity.userId);
    if(!allowed(access) || access?.id!==input.caseId)return deny('CASE_UNAVAILABLE');
    if(!consentValid(access,identity.userId))return deny('CONSENT_REQUIRED');
    const previous=await deps.cases.findReplay(input.caseId,identity.userId,input.idempotencyKey);
    if(previous){
      if(previous.digest!==digest||previous.kind!==input.evidenceKind||previous.pageNumber!==input.pageNumber||previous.mime!==mime)
        return deny('REPLAY_CONFLICT');
      return {ok:true,evidenceId:previous.evidenceId,uploaded:false,mayPublish:false,ownershipVerified:false};
    }
    const evidenceId=randomUUID(), objectKey=`private/evidence/${randomUUID()}`;
    let sealed:Uint8Array, wrappedDataKey:string, keyVersion:string;
    try {
      const dataKey=await deps.generateDataKey({caseId:input.caseId,evidenceId});
      // Always zeroize any returned key bytes, even when metadata is malformed.
      try {
        if(!(dataKey.plaintextKey instanceof Uint8Array)||dataKey.plaintextKey.byteLength!==32 ||
           !dataKey.wrappedKey || !dataKey.keyVersion)throw new Error('INVALID_KMS_KEY');
        wrappedDataKey=dataKey.wrappedKey;
        keyVersion=dataKey.keyVersion;
        sealed=sealEvidence(input.bytes,dataKey.plaintextKey,input.caseId,evidenceId);
      } finally { if(dataKey.plaintextKey instanceof Uint8Array)dataKey.plaintextKey.fill(0); }
    }catch{return deny('STORAGE_UNAVAILABLE');}
    try{await deps.objects.putPrivate(objectKey,sealed);}catch{return deny('STORAGE_UNAVAILABLE');}
    const row:EvidenceRow={evidenceId,caseId:input.caseId,uploadedBy:identity.userId,objectKey,digest,mime,
      kind:input.evidenceKind,pageNumber:input.pageNumber,fileSize:input.bytes.byteLength,
      wrappedDataKey,keyVersion,idempotencyKey:input.idempotencyKey};
    try {await deps.cases.attachEvidence(row);}
    catch {
      try{await deps.objects.deletePrivate(objectKey);}catch{/* private orphan requires audited cleanup by storage adapter */}
      return deny('STORAGE_UNAVAILABLE');
    }
    return {ok:true,evidenceId,uploaded:true,mayPublish:false,ownershipVerified:false};
  });}catch{return deny('STORAGE_UNAVAILABLE');}
}
