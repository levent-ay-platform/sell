import type {ReadDocumentResult} from './reader.ts';
import type {FieldName,ExtractedField} from './smart-fields.ts';

/**
 * Safe, metadata-only next-step planner for individual CPF submissions.
 * OCR is evidence collection, never authoritative confirmation of title.
 * This component never includes owner names, title numbers, QR bytes or image text
 * in status messages, logs or public responses.
 */
export type ReadinessReason =
  | 'DOCUMENT_TYPE_UNRECOGNIZED'
  | 'PAGE_NUMBER_UNREADABLE'
  | 'PAGE_NUMBER_CONTRADICTORY'
  | 'MISSING_DOCUMENT_PAGES'
  | 'TITLE_REFERENCE_MISSING_OR_UNCERTAIN'
  | 'AREA_MISSING_OR_UNCERTAIN'
  | 'SHARE_UNCONFIRMED'
  | 'HISTORIC_DATE_IS_NOT_CERTIFICATE_ISSUE_DATE'
  | 'PERSON_IDENTITY_NOT_YET_CORROBORATED'
  | 'CURRENT_CPF_STATUS_NOT_YET_CORROBORATED'
  | 'IMAGE_QUALITY_REVIEW_REQUIRED';
export type UserFollowUp =
  | 'SEND_FULL_DOCUMENT'
  | 'RETAKE_PAGE_FOOTER'
  | 'RETAKE_REGISTRY_REFERENCE'
  | 'RETAKE_AREA'
  | 'PROVIDE_RECENT_CPF_PROOF'
  | 'AUTHORIZED_REVIEW_REQUIRED'
  | 'RETAKE_LOW_QUALITY_PAGE';
export interface ReadingReadiness {
  /** Never use OCR quality as document authenticity or legal rights verification. */
  ownershipVerified: false;
  mayPublish: false;
  requiresIndependentReview: true;
  needsAdditionalImages: boolean;
  reasons: readonly ReadinessReason[];
  followUps: readonly UserFollowUp[];
  /** Only flags, never extracted personal values. */
  criticalFields: Readonly<Record<'registryReference'|'areaSquareMetres'|'ownershipShareUnits',ExtractedField['status']>>;
}

const usable=(field:ExtractedField):boolean=>field.status==='CANDIDATE' && field.value!==null;
const potentiallyLegalShare=(field:ExtractedField):boolean=>field.value!==null && field.status==='CANDIDATE';
export function planNextVerificationSteps(read:ReadDocumentResult):ReadingReadiness {
  const {extraction}=read;
  const reasons:ReadinessReason[]=[];
  const followUps:UserFollowUp[]=[];
  const add=(r:ReadinessReason)=>{if(!reasons.includes(r))reasons.push(r);};
  const request=(r:UserFollowUp)=>{if(!followUps.includes(r))followUps.push(r);};
  const fields=extraction.fields;
  if(extraction.documentKind==='UNKNOWN')add('DOCUMENT_TYPE_UNRECOGNIZED');
  if(read.qualityReviewPages?.length){add('IMAGE_QUALITY_REVIEW_REQUIRED');request('RETAKE_LOW_QUALITY_PAGE');}
  const badPageCount=extraction.issues.includes('UNREADABLE_PAGE_COUNT');
  if(badPageCount){add('PAGE_NUMBER_UNREADABLE');request('RETAKE_PAGE_FOOTER');}
  if(extraction.issues.includes('INCONSISTENT_PAGE_COUNT')){
    add('PAGE_NUMBER_CONTRADICTORY');request('RETAKE_PAGE_FOOTER');
  }
  if(extraction.missingPages.length){add('MISSING_DOCUMENT_PAGES');request('SEND_FULL_DOCUMENT');}
  if(!usable(fields.registryReference)){add('TITLE_REFERENCE_MISSING_OR_UNCERTAIN');request('RETAKE_REGISTRY_REFERENCE');}
  if(!usable(fields.areaSquareMetres)){add('AREA_MISSING_OR_UNCERTAIN');request('RETAKE_AREA');}
  // An OCR number of "parts/أجزاء" can be both the *total* and the offered share;
  // it is not evidence of the submitter's exact legal share or capacity.
  if(!potentiallyLegalShare(fields.ownershipShareUnits) || !usable(fields.ownershipTotalUnits)){
    add('SHARE_UNCONFIRMED');
  }
  if(!usable(fields.certificateIssuedAt) && usable(fields.lastRegistryUpdatedAt)) {
    add('HISTORIC_DATE_IS_NOT_CERTIFICATE_ISSUE_DATE');
  }
  add('PERSON_IDENTITY_NOT_YET_CORROBORATED');
  add('CURRENT_CPF_STATUS_NOT_YET_CORROBORATED');
  request('PROVIDE_RECENT_CPF_PROOF');
  request('AUTHORIZED_REVIEW_REQUIRED');
  return {
    ownershipVerified:false, mayPublish:false, requiresIndependentReview:true,
    needsAdditionalImages:followUps.some(x=>x==='SEND_FULL_DOCUMENT'||x==='RETAKE_PAGE_FOOTER'||x==='RETAKE_REGISTRY_REFERENCE'||x==='RETAKE_AREA'||x==='RETAKE_LOW_QUALITY_PAGE'),
    reasons,followUps,
    criticalFields:{
      registryReference:fields.registryReference.status,
      areaSquareMetres:fields.areaSquareMetres.status,
      ownershipShareUnits:fields.ownershipShareUnits.status
    }
  };
}
