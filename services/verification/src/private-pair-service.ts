/**
 * Server-only, pre-deployment certificate/CIN comparison boundary.
 * Loads only already-scanned, AES-GCM sealed immutable evidence for one
 * authenticated person's pending case. No HTTP listener or public route.
 * Neither OCR text agreement nor the number of identity images proves identity,
 * title ownership, document authenticity or completeness.
 */
import {createDecipheriv,createHash} from 'node:crypto';
import {parseOcrWorkerOutput} from './reader.ts';
import type {IngestedDocument,IngestedPage} from './reader.ts';
import {comparePrivateCertificateIdentityOcr} from './private-document-comparison.ts';
import {compareCertificateIdentityCinText} from './private-cin-comparison.ts';
import type {PrivateCinComparison} from './private-cin-comparison.ts';
import type {PrivateDocumentComparison} from './private-document-comparison.ts';
import type {Authenticator,AuthorizedUploadCase,EvidenceRow,CaseEvidenceRepository} from './evidence-upload.ts';
import type {PrivateCiphertextReader} from './local-private-store.ts';
import type {PrivateOcrWorker} from './seller-ocr-service.ts';

const ID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SHA=/^[0-9a-f]{64}$/i;
const MAGIC=Buffer.from('MALAK-EV1\0','ascii');
const MAX_BYTES=12*1024*1024;
type Kind='OWNERSHIP_CERTIFICATE'|'IDENTITY';
export class PrivatePairAccessDenied extends Error {
  constructor(){super('PRIVATE_PAIR_ACCESS_DENIED');}
}
export class PrivatePairUnavailable extends Error {
  constructor(){super('PRIVATE_PAIR_UNAVAILABLE');}
}
export interface PrivatePairRequest {
  readonly bearerToken:string;
  readonly caseId:string;
  readonly certificateEvidenceIds:readonly string[];
  readonly identityEvidenceIds:readonly string[];
}
export interface PrivatePairDependencies {
  auth:Authenticator;
  cases:Pick<CaseEvidenceRepository,'getUploadCase'> & {
    /** Scoped SQL lookup: never accept arbitrary object keys from clients. */
    getPrivateEvidence(caseId:string,userId:string,evidenceId:string):Promise<EvidenceRow|null>;
  };
  objects:PrivateCiphertextReader;
  keys:{unwrapDataKey(key:{caseId:string;evidenceId:string;wrappedKey:string;keyVersion:string}):Promise<Uint8Array>};
  worker:PrivateOcrWorker;
}
export interface PrivatePairReport {
  readonly access:'AUTHORIZED_CASE_OWNER_ONLY';
  readonly identityPagesReceived:number;
  readonly certificatePagesReceived:number;
  readonly identityCapture:'FRONT_IMAGE_ONLY_UNVERIFIED';
  readonly comparison:PrivateDocumentComparison;
  /** Number values are NEVER returned; document-level review remains mandatory. */
  readonly cinComparison:PrivateCinComparison;
  readonly ownershipVerified:false;
  readonly identityVerified:false;
  readonly mayPublish:false;
  readonly requiresHumanReview:true;
}
function allowed(c:AuthorizedUploadCase|null,caseId:string,userId:string):boolean {
  const consent=c?.consent;
  return c?.id===caseId&&c.claimantKind==='PERSON'&&c.actorMayUpload&&
    (c.status==='PENDING'||c.status==='NEEDS_INFO')&&
    consent?.personUserId===userId&&consent.purpose==='PROPERTY_OWNERSHIP_EVIDENCE'&&
    consent.revokedAt===null&&!!consent.acceptedAt&&!!consent.policyVersion;
}
function checkIds(req:PrivatePairRequest):void {
  if(!req||typeof req.bearerToken!=='string'||req.bearerToken.length<20||
    !ID.test(req.caseId)||!Array.isArray(req.certificateEvidenceIds)||
    !Array.isArray(req.identityEvidenceIds)||req.certificateEvidenceIds.length<1||
    req.certificateEvidenceIds.length>8||req.identityEvidenceIds.length<1||
    req.identityEvidenceIds.length!==1)throw new PrivatePairAccessDenied();
  const all=[...req.certificateEvidenceIds,...req.identityEvidenceIds];
  if(all.some(id=>typeof id!=='string'||!ID.test(id))||new Set(all).size!==all.length)
    throw new PrivatePairAccessDenied();
}
function rowAllowed(row:EvidenceRow|null,kind:Kind,req:PrivatePairRequest,userId:string,evidenceId:string):row is EvidenceRow {
  return !!row&&row.evidenceId===evidenceId&&row.caseId===req.caseId&&
    row.uploadedBy===userId&&row.kind===kind&&SHA.test(row.digest)&&
    Number.isSafeInteger(row.fileSize)&&row.fileSize>=20&&row.fileSize<=MAX_BYTES&&
    (kind==='IDENTITY'
      ?row.pageNumber===1&&['image/jpeg','image/png'].includes(row.mime)
      :['application/pdf','image/jpeg','image/png','image/tiff'].includes(row.mime));
}
function decryptEvidence(sealed:Uint8Array,key:Uint8Array,row:EvidenceRow):Buffer {
  if(!(sealed instanceof Uint8Array)||!(key instanceof Uint8Array)||key.byteLength!==32||
    sealed.byteLength!==row.fileSize+MAGIC.length+28||
    !Buffer.from(sealed.subarray(0,MAGIC.length)).equals(MAGIC))
    throw new PrivatePairUnavailable();
  const iv=sealed.subarray(MAGIC.length,MAGIC.length+12);
  const tag=sealed.subarray(MAGIC.length+12,MAGIC.length+28);
  const payload=sealed.subarray(MAGIC.length+28);
  const decipher=createDecipheriv('aes-256-gcm',key,iv);
  decipher.setAAD(Buffer.from('malak-evidence-v1:'+row.caseId+':'+row.evidenceId));
  decipher.setAuthTag(tag);
  const clear=Buffer.concat([decipher.update(payload),decipher.final()]);
  if(clear.length!==row.fileSize||
    createHash('sha256').update(clear).digest('hex').toLowerCase()!==row.digest.toLowerCase()){
    clear.fill(0);throw new PrivatePairUnavailable();
  }
  return clear;
}
async function readOne(row:EvidenceRow,deps:PrivatePairDependencies):Promise<IngestedDocument> {
  let sealed:Uint8Array|undefined,key:Uint8Array|undefined,clear:Buffer|undefined;
  try {
    sealed=await deps.objects.readPrivate(row.objectKey);
    key=await deps.keys.unwrapDataKey({caseId:row.caseId,evidenceId:row.evidenceId,
      wrappedKey:row.wrappedDataKey,keyVersion:row.keyVersion});
    clear=decryptEvidence(sealed,key,row);
    const raw=await deps.worker.extract(clear,row.mime);
    const read=parseOcrWorkerOutput(raw);
    // A single still image can only contribute one physical page. Reject
    // OCR metadata that claims PDF or multiple pages for an image receipt.
    if(row.mime==='application/pdf' ? read.format!=='pdf' :
       (read.format!=='image'||read.pages.length!==1))
      throw new PrivatePairUnavailable();
    return read;
  } catch {
    // Never forward storage, OCR, Vault or personal information in exceptions.
    throw new PrivatePairUnavailable();
  } finally {
    clear?.fill(0);key?.fill(0);sealed?.fill(0);
  }
}
function joinPages(docs:readonly IngestedDocument[]):IngestedDocument {
  const pages:IngestedPage[]=[];
  for(const doc of docs)for(const page of doc.pages){
    const number=pages.length+1;
    if(number>8)throw new PrivatePairUnavailable();
    // The private pair comparator reparses its input as a *text-only*
    // OCR document. Never forward optional word geometry to that boundary:
    // it has a different schema, and it is not needed for name/CIN matching.
    // Keep page quality and per-line evidence, but strip layout-only fields.
    pages.push({page:number,method:page.method,
      lines:page.lines.map(line=>({...line,page:number})),
      ...(page.qualityWarnings!==undefined?
        {qualityWarnings:page.qualityWarnings}:{})});
  }
  return {format:'pdf',mayApproveOwnership:false,pages};
}
/** Auth/session/consent rechecked AFTER extraction; no PII released on revocation. */
export async function analyzeAuthorizedPrivateEvidencePair(
  req:PrivatePairRequest,deps:PrivatePairDependencies
):Promise<PrivatePairReport> {
  checkIds(req);
  let userId:string;
  try {
    const id=await deps.auth.authenticate(req.bearerToken);
    if(!id?.userId||id.pilotUnverified===true||!ID.test(id.userId))
      throw new PrivatePairAccessDenied();
    userId=id.userId;
    const c=await deps.cases.getUploadCase(req.caseId,userId);
    if(!allowed(c,req.caseId,userId))throw new PrivatePairAccessDenied();
  }catch(e){
    if(e instanceof PrivatePairAccessDenied)throw e;
    throw new PrivatePairUnavailable();
  }
  const fetchRows=async(ids:readonly string[],kind:Kind):Promise<EvidenceRow[]>=>{
    const rows:EvidenceRow[]=[];
    for(const evidenceId of ids){
      let row:EvidenceRow|null;
      try {row=await deps.cases.getPrivateEvidence(req.caseId,userId,evidenceId);}
      catch {throw new PrivatePairUnavailable();}
      if(!rowAllowed(row,kind,req,userId,evidenceId))throw new PrivatePairAccessDenied();
      rows.push(row);
    }
    return rows;
  };
  const certRows=await fetchRows(req.certificateEvidenceIds,'OWNERSHIP_CERTIFICATE');
  const idRows=await fetchRows(req.identityEvidenceIds,'IDENTITY');
  // Certificates may be multi-page; the CIN is exactly one front image
  // numbered page 1. Never silently renumber evidence before OCR.
  const ordered=(rows:readonly EvidenceRow[])=>rows.every((row,i)=>
    row.mime==='application/pdf'
      ?rows.length===1&&row.pageNumber===null
      :row.pageNumber===i+1);
  if(!ordered(certRows)||!ordered(idRows))throw new PrivatePairAccessDenied();
  // A pair can contain several pages/files. Bound the entire synchronous
  // extraction sequence, not only each Python subprocess (max 60 seconds).
  // The current OCR job is always awaited/killed by its worker before exiting.
  const startedAt=Date.now(),maximumElapsedMs=180000;
  const checkBudget=()=>{if(Date.now()-startedAt>=maximumElapsedMs)throw new PrivatePairUnavailable();};
  const certDocs:IngestedDocument[]=[];
  for(const row of certRows){checkBudget();certDocs.push(await readOne(row,deps));checkBudget();}
  const identityDocs:IngestedDocument[]=[];
  for(const row of idRows){checkBudget();identityDocs.push(await readOne(row,deps));checkBudget();}
  const cert=joinPages(certDocs),identity=joinPages(identityDocs);
  const cinComparison=compareCertificateIdentityCinText(cert,identity);
  let comparison:PrivateDocumentComparison;
  try {comparison=comparePrivateCertificateIdentityOcr(cert,identity);}
  catch {throw new PrivatePairUnavailable();}
  if(cinComparison.status==='NUMBER_DIFFERS_NEEDS_REVIEW'){
    comparison={...comparison,assessment:'TEXT_DIFFERENCE_NEEDS_REVIEW',
      reasons:[...comparison.reasons,'CIN_NUMBER_TEXT_DIFFERS']};
  }else if(cinComparison.status==='AMBIGUOUS_OR_LOW_CONFIDENCE' &&
           comparison.assessment==='PRELIMINARY_TEXT_AGREEMENT'){
    comparison={...comparison,assessment:'INSUFFICIENT_OR_AMBIGUOUS',
      reasons:[...comparison.reasons,'CIN_NUMBER_UNCERTAIN']};
  }
  try {
    const [identityNow,caseNow]=await Promise.all([
      deps.auth.authenticate(req.bearerToken),deps.cases.getUploadCase(req.caseId,userId)
    ]);
    if(identityNow?.pilotUnverified===true||identityNow?.userId!==userId||
       !allowed(caseNow,req.caseId,userId))
      throw new PrivatePairAccessDenied();
  }catch(e){
    if(e instanceof PrivatePairAccessDenied)throw e;
    throw new PrivatePairUnavailable();
  }
  return {access:'AUTHORIZED_CASE_OWNER_ONLY',certificatePagesReceived:cert.pages.length,
    identityPagesReceived:identity.pages.length,
    identityCapture:'FRONT_IMAGE_ONLY_UNVERIFIED',comparison,cinComparison,
    ownershipVerified:false,identityVerified:false,mayPublish:false,requiresHumanReview:true};
}
