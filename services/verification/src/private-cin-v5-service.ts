/**
 * Authenticated, consent-bound, transient Huawei CIN OCR preview.
 * Public v5 weights live on Huawei; no card images/PII in app binary or CI.
 * No image persistence, ownership/identity verification, or silent publishing.
 */
import type {Authenticator,AuthorizedUploadCase,CaseEvidenceRepository} from './evidence-upload.ts';

const ID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const AR=/^[\u0621-\u064a]+(?: [\u0621-\u064a]+){0,3}$/u;
const MAX_IMAGE=6*1024*1024;
export interface CinFields {
  surname:string|null;
  givenName:string|null;
  cinLast4:string|null;
  birthDate:string|null;
  confidencePercent:number;
}
export interface CinV5Worker {
  readFront(bytes:Uint8Array,mime:'image/jpeg'|'image/png'):Promise<CinFields>;
}
export interface CinPreviewRequest {
  bearerToken:string;
  caseId:string;
  bytes:Uint8Array;
  mime:'image/jpeg'|'image/png';
}
export interface CinPreviewDeps {
  auth:Authenticator;
  cases:Pick<CaseEvidenceRepository,'getUploadCase'>;
  worker:CinV5Worker;
}
export class CinPreviewDenied extends Error {
  constructor(){super('PRIVATE_CIN_PREVIEW_DENIED');}
}
function permitted(c:AuthorizedUploadCase|null,caseId:string,userId:string):boolean {
  const consent=c?.consent;
  return !!c&&c.id===caseId&&c.claimantKind==='PERSON'&&c.actorMayUpload&&
    (c.status==='PENDING'||c.status==='NEEDS_INFO')&&
    consent?.personUserId===userId&&
    consent.purpose==='PROPERTY_OWNERSHIP_EVIDENCE'&&
    consent.revokedAt===null&&!!consent.policyVersion&&!!consent.acceptedAt;
}
function correctMagic(data:Uint8Array,mime:string):boolean {
  if(mime==='image/jpeg')
    return data.length>=4&&data[0]===0xff&&data[1]===0xd8&&data[2]===0xff;
  return mime==='image/png'&&data.length>=8 &&
    [137,80,78,71,13,10,26,10].every((b,i)=>data[i]===b);
}
function safeWord(raw:unknown):string|null {
  if(raw===null)return null;
  if(typeof raw!=='string')throw Error('PRIVATE_CIN_MODEL_INVALID');
  const n=raw.trim().replace(/\s+/g,' ');
  if(!n)return null;
  if(n.length>55||!AR.test(n))throw Error('PRIVATE_CIN_MODEL_INVALID');
  return n;
}
function sanitized(fields:CinFields):CinFields {
  if(!fields||typeof fields!=='object'||
    !Number.isInteger(fields.confidencePercent)||
    fields.confidencePercent<0||fields.confidencePercent>100)throw Error('PRIVATE_CIN_MODEL_INVALID');
  const surname=safeWord(fields.surname), givenName=safeWord(fields.givenName);
  if((surname===null)!==(givenName===null))
    return {surname:null,givenName:null,cinLast4:null,birthDate:null,confidencePercent:0};
  if(fields.cinLast4!==null &&
    (typeof fields.cinLast4!=='string'||!/^\d{4}$/.test(fields.cinLast4)))
    throw Error('PRIVATE_CIN_MODEL_INVALID');
  if(fields.birthDate!==null&&
    (typeof fields.birthDate!=='string'||fields.birthDate.length>32||
      !/^[0-9\u0621-\u064a /.-]{8,32}$/u.test(fields.birthDate)))
    throw Error('PRIVATE_CIN_MODEL_INVALID');
  return {surname,givenName,
    cinLast4:fields.cinLast4,birthDate:fields.birthDate,
    confidencePercent:fields.confidencePercent};
}
export async function previewOwnerCin(
  request:CinPreviewRequest,deps:CinPreviewDeps
):Promise<CinFields> {
  if(!request||!ID.test(request.caseId)||
    !(request.bytes instanceof Uint8Array)||
    request.bytes.byteLength<20||request.bytes.byteLength>MAX_IMAGE||
    !['image/jpeg','image/png'].includes(request.mime)||
    !correctMagic(request.bytes,request.mime)||
    !deps?.auth||!deps?.cases||!deps?.worker)
    throw new CinPreviewDenied();
  const first=await deps.auth.authenticate(request.bearerToken);
  if(!first?.userId||first.pilotUnverified===true||!ID.test(first.userId))
    throw new CinPreviewDenied();
  const before=await deps.cases.getUploadCase(request.caseId,first.userId);
  if(!permitted(before,request.caseId,first.userId))throw new CinPreviewDenied();
  const result=await deps.worker.readFront(request.bytes,request.mime);
  // Recheck both identity and consent after server inference, before revealing
  // any candidate name. A revocation mid-scan is always denied.
  const second=await deps.auth.authenticate(request.bearerToken);
  if(!second?.userId||second.userId!==first.userId||
    second.pilotUnverified===true)throw new CinPreviewDenied();
  const after=await deps.cases.getUploadCase(request.caseId,first.userId);
  if(!permitted(after,request.caseId,first.userId))throw new CinPreviewDenied();
  return sanitized(result);
}
