/**
 * Owner-only immediate OCR presentation after an already-authorized encrypted
 * upload. This is a product MVP bridge, NOT an identity/title verification.
 * It never grants publication or returns raw OCR text/document bytes.
 */
import type {Authenticator, AuthorizedUploadCase, CaseEvidenceRepository} from './evidence-upload.ts';
import {readUntrustedOcrDocument} from './reader.ts';
import {presentPrivateCpfReading} from './ocr-presentation.ts';
import type {PrivateOcrPresentation} from './ocr-presentation.ts';

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_BYTES=12*1024*1024;
export interface SellerOcrRequest {
  bearerToken:string;
  caseId:string;
  evidenceId:string;
  bytes:Uint8Array;
  mime:'application/pdf'|'image/png'|'image/jpeg'|'image/tiff';
  declaredArea:string|null;
}
export interface PrivateOcrWorker {
  extract(bytes:Uint8Array,mime:SellerOcrRequest['mime']):Promise<unknown>;
}
export interface SellerOcrDeps {
  auth:Authenticator;
  cases:Pick<CaseEvidenceRepository,'getUploadCase'>;
  worker:PrivateOcrWorker;
}
function currentlyAllowed(c:AuthorizedUploadCase|null,caseId:string,userId:string):boolean {
  const consent=c?.consent;
  return c?.id===caseId && c?.claimantKind==='PERSON' && c.actorMayUpload &&
    (c.status==='PENDING'||c.status==='NEEDS_INFO') &&
    consent?.personUserId===userId && consent.purpose==='PROPERTY_OWNERSHIP_EVIDENCE' &&
    consent.revokedAt===null && !!consent.policyVersion && !!consent.acceptedAt;
}
export class PrivateOcrAccessDenied extends Error {
  constructor(){super('PRIVATE_OCR_ACCESS_DENIED');}
}
/**
 * Authenticated owner, unchanged case and current consent both before AND after
 * potentially expensive OCR. No display if consent is revoked during the scan.
 * Uploaded bytes have already passed the mandatory malware scanner and seal.
 */
export async function analyzeOwnerUploadedEvidence(request:SellerOcrRequest,deps:SellerOcrDeps):Promise<PrivateOcrPresentation> {
  if(!request || !UUID.test(request.caseId)||!UUID.test(request.evidenceId)||
    !(request.bytes instanceof Uint8Array)||request.bytes.byteLength<20||request.bytes.byteLength>MAX_BYTES||
    !['application/pdf','image/png','image/jpeg','image/tiff'].includes(request.mime)||
    (request.declaredArea!==null && (typeof request.declaredArea!=='string'||
       request.declaredArea.length>32)))throw new PrivateOcrAccessDenied();
  const first=await deps.auth.authenticate(request.bearerToken);
  if(!first?.userId||first.pilotUnverified===true||!UUID.test(first.userId))
    throw new PrivateOcrAccessDenied();
  const before=await deps.cases.getUploadCase(request.caseId,first.userId);
  if(!currentlyAllowed(before,request.caseId,first.userId))throw new PrivateOcrAccessDenied();
  const payload=await deps.worker.extract(request.bytes,request.mime);
  const reading=presentPrivateCpfReading(readUntrustedOcrDocument(payload),request.declaredArea);
  const second=await deps.auth.authenticate(request.bearerToken);
  if(second?.pilotUnverified===true||second?.userId!==first.userId)
    throw new PrivateOcrAccessDenied();
  const after=await deps.cases.getUploadCase(request.caseId,first.userId);
  if(!currentlyAllowed(after,request.caseId,first.userId))throw new PrivateOcrAccessDenied();
  return reading;
}
