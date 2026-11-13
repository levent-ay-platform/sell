/**
 * Malak Smart Listing V2 — ONE private orchestration contract for ALL document
 * families. Never conflate text identity, legal role and present CPF title.
 *
 * Runs only after case-scoped authenticated private evidence is unsealed and
 * OCR'd. No external paid APIs; no PII in the returned role-status report.
 */
import {readUntrustedOcrDocument} from './reader.ts';
import type {IngestedDocument} from './reader.ts';
import type {LandPaperKind} from './document-training-v2.ts';
import type {LandFactKey,PrivateLandFact} from './document-family-fields-v2.ts';
import {comparePrivateCertificateIdentityOcr,
  comparePrivateRoleLabeledNameToIdentity} from './private-document-comparison.ts';
import type {CandidateName,ComparisonAssessment} from './private-document-comparison.ts';
import {compareCertificateIdentityCinText} from './private-cin-comparison.ts';

export type LegalPartyRole=
 'REGISTERED_HOLDER'|'SHARED_RIGHTS_HOLDER'|'BUYER'|'SELLER'|
 'TRANSFEREE'|'TRANSFEROR'|'SERVICE_APPLICANT'|
 'POSSESSION_REQUESTER'|'PAYMENT_PAYER';
export interface RoleTextResult {
  readonly role:LegalPartyRole;
  readonly assessment:ComparisonAssessment;
  /** Page of this labelled person, not the scanned CIN or their name. */
  readonly documentPage:number|null;
}
export type UnifiedReviewAssessment=
 'ROLE_TEXT_MATCH_ONLY'|'ROLE_TEXT_DIFFERENCE'|
 'ROLE_AMBIGUOUS'|'INSUFFICIENT_EVIDENCE';
export type UnifiedReviewReason=
 'DOCUMENT_TYPE_NOT_CONFIRMED'|'DOCUMENT_TYPE_MISMATCH'|
 'UNSUPPORTED_ROLE_EXTRACTION'|'MULTIPLE_MATCHING_ROLES'|
 'LOW_CONFIDENCE_OR_CONFLICTING_ROLE'|'SHARED_RIGHTS_NEED_ROW_REVIEW'|
 'ROLE_MATCH_NOT_OWNERSHIP'|'CIN_TEXT_DIFFERS'|'CIN_UNREADABLE'|
 'OFFICIAL_REGISTRY_NOT_CHECKED'|'SCAN_QUALITY_NEEDS_REVIEW';
export interface UnifiedPrivateDocumentIdentityResult {
  readonly detectedKind:LandPaperKind;
  readonly assessment:UnifiedReviewAssessment;
  readonly roles:readonly RoleTextResult[];
  readonly reasons:readonly UnifiedReviewReason[];
  readonly identityCapture:'FRONT_IMAGE_ONLY_UNVERIFIED';
  readonly legalOwnershipVerified:false;
  readonly identityVerified:false;
  readonly mayPublish:false;
  readonly requiresHumanReview:true;
}
const ROLE_KEYS:Readonly<Partial<Record<LandPaperKind,
  readonly {key:LandFactKey;role:LegalPartyRole}[]>>>={
 SALE_DEED:[
   {key:'buyer',role:'BUYER'},{key:'seller',role:'SELLER'}
 ],
 TRANSFER_CARD:[
   {key:'transferee',role:'TRANSFEREE'},{key:'transferor',role:'TRANSFEROR'}
 ],
 PROPERTY_SERVICE_RECEIPT:[
   {key:'serviceApplicant',role:'SERVICE_APPLICANT'}
 ]
};
function candidate(f:PrivateLandFact):CandidateName|null {
  if(f.status!=='CANDIDATE'||!f.value||
     f.evidence.length<1||f.evidence.length>8)return null;
  const e=f.evidence[0]!;
  if(f.evidence.some(x=>x.confidence!==null&&x.confidence<0.9))
    return null;
  return {value:f.value,page:e.page,source:e.source,confidence:e.confidence};
}
function initial(kind:LandPaperKind):UnifiedPrivateDocumentIdentityResult {
  return {detectedKind:kind,assessment:'INSUFFICIENT_EVIDENCE',roles:[],
    reasons:['OFFICIAL_REGISTRY_NOT_CHECKED'],
    identityCapture:'FRONT_IMAGE_ONLY_UNVERIFIED',
    legalOwnershipVerified:false,identityVerified:false,
    mayPublish:false,requiresHumanReview:true};
}
/**
 * OCR classification is indicative only. An explicit stored legal kind may
 * reject a clearly DIFFERENT detected kind, but never turn UNKNOWN into a
 * fabricated positive class. OTHER_LEGAL_PROOF remains a generic intake.
 */
export function compareUnifiedPrivateDocumentIdentity(
  document:IngestedDocument,frontIdentity:IngestedDocument,
  storedKinds:readonly string[]=[]
):UnifiedPrivateDocumentIdentityResult {
  if(!Array.isArray(storedKinds)||storedKinds.length>8||
    storedKinds.some(k=>typeof k!=='string'||k.length>40))
    throw new TypeError('INVALID_PRIVATE_DOCUMENT_KINDS');
  const read=readUntrustedOcrDocument(document);
  const kind=read.documentFamilyCandidate.kind;
  const base=initial(kind);
  const reasons=new Set<UnifiedReviewReason>(base.reasons);
  const classification=read.documentFamilyCandidate;
  const recognized=kind!=='UNKNOWN'&&classification.status==='CANDIDATE';
  if(!recognized){
    reasons.add('DOCUMENT_TYPE_NOT_CONFIRMED');
    return {...base,reasons:[...reasons]};
  }
  const specific=storedKinds.filter(k=>k!=='OTHER_LEGAL_PROOF'&&k!=='TITLE_EXTRACT');
  if(specific.some(k=>k!==kind)){
    reasons.add('DOCUMENT_TYPE_MISMATCH');
    return {...base,reasons:[...reasons]};
  }
  const quality=[...document.pages,...frontIdentity.pages]
    .some(p=>(p.qualityWarnings?.length??0)>0);
  if(quality){
    reasons.add('SCAN_QUALITY_NEEDS_REVIEW');
    return {...base,reasons:[...reasons]};
  }
  const roles:RoleTextResult[]=[];
  if(kind==='OWNERSHIP_CERTIFICATE'){
    const r=comparePrivateCertificateIdentityOcr(document,frontIdentity);
    const cin=compareCertificateIdentityCinText(document,frontIdentity);
    if(cin.status==='NUMBER_DIFFERS_NEEDS_REVIEW'){
      reasons.add('CIN_TEXT_DIFFERS');
      return {...base,assessment:'ROLE_TEXT_DIFFERENCE',reasons:[...reasons]};
    }
    if(cin.status==='AMBIGUOUS_OR_LOW_CONFIDENCE')
      reasons.add('CIN_UNREADABLE');
    const assessment=cin.status==='AMBIGUOUS_OR_LOW_CONFIDENCE'?
      'INSUFFICIENT_OR_AMBIGUOUS':r.assessment;
    roles.push({role:'REGISTERED_HOLDER',assessment,
      documentPage:r.certificateName?.page??null});
  } else if(kind==='SHARED_OWNERSHIP_CERTIFICATE'){
    // A row-based individual match requires source-aware owner/CIN
    // association; matching any stray name from the table is unsafe.
    reasons.add('SHARED_RIGHTS_NEED_ROW_REVIEW');
    roles.push({role:'SHARED_RIGHTS_HOLDER',
      assessment:'INSUFFICIENT_OR_AMBIGUOUS',documentPage:null});
  } else {
    const targets=ROLE_KEYS[kind];
    if(!targets||!targets.length)reasons.add('UNSUPPORTED_ROLE_EXTRACTION');
    for(const target of targets??[]){
      const fact=read.privateFamilyFields.facts.find(f=>f.key===target.key);
      if(!fact||fact.status==='LOW_CONFIDENCE'||fact.status==='CONFLICT')
        reasons.add('LOW_CONFIDENCE_OR_CONFLICTING_ROLE');
      const actor=fact?candidate(fact):null;
      const compared=comparePrivateRoleLabeledNameToIdentity(actor,frontIdentity);
      roles.push({role:target.role,assessment:compared.assessment,
        documentPage:actor?.page??null});
    }
  }
  const matches=roles.filter(r=>r.assessment==='PRELIMINARY_TEXT_AGREEMENT');
  if(matches.length>0)reasons.add('ROLE_MATCH_NOT_OWNERSHIP');
  let assessment:UnifiedReviewAssessment='INSUFFICIENT_EVIDENCE';
  if(matches.length>1){assessment='ROLE_AMBIGUOUS';reasons.add('MULTIPLE_MATCHING_ROLES');}
  else if(matches.length===1)assessment='ROLE_TEXT_MATCH_ONLY';
  else if(roles.length>0&&roles.every(r=>
      r.assessment==='TEXT_DIFFERENCE_NEEDS_REVIEW'))
    assessment='ROLE_TEXT_DIFFERENCE';
  return {...base,assessment,roles,reasons:[...reasons]};
}
