/**
 * Malak Smart Listing V2 — ONE private orchestration contract for ALL document
 * families. Never conflate text identity, legal role and present CPF title.
 *
 * Runs only after case-scoped authenticated private evidence is unsealed and
 * OCR'd. No external paid APIs; no PII in the returned role-status report.
 */
import {readUntrustedOcrDocument,asTextOnlyOcrDocument} from './reader.ts';
import type {IngestedDocument} from './reader.ts';
import type {LandPaperKind} from './document-training-v2.ts';
import type {LandFactKey,PrivateLandFact} from './document-family-fields-v2.ts';
import {comparePrivateCertificateIdentityOcr,
  comparePrivateRoleLabeledNameToIdentity} from './private-document-comparison.ts';
import type {CandidateName,ComparisonAssessment} from './private-document-comparison.ts';
import {compareCertificateIdentityCinText,
  compareExactOwnerRowCinToIdentity} from './private-cin-comparison.ts';
import {locateOwnerTableFromWords} from './owner-table-layout-v2.ts';
import {analyzeOwnerTable} from './owner-table-v2.ts';

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
export interface MatchedPrivateRightsHolder {
  /** Only the holder identified by the captured front ID; no other owners. */
  readonly printedRowOrdinal:number;
  readonly rightsParts:string;
  readonly documentPage:number;
  readonly status:'PRELIMINARY_PERSON_ROW_TEXT_ONLY';
}
export interface UnifiedPrivateDocumentIdentityResult {
  readonly detectedKind:LandPaperKind;
  readonly assessment:UnifiedReviewAssessment;
  readonly roles:readonly RoleTextResult[];
  readonly matchedRightsHolder:MatchedPrivateRightsHolder|null;
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
 ],
 POSSESSION_REPORT:[
   {key:'possessionRequester',role:'POSSESSION_REQUESTER'}
 ],
 FINANCIAL_RECEIPT:[
   {key:'receiptPayer',role:'PAYMENT_PAYER'}
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
/** An independently printed "page x of n" footer; without it, no full table. */
function completePrintedPagination(document:IngestedDocument):number|null {
  if(document.pages.length<1||document.pages.length>8)return null;
  let required:number|null=null;
  const seen=new Set<number>();
  for(const page of document.pages) {
    const observations:{page:number;total:number}[]=[];
    for(const line of page.lines){
      if(line.text.length>300)continue;
      const text=line.text.normalize('NFKC')
        .replace(/[٠-٩]/gu,d=>String(d.charCodeAt(0)-0x660));
      const a=/(?:صفحة)\s*([0-9]{1,2})\s*من\s*([0-9]{1,2})/u.exec(text);
      const b=/\bpage\s*([0-9]{1,2})\s*(?:de|of)\s*([0-9]{1,2})\b/iu.exec(text);
      const found=a??b;
      if(found)observations.push({page:Number(found[1]),total:Number(found[2])});
    }
    if(observations.length===0||observations.some(x=>
        x.page!==page.page||x.total<1||x.total>8||
        x.total!==observations[0]!.total||
        x.total<page.page||required!==null&&required!==x.total))
      return null;
    required=observations[0]!.total;
    seen.add(page.page);
  }
  if(required!==document.pages.length||seen.size!==required||
     Array.from({length:required},(_,i)=>i+1).some(n=>!seen.has(n)))
    return null;
  return required;
}

function initial(kind:LandPaperKind):UnifiedPrivateDocumentIdentityResult {
  return {detectedKind:kind,assessment:'INSUFFICIENT_EVIDENCE',roles:[],matchedRightsHolder:null,
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
  // Keep parsed source word geometry available for the private shared-owner
  // row check BELOW, but never feed that parsed format to a text-only reader.
  const textDoc=asTextOnlyOcrDocument(document);
  const textIdentity=asTextOnlyOcrDocument(frontIdentity);
  const read=readUntrustedOcrDocument(textDoc);
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
  let matchedRightsHolder:MatchedPrivateRightsHolder|null=null;
  if(kind==='OWNERSHIP_CERTIFICATE'){
    const r=comparePrivateCertificateIdentityOcr(textDoc,textIdentity);
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
    // Evaluate only the row corresponding to the identity holder. Other
    // people's unreadable names or parts must not block this PRIVATE preview.
    reasons.add('SHARED_RIGHTS_NEED_ROW_REVIEW');
    let assessment:ComparisonAssessment='INSUFFICIENT_OR_AMBIGUOUS';
    let page:number|null=null;
    const pagesComplete=completePrintedPagination(textDoc)!==null;
    const spatialReady=document.pages.length>0&&document.pages.every(p=>
      p.privateSpatialPage!==undefined&&p.spatialWordsTruncated===false&&
      p.privateSpatialPage.words.length>0);
    if(pagesComplete&&spatialReady){
      const layout=locateOwnerTableFromWords(
        document.pages.map(p=>p.privateSpatialPage!));
      const fatal=new Set(['NO_PAGE_1','HEADER_NOT_FOUND',
        'HEADER_GEOMETRY_AMBIGUOUS','DUPLICATE_OWNER_ORDINAL','CIN_AMBIGUOUS']);
      if(layout.ownerOrdinals.length>0&&
          !layout.findings.some(f=>fatal.has(f))){
        const table=analyzeOwnerTable(layout.cells,{
          declaredPages:document.pages.length,
          receivedPages:document.pages.map(p=>p.page),
          declaredOwnerCount:null,printedTotalParts:null
        });
        const matches:{ordinal:number;page:number;parts:string}[]=[];
        let contradiction=false;
        for(const row of table.rows){
          const n=row.fields.name,c=row.fields.cin,rights=row.fields.rightsParts;
          if(n.status!=='CANDIDATE'||c.status!=='CANDIDATE'||
             rights.status!=='CANDIDATE'||!n.value||!c.value||!rights.value)
            continue;
          const namePage=n.evidence[0]?.page,cinPage=c.evidence[0]?.page;
          const partsPage=rights.evidence[0]?.page;
          if(namePage===undefined||namePage!==cinPage||
             namePage!==partsPage||row.sourcePages.length!==1)continue;
          const actor:CandidateName={value:n.value,page:namePage,
            source:n.evidence[0]!.source,confidence:n.evidence[0]!.confidence};
          const nameCheck=comparePrivateRoleLabeledNameToIdentity(
            actor,textIdentity).assessment;
          const cinCheck=compareExactOwnerRowCinToIdentity(
            c.value,textIdentity);
          if(nameCheck==='PRELIMINARY_TEXT_AGREEMENT'&&
             cinCheck==='SAME_NUMBER_UNVERIFIED')
            matches.push({ordinal:row.ordinal,page:namePage,parts:rights.value});
          else if((nameCheck==='PRELIMINARY_TEXT_AGREEMENT'&&
                   cinCheck==='NUMBER_DIFFERS_NEEDS_REVIEW')||
                  (cinCheck==='SAME_NUMBER_UNVERIFIED'&&
                   nameCheck==='TEXT_DIFFERENCE_NEEDS_REVIEW'))
            contradiction=true;
        }
        if(matches.length===1&&!contradiction){
          const match=matches[0]!;
          assessment='PRELIMINARY_TEXT_AGREEMENT';
          page=match.page;
          matchedRightsHolder={
            printedRowOrdinal:match.ordinal,rightsParts:match.parts,
            documentPage:match.page,
            status:'PRELIMINARY_PERSON_ROW_TEXT_ONLY'
          };
        }else if(matches.length>1||contradiction){
          reasons.add('LOW_CONFIDENCE_OR_CONFLICTING_ROLE');
        }
      }
    }
    roles.push({role:'SHARED_RIGHTS_HOLDER',assessment,documentPage:page});
  } else {
    const targets=ROLE_KEYS[kind];
    if(!targets||!targets.length)reasons.add('UNSUPPORTED_ROLE_EXTRACTION');
    for(const target of targets??[]){
      const fact=read.privateFamilyFields.facts.find(f=>f.key===target.key);
      if(!fact||fact.status!=='CANDIDATE')
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
  return {...base,assessment,roles,matchedRightsHolder,reasons:[...reasons]};
}
