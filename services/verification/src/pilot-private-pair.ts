/**
 * Same-login PILOT OCR on JUST the bytes the user uploads in this request.
 * No stored-evidence lookup, owner-role promotion, claims, or cross-user reads.
 * All scans are temporary, malware-screened, and disposed before returning.
 */
import type {Authenticator} from './evidence-upload.ts';
import type {PrivateOcrWorker} from './seller-ocr-service.ts';
import {parseOcrWorkerOutput,readUntrustedOcrDocument} from './reader.ts';
import type {IngestedDocument,IngestedPage} from './reader.ts';
import {compareUnifiedPrivateDocumentIdentity} from './unified-identity-v2.ts';
import type {UnifiedPrivateDocumentIdentityResult} from './unified-identity-v2.ts';

const TOKEN=/^[A-Za-z0-9._~+/-]{20,4096}$/;
const MAX_DOCS=8,MAX_TOTAL=12*1024*1024,MAX_ID=6*1024*1024;
type Mime='application/pdf'|'image/jpeg'|'image/png'|'image/tiff';
type Data={bytes:Uint8Array;mime:Mime};
export interface PilotPairRequest {
 bearerToken:string;
 documents:readonly Data[];
 front:Data;
}
export interface PilotPairDependencies {
 auth:Authenticator;
 scanner:{scan(bytes:Uint8Array,mime:Mime):Promise<'CLEAN'|'INFECTED'|'UNKNOWN'>};
 worker:PrivateOcrWorker;
}
export type PilotPairPreview = {
 readonly detectedKind:string;
 readonly assessment:UnifiedPrivateDocumentIdentityResult['assessment'];
 readonly matchedRole:string|null;
 readonly documentCinStatus:string|null;
 readonly matchedRightsHolder:UnifiedPrivateDocumentIdentityResult['matchedRightsHolder'];
 readonly propertyFacts:readonly {key:string;value:string;page:number}[];
 readonly certificatePages:number;
 readonly identityPages:1;
 readonly ownershipVerified:false;
 readonly identityVerified:false;
 readonly mayPublish:false;
 readonly requiresHumanReview:true;
};
export class PilotPairDenied extends Error{
 constructor(){super('PILOT_PAIR_DENIED');}
}
export class PilotPairUnavailable extends Error{
 constructor(){super('PILOT_PAIR_UNAVAILABLE');}
}
/** Sniff bytes rather than trusting the caller's content type. */
export function sniffPilotMime(bytes:Uint8Array):Mime|null {
 if(bytes.length<20)return null;
 if(bytes[0]===0x25&&bytes[1]===0x50&&bytes[2]===0x44&&bytes[3]===0x46&&bytes[4]===0x2d)
  return 'application/pdf';
 if(bytes[0]===0xff&&bytes[1]===0xd8&&bytes[2]===0xff)
  return 'image/jpeg';
 if([137,80,78,71,13,10,26,10].every((b,i)=>bytes[i]===b))
  return 'image/png';
 if((bytes[0]===0x49&&bytes[1]===0x49&&bytes[2]===0x2a&&bytes[3]===0x00)||
    (bytes[0]===0x4d&&bytes[1]===0x4d&&bytes[2]===0x00&&bytes[3]===0x2a))
  return 'image/tiff';
 return null;
}
function join(docs:readonly IngestedDocument[]):IngestedDocument {
 const pages:IngestedPage[]=[];
 for(const doc of docs)for(const page of doc.pages){
  const number=pages.length+1;
  if(number>8)throw new PilotPairDenied();
  pages.push({...page,page:number,
   lines:page.lines.map(line=>({...line,page:number})),
   ...(page.privateSpatialPage?{privateSpatialPage:{
    ...page.privateSpatialPage,page:number,
    words:page.privateSpatialPage.words.map(w=>({...w,page:number}))
   }}:{})
  });
 }
 return {format:'pdf',mayApproveOwnership:false,pages};
}
const FACT_KEYS=new Set([
 'registerReference','parentRegisterReference','registeredAreaM2',
 'soldAreaM2','possessedAreaM2','transferredAreaM2',
 'registeredPlotNumber','soldPlotNumber','possessedPlotNumber',
 'salePriceTnd','transferReference','serviceRequestNumber','serviceRequestDate',
 'rightsDenominator'
]);
export async function previewPilotPrivatePair(
 req:PilotPairRequest,deps:PilotPairDependencies
):Promise<PilotPairPreview> {
 if(!req||typeof req.bearerToken!=='string'||!TOKEN.test(req.bearerToken)||
    !Array.isArray(req.documents)||req.documents.length<1||
    req.documents.length>MAX_DOCS||!req.front||
    !deps?.auth||!deps.scanner||!deps.worker)throw new PilotPairDenied();
 const files=[...req.documents,req.front];
 let total=0;
 for(const f of files){
  if(!(f.bytes instanceof Uint8Array)||f.bytes.length<20||
     f.mime!==sniffPilotMime(f.bytes))throw new PilotPairDenied();
  total+=f.bytes.byteLength;
  if(total>MAX_TOTAL)throw new PilotPairDenied();
 }
 if(req.front.bytes.byteLength>MAX_ID||
    !['image/png','image/jpeg'].includes(req.front.mime)||
    (req.documents.length>1&&req.documents.some(f=>f.mime==='application/pdf')))
  throw new PilotPairDenied();
 let first;
 try{first=await deps.auth.authenticate(req.bearerToken);}
 catch{throw new PilotPairDenied();}
 // No elevation: only the existing server-issued, device-scoped pilot bearer.
 if(!first?.pilotUnverified||!first.userId)throw new PilotPairDenied();
 const owner=first.userId;
 try{
  // Scanner and worker injected by the real Malak service; never a fake result.
  for(const f of files)
   if(await deps.scanner.scan(f.bytes,f.mime)!=='CLEAN')
    throw new PilotPairDenied();
  const docs:IngestedDocument[]=[];
  for(const f of req.documents){
   const doc=parseOcrWorkerOutput(await deps.worker.extract(f.bytes,f.mime));
   if(f.mime==='application/pdf'?doc.format!=='pdf':
     doc.format!=='image'||doc.pages.length!==1)throw new PilotPairDenied();
   docs.push(doc);
  }
  const front=parseOcrWorkerOutput(
    await deps.worker.extract(req.front.bytes,req.front.mime));
  if(front.format!=='image'||front.pages.length!==1)throw new PilotPairDenied();
  const document=join(docs);
  const comparison=compareUnifiedPrivateDocumentIdentity(document,front);
  const read=readUntrustedOcrDocument(document);
  const matches=comparison.roles.filter(r=>
   r.assessment==='PRELIMINARY_TEXT_AGREEMENT');
  const reliable=comparison.assessment==='ROLE_TEXT_MATCH_ONLY'||
                 comparison.assessment==='NAME_ONLY_REVIEW';
  const selected=reliable&&matches.length===1?matches[0]:null;
  const identityNow=await deps.auth.authenticate(req.bearerToken);
  if(identityNow?.userId!==owner||identityNow?.pilotUnverified!==true)
   throw new PilotPairDenied();
  // No other party's names, ID numbers, source OCR or document bytes leave.
  const propertyFacts=read.privateFamilyFields.facts.filter(f=>
     FACT_KEYS.has(f.key)&&f.status==='CANDIDATE'&&
     f.value!==null&&f.evidence.length>0)
   .map(f=>({key:f.key,value:f.value!,page:f.evidence[0]!.page}));
  return {
   detectedKind:comparison.detectedKind,
   assessment:comparison.assessment,
   matchedRole:selected?.role??null,
   documentCinStatus:selected?.documentCinStatus??null,
   matchedRightsHolder:selected?comparison.matchedRightsHolder:null,
   propertyFacts,
   certificatePages:document.pages.length,
   identityPages:1,
   ownershipVerified:false,identityVerified:false,mayPublish:false,
   requiresHumanReview:true
  };
 }catch(err){
  if(err instanceof PilotPairDenied)throw err;
  throw new PilotPairUnavailable();
 }
}
