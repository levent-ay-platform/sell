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
  /** Does not reveal the private CIN or the raw actor's name. */
  readonly documentCinStatus:
    'NOT_PRINTED'|'SAME_NUMBER_UNVERIFIED'|
    'NUMBER_DIFFERS_NEEDS_REVIEW'|'AMBIGUOUS_OR_LOW_CONFIDENCE'|
    'MASKED_IN_SOURCE';
  /** Page of this labelled person, not the scanned CIN or their name. */
  readonly documentPage:number|null;
}
export type UnifiedReviewAssessment=
 'ROLE_TEXT_MATCH_ONLY'|'NAME_ONLY_REVIEW'|'ROLE_TEXT_DIFFERENCE'|
 'ROLE_AMBIGUOUS'|'CONFLICT_REVIEW'|'NO_PERSON_IDENTIFIED'|
 'INSUFFICIENT_EVIDENCE';
export type UnifiedReviewReason=
 'DOCUMENT_TYPE_NOT_CONFIRMED'|'DOCUMENT_TYPE_MISMATCH'|
 'UNSUPPORTED_ROLE_EXTRACTION'|'MULTIPLE_MATCHING_ROLES'|
 'LOW_CONFIDENCE_OR_CONFLICTING_ROLE'|'SHARED_RIGHTS_NEED_ROW_REVIEW'|
 'ROLE_MATCH_NOT_OWNERSHIP'|'CIN_TEXT_DIFFERS'|'CIN_UNREADABLE'|
 'OFFICIAL_REGISTRY_NOT_CHECKED'|'SCAN_QUALITY_NEEDS_REVIEW'|
 'DOCUMENT_CIN_NOT_PRINTED'|'DOCUMENT_CIN_UNREADABLE'|
 'ROLE_CIN_CONFLICT'|'DOCUMENT_CIN_MASKED'|'TARGET_RIGHTS_NOT_READABLE'|
 'NO_ROLE_LABELED_PERSON';
export interface MatchedPrivateRightsHolder {
  /** Only the holder identified by the captured front ID; no other owners. */
  readonly printedRowOrdinal:number;
  readonly rightsParts:string|null;
  readonly documentPage:number;
  readonly status:'PRELIMINARY_PERSON_ROW_TEXT_ONLY'|'NAME_ONLY_REVIEW';
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
  readonly {key:LandFactKey;cinKey:LandFactKey;role:LegalPartyRole}[]>>>={
 SALE_DEED:[
   {key:'buyer',cinKey:'buyerCin',role:'BUYER'},
   {key:'seller',cinKey:'sellerCin',role:'SELLER'}
 ],
 TRANSFER_CARD:[
   {key:'transferee',cinKey:'transfereeCin',role:'TRANSFEREE'},
   {key:'transferor',cinKey:'transferorCin',role:'TRANSFEROR'}
 ],
 PROPERTY_SERVICE_RECEIPT:[
   {key:'serviceApplicant',cinKey:'serviceApplicantCin',role:'SERVICE_APPLICANT'}
 ],
 POSSESSION_REPORT:[
   {key:'possessionRequester',cinKey:'possessionRequesterCin',role:'POSSESSION_REQUESTER'}
 ],
 FINANCIAL_RECEIPT:[
   {key:'receiptPayer',cinKey:'receiptPayerCin',role:'PAYMENT_PAYER'}
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
    const cin=compareCertificateIdentityCinText(textDoc,textIdentity);
    if(cin.status==='NUMBER_DIFFERS_NEEDS_REVIEW'){
      reasons.add('ROLE_CIN_CONFLICT');
      // Retain the original certificate-specific reason for older reviewers.
      reasons.add('CIN_TEXT_DIFFERS');
    }
    if(cin.status==='AMBIGUOUS_OR_LOW_CONFIDENCE')
      reasons.add('DOCUMENT_CIN_UNREADABLE');
    if(cin.status==='NOT_COMPARABLE')
      reasons.add('DOCUMENT_CIN_NOT_PRINTED');
    roles.push({role:'REGISTERED_HOLDER',assessment:r.assessment,
      documentCinStatus:cin.status==='NOT_COMPARABLE'?
        'NOT_PRINTED':cin.status,
      documentPage:r.certificateName?.page??null});
  } else if(kind==='SHARED_OWNERSHIP_CERTIFICATE'){
    // Only the identity holder's OWN row may leave this private comparison.
    // Other unreadable owners do not block it; an unrelated 8-digit number
    // must never be compared as a CIN for the selected person.
    reasons.add('SHARED_RIGHTS_NEED_ROW_REVIEW');
    let selected:RoleTextResult={
      role:'SHARED_RIGHTS_HOLDER',
      assessment:'INSUFFICIENT_OR_AMBIGUOUS',
      documentCinStatus:'NOT_PRINTED',documentPage:null
    };
    const pagesComplete=completePrintedPagination(textDoc)!==null;
    const spatialReady=document.pages.length>0&&document.pages.every(p=>
      p.privateSpatialPage!==undefined&&p.spatialWordsTruncated===false&&
      p.privateSpatialPage.words.length>0);
    if(pagesComplete&&spatialReady){
      const layout=locateOwnerTableFromWords(
        document.pages.map(p=>p.privateSpatialPage!));
      const fatal=new Set(['NO_PAGE_1','HEADER_NOT_FOUND',
        'HEADER_GEOMETRY_AMBIGUOUS','DUPLICATE_OWNER_ORDINAL',
        'CIN_AMBIGUOUS']);
      if(layout.ownerOrdinals.length>0&&
          !layout.findings.some(f=>fatal.has(f))){
        const table=analyzeOwnerTable(layout.cells,{
          declaredPages:document.pages.length,
          receivedPages:document.pages.map(p=>p.page),
          declaredOwnerCount:null,printedTotalParts:null
        });
        const matches:{
          ordinal:number;page:number;parts:string|null;
          status:'NAME_ONLY_REVIEW'|'PRELIMINARY_PERSON_ROW_TEXT_ONLY';
          cinStatus:RoleTextResult['documentCinStatus']
        }[]=[];
        let inconsistent=false;
        for(const row of table.rows){
          const name=row.fields.name,cin=row.fields.cin,rights=row.fields.rightsParts;
          if(name.status!=='CANDIDATE'||!name.value)continue;
          const n=name.evidence[0];
          if(!n||row.sourcePages.length!==1||row.sourcePages[0]!==n.page)
            continue;
          const actor:CandidateName={value:name.value,page:n.page,
            source:n.source,confidence:n.confidence};
          const nameResult=comparePrivateRoleLabeledNameToIdentity(
            actor,textIdentity).assessment;
          if(nameResult==='INSUFFICIENT_OR_AMBIGUOUS')continue;
          let cinStatus:RoleTextResult['documentCinStatus']='NOT_PRINTED';
          if(cin.status==='MASKED'){
            cinStatus='MASKED_IN_SOURCE';
          }else if(cin.status==='NOT_OBSERVED'){
            cinStatus='NOT_PRINTED';
          }else if(cin.status==='CANDIDATE'&&cin.value&&
                   cin.evidence[0]?.page===n.page){
            const checked=compareExactOwnerRowCinToIdentity(
              cin.value,textIdentity);
            cinStatus=checked==='NOT_COMPARABLE'?
              'AMBIGUOUS_OR_LOW_CONFIDENCE':checked;
          }else cinStatus='AMBIGUOUS_OR_LOW_CONFIDENCE';
          if(nameResult==='TEXT_DIFFERENCE_NEEDS_REVIEW'){
            if(cinStatus==='SAME_NUMBER_UNVERIFIED')inconsistent=true;
            continue;
          }
          if(cinStatus==='NUMBER_DIFFERS_NEEDS_REVIEW'){
            inconsistent=true;
            reasons.add('ROLE_CIN_CONFLICT');
            continue;
          }
          if(cinStatus==='AMBIGUOUS_OR_LOW_CONFIDENCE'){
            reasons.add('DOCUMENT_CIN_UNREADABLE');
            continue;
          }
          if(cinStatus==='MASKED_IN_SOURCE')reasons.add('DOCUMENT_CIN_MASKED');
          if(rights.status!=='CANDIDATE'||!rights.value||
              rights.evidence[0]?.page!==n.page){
            reasons.add('TARGET_RIGHTS_NOT_READABLE');
          }
          matches.push({
            ordinal:row.ordinal,page:n.page,
            parts:rights.status==='CANDIDATE'&&rights.evidence[0]?.page===n.page?
              rights.value:null,
            status:cinStatus==='NOT_PRINTED'||cinStatus==='MASKED_IN_SOURCE'?
              'NAME_ONLY_REVIEW':
              'PRELIMINARY_PERSON_ROW_TEXT_ONLY',cinStatus
          });
        }
        // Duplicate name/identity matches never choose the first row.
        if(matches.length===1&&!inconsistent){
          const match=matches[0]!;
          selected={
            role:'SHARED_RIGHTS_HOLDER',
            assessment:'PRELIMINARY_TEXT_AGREEMENT',
            documentCinStatus:match.cinStatus,documentPage:match.page
          };
          matchedRightsHolder={
            printedRowOrdinal:match.ordinal,rightsParts:match.parts,
            documentPage:match.page,status:match.status
          };
          if(match.cinStatus==='NOT_PRINTED')
            reasons.add('DOCUMENT_CIN_NOT_PRINTED');
        }else if(matches.length>1||inconsistent){
          reasons.add('LOW_CONFIDENCE_OR_CONFLICTING_ROLE');
          if(inconsistent)reasons.add('ROLE_CIN_CONFLICT');
        }
      }
    }
    roles.push(selected);
  } else {
    const targets=ROLE_KEYS[kind];
    if(!targets||!targets.length)reasons.add('UNSUPPORTED_ROLE_EXTRACTION');
    for(const target of targets??[]){
      const fact=read.privateFamilyFields.facts.find(f=>f.key===target.key);
      const cinFact=read.privateFamilyFields.facts.find(f=>f.key===target.cinKey);
      const actor=fact?candidate(fact):null;
      if(fact&&fact.status!=='CANDIDATE')
        reasons.add('LOW_CONFIDENCE_OR_CONFLICTING_ROLE');
      const compared=comparePrivateRoleLabeledNameToIdentity(actor,textIdentity);
      let cinStatus:RoleTextResult['documentCinStatus']='NOT_PRINTED';
      if(cinFact&&cinFact.status!=='ABSENT'){
        if(cinFact.status==='MASKED')
          cinStatus='MASKED_IN_SOURCE';
        else if(cinFact.status!=='CANDIDATE'||!cinFact.value||
            !actor||cinFact.evidence[0]?.page!==actor.page)
          cinStatus='AMBIGUOUS_OR_LOW_CONFIDENCE';
        else {
          const r=compareExactOwnerRowCinToIdentity(cinFact.value,textIdentity);
          cinStatus=r==='NOT_COMPARABLE'?'AMBIGUOUS_OR_LOW_CONFIDENCE':r;
        }
      }
      if(compared.assessment==='PRELIMINARY_TEXT_AGREEMENT'){
        if(cinStatus==='NOT_PRINTED')
          reasons.add('DOCUMENT_CIN_NOT_PRINTED');
        if(cinStatus==='MASKED_IN_SOURCE')
          reasons.add('DOCUMENT_CIN_MASKED');
        if(cinStatus==='AMBIGUOUS_OR_LOW_CONFIDENCE')
          reasons.add('DOCUMENT_CIN_UNREADABLE');
        if(cinStatus==='NUMBER_DIFFERS_NEEDS_REVIEW')
          reasons.add('ROLE_CIN_CONFLICT');
      }else if(compared.assessment==='TEXT_DIFFERENCE_NEEDS_REVIEW'&&
               cinStatus==='SAME_NUMBER_UNVERIFIED'){
        reasons.add('ROLE_CIN_CONFLICT');
      }
      roles.push({role:target.role,assessment:compared.assessment,
        documentCinStatus:cinStatus,documentPage:actor?.page??null});
    }
  }
  const matches=roles.filter(r=>r.assessment==='PRELIMINARY_TEXT_AGREEMENT');
  let assessment:UnifiedReviewAssessment='INSUFFICIENT_EVIDENCE';
  if(reasons.has('ROLE_CIN_CONFLICT')){
    assessment='CONFLICT_REVIEW';
    matchedRightsHolder=null;
  }else if(matches.length>1){
    assessment='ROLE_AMBIGUOUS';
    reasons.add('MULTIPLE_MATCHING_ROLES');
    matchedRightsHolder=null;
  }else if(matches.length===1){
    const cin=matches[0]!.documentCinStatus;
    if(cin==='SAME_NUMBER_UNVERIFIED'){
      assessment='ROLE_TEXT_MATCH_ONLY';
    }else if(cin==='NOT_PRINTED'||cin==='MASKED_IN_SOURCE'){
      assessment='NAME_ONLY_REVIEW';
      if(cin==='NOT_PRINTED')reasons.add('DOCUMENT_CIN_NOT_PRINTED');
      else reasons.add('DOCUMENT_CIN_MASKED');
    }else {
      assessment='INSUFFICIENT_EVIDENCE';
      matchedRightsHolder=null;
    }
    reasons.add('ROLE_MATCH_NOT_OWNERSHIP');
  }else if(roles.length>0&&roles.every(r=>
      r.assessment==='TEXT_DIFFERENCE_NEEDS_REVIEW')){
    assessment='ROLE_TEXT_DIFFERENCE';
  }else if(kind!=='SHARED_OWNERSHIP_CERTIFICATE'&&
           kind!=='OWNERSHIP_CERTIFICATE'&&
           (ROLE_KEYS[kind]??[]).length>0&&
           (ROLE_KEYS[kind]??[]).every(t=>{
             const f=read.privateFamilyFields.facts.find(x=>x.key===t.key);
             return !f||f.status==='ABSENT';
           })){
    // Only say "no named person" when this type truly has NO role field.
    // A masked, low-confidence, conflicting or incomplete role is review,
    // never evidence that no party was printed in the document.
    assessment='NO_PERSON_IDENTIFIED';
    reasons.add('NO_ROLE_LABELED_PERSON');
  }
  return {...base,assessment,roles,matchedRightsHolder,reasons:[...reasons]};
}
