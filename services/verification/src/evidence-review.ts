/**
 * Private, server-only evidence review: never mount this as a public download route.
 * Caller supplies a verified session and a trusted internal inspector. The only
 * external result is an opaque success/denial; no bytes, OCR or keys are returned.
 */
import {createDecipheriv, createHash, timingSafeEqual} from 'node:crypto';
import type {Authenticator, EvidenceKind} from './evidence-upload.ts';
import type {VaultTransitDataKeys} from './vault-transit.ts';
import type {PrivateCiphertextReader} from './local-private-store.ts';

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SHA256=/^[0-9a-f]{64}$/;
const HEADER=Buffer.from('MALAK-EV1\0');
const MAX_FILE=12*1024*1024;
const MAX_SEALED=MAX_FILE+4096;

/** Access is resolved from trusted SQL rows, never from user-provided role flags. */
export interface AuthorizedEvidence {
  readonly caseId:string;
  readonly evidenceId:string;
  readonly objectKey:string;
  readonly wrappedKey:string;
  readonly keyVersion:string;
  readonly fileSize:number;
  readonly digest:string;
  readonly kind:EvidenceKind;
}
export interface ReviewerEvidenceLedger {
  /** Must atomically check assigned reviewer, case state, consent and case evidence,
   * then COMMIT immutable ACCESS_STARTED audit before returning any metadata. */
  authorizeAndAudit(caseId:string,evidenceId:string,reviewerId:string):Promise<AuthorizedEvidence|null>;
}
export interface PrivateEvidenceInspectionDependencies {
  readonly auth:Authenticator;
  readonly ledger:ReviewerEvidenceLedger;
  readonly objects:PrivateCiphertextReader;
  readonly keys:Pick<VaultTransitDataKeys,'unwrapDataKey'>;
  /** Trusted private worker only; MUST NOT persist, log or return plaintext. */
  readonly inspect:(plaintext:Uint8Array,kind:EvidenceKind)=>Promise<void>;
}
export interface ReviewRequest {readonly bearerToken:string;readonly caseId:string;readonly evidenceId:string;}
export type ReviewInspectionResult=
  | {ok:true;inspected:true;mayPublish:false;ownershipVerified:false}
  | {ok:false;code:'AUTH_REQUIRED'|'ACCESS_DENIED'|'EVIDENCE_UNAVAILABLE';mayPublish:false;ownershipVerified:false};
const denied=(code:'AUTH_REQUIRED'|'ACCESS_DENIED'|'EVIDENCE_UNAVAILABLE'):ReviewInspectionResult=>({ok:false,code,mayPublish:false,ownershipVerified:false});

/** Any failed authentication, access, file, key or integrity check fails closed. */
export async function inspectReviewerEvidence(
  request:ReviewRequest,deps:PrivateEvidenceInspectionDependencies
):Promise<ReviewInspectionResult> {
  if(!request||typeof request.bearerToken!=='string'||!request.bearerToken||
     !UUID.test(request.caseId)||!UUID.test(request.evidenceId))return denied('ACCESS_DENIED');
  let identity;
  try {identity=await deps.auth.authenticate(request.bearerToken);}catch{return denied('AUTH_REQUIRED');}
  if(!identity?.userId||!UUID.test(identity.userId))return denied('AUTH_REQUIRED');
  let row:AuthorizedEvidence|null;
  try {row=await deps.ledger.authorizeAndAudit(request.caseId,request.evidenceId,identity.userId);}
  catch{return denied('EVIDENCE_UNAVAILABLE');}
  if(!row)return denied('ACCESS_DENIED');
  if(row.caseId!==request.caseId||row.evidenceId!==request.evidenceId||
    !SHA256.test(row.digest)||!Number.isSafeInteger(row.fileSize)||
    row.fileSize<1||row.fileSize>MAX_FILE||
    !/^private\/evidence\/[0-9a-f-]{36}$/i.test(row.objectKey))return denied('EVIDENCE_UNAVAILABLE');

  let sealed:Uint8Array|undefined,key:Uint8Array|undefined,plaintext:Buffer|undefined;
  try {
    sealed=await deps.objects.readPrivate(row.objectKey);
    if(!(sealed instanceof Uint8Array)||sealed.byteLength<HEADER.length+12+16+1||
      sealed.byteLength>MAX_SEALED||
      !Buffer.from(sealed.subarray(0,HEADER.length)).equals(HEADER))return denied('EVIDENCE_UNAVAILABLE');
    key=await deps.keys.unwrapDataKey({caseId:row.caseId,evidenceId:row.evidenceId,
      wrappedKey:row.wrappedKey,keyVersion:row.keyVersion});
    if(!(key instanceof Uint8Array)||key.byteLength!==32)return denied('EVIDENCE_UNAVAILABLE');
    const iv=sealed.subarray(HEADER.length,HEADER.length+12);
    const tag=sealed.subarray(HEADER.length+12,HEADER.length+28);
    const data=sealed.subarray(HEADER.length+28);
    const decipher=createDecipheriv('aes-256-gcm',key,iv);
    decipher.setAAD(Buffer.from(`malak-evidence-v1:${row.caseId}:${row.evidenceId}`));
    decipher.setAuthTag(Buffer.from(tag));
    plaintext=Buffer.concat([decipher.update(data),decipher.final()]);
    if(plaintext.byteLength!==row.fileSize)return denied('EVIDENCE_UNAVAILABLE');
    const expected=Buffer.from(row.digest,'hex');
    const actual=createHash('sha256').update(plaintext).digest();
    try {if(!timingSafeEqual(expected,actual))return denied('EVIDENCE_UNAVAILABLE');}
    finally {actual.fill(0);expected.fill(0);}
    // The trusted internal consumer must process the plaintext synchronously within
    // this awaited scope. Not an HTTP download, app reply or public OCR result.
    await deps.inspect(plaintext,row.kind);
    return {ok:true,inspected:true,mayPublish:false,ownershipVerified:false};
  } catch {
    return denied('EVIDENCE_UNAVAILABLE');
  } finally {
    if(plaintext)plaintext.fill(0);
    if(key instanceof Uint8Array)key.fill(0);
    if(sealed instanceof Uint8Array)sealed.fill(0);
  }
}
