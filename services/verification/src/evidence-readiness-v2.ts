/**
 * MALAK Smart Listing V2 — private evidence readiness (NOT seller honesty).
 *
 * Editorial product heuristics, NOT calibrated statistical probabilities and
 * NOT the Tunisian legal status of any property/person. 0..75 points only;
 * 25 points deliberately reserved for future independent official verification
 * which this module NEVER awards. Never show a public 'verified seller' badge
 * or use this score as authorization, filtering or auto-publication.
 *
 * All recognized document kinds, including receipts and UNKNOWN, remain
 * eligible for the PRIVATE upload/review journey if file safety gates pass.
 */
import type {DocumentTrainingCandidate,LandPaperKind} from './document-training-v2.ts';
import type {PrivateLandFacts} from './document-family-fields-v2.ts';

export type PreliminaryIdentityTextStatus=
  'NOT_CHECKED'|'SAME_TEXT_UNVERIFIED'|'DIFFERENT_TEXT'|'INCONCLUSIVE';
export type PreliminaryPropertyTextStatus=
  'NOT_CHECKED'|'SAME_REFERENCE_UNVERIFIED'|'DIFFERENT_SCOPE'|'INCONCLUSIVE';
export type EvidenceReviewIssue=
  'DOCUMENT_UNCLASSIFIED'|'TYPE_LOW_CONFIDENCE'|'UNTESTED_TRANSFER_TEMPLATE'|
  'DOCUMENT_FIELDS_CONFLICT'|'IDENTITY_TEXT_DIFFERS'|'PROPERTY_SCOPE_DIFFERS'|
  'IDENTITY_NOT_CHECKED'|'OFFICIAL_REGISTRY_NOT_CHECKED';
export interface PrivateEvidenceReadiness {
  /** Product priority heuristic (0..5), not "legal trust" or probability. */
  readonly documentSupportLevel:0|1|2|3|4|5;
  /** Partial private review progress, 0..75; no official registry points. */
  readonly provisionalProgressPoints:number;
  readonly provisionalMaxPoints:75;
  readonly reservedOfficialVerificationPoints:25;
  readonly components:Readonly<{
    documentEvidencePoints:number;
    sourceLegibilityPoints:number;
    preliminaryIdentityTextPoints:number;
    preliminaryPropertyScopePoints:number;
  }>;
  readonly issues:readonly EvidenceReviewIssue[];
  readonly intakeDecision:'ACCEPT_FOR_PRIVATE_REVIEW';
  readonly scoringModel:'HEURISTIC_PRIVATE_REVIEW_NOT_PROBABILITY';
  readonly officialOwnershipVerified:false;
  readonly sellerIdentityVerified:false;
  readonly displayAsVerifiedSeller:false;
  readonly mayPublish:false;
}
const SUPPORT:Readonly<Record<LandPaperKind,{level:PrivateEvidenceReadiness['documentSupportLevel'];points:number}>>={
  OWNERSHIP_CERTIFICATE:{level:5,points:40},
  SHARED_OWNERSHIP_CERTIFICATE:{level:4,points:35},
  SALE_DEED:{level:3,points:28},
  TRANSFER_CARD:{level:3,points:25},
  POSSESSION_REPORT:{level:2,points:15},
  PROPERTY_SERVICE_RECEIPT:{level:1,points:7},
  FINANCIAL_RECEIPT:{level:1,points:5},
  UNKNOWN:{level:0,points:0},
};
const TEXT_STATES:readonly PreliminaryIdentityTextStatus[]=[
 'NOT_CHECKED','SAME_TEXT_UNVERIFIED','DIFFERENT_TEXT','INCONCLUSIVE'
];
const PROPERTY_STATES:readonly PreliminaryPropertyTextStatus[]=[
 'NOT_CHECKED','SAME_REFERENCE_UNVERIFIED','DIFFERENT_SCOPE','INCONCLUSIVE'
];
export function assessPrivateEvidenceReadiness(
 classification:DocumentTrainingCandidate,
 facts:PrivateLandFacts,
 identityText:PreliminaryIdentityTextStatus='NOT_CHECKED',
 propertyText:PreliminaryPropertyTextStatus='NOT_CHECKED'
):PrivateEvidenceReadiness {
 if(classification.kind!==facts.kind||classification.status!==facts.documentStatus||
    !TEXT_STATES.includes(identityText)||!PROPERTY_STATES.includes(propertyText))
   throw new TypeError('INVALID_EVIDENCE_PROGRESS_INPUT');
 const issues=new Set<EvidenceReviewIssue>();
 if(classification.kind==='UNKNOWN'||classification.status==='AMBIGUOUS'||
    classification.status==='UNKNOWN')issues.add('DOCUMENT_UNCLASSIFIED');
 if(classification.status==='LOW_CONFIDENCE')issues.add('TYPE_LOW_CONFIDENCE');
 if(classification.kind==='TRANSFER_CARD')issues.add('UNTESTED_TRANSFER_TEMPLATE');
 if(facts.facts.some(f=>f.status==='CONFLICT'))
   issues.add('DOCUMENT_FIELDS_CONFLICT');
 if(identityText==='DIFFERENT_TEXT')issues.add('IDENTITY_TEXT_DIFFERS');
 if(propertyText==='DIFFERENT_SCOPE')issues.add('PROPERTY_SCOPE_DIFFERS');
 if(identityText==='NOT_CHECKED'||identityText==='INCONCLUSIVE')
   issues.add('IDENTITY_NOT_CHECKED');
 issues.add('OFFICIAL_REGISTRY_NOT_CHECKED');

 const classificationUsable=classification.status==='CANDIDATE';
 // Do not award class/legibility points for an unrecognized or uncertain title.
 const docEvidence=classificationUsable?SUPPORT[classification.kind].points:0;
 const hasConflict=facts.facts.some(f=>f.status==='CONFLICT');
 const readableCount=facts.facts.filter(f=>f.status==='CANDIDATE'&&
   f.value!==null&&f.evidence.length>0).length;
 const sourceLegibility=classificationUsable&&!hasConflict?
   Math.min(10,readableCount*3):0;
 // These points reflect only preliminary text agreement, never genuine ID
 // or independent CPF ownership verification.
 const identityPoints=identityText==='SAME_TEXT_UNVERIFIED'&&!hasConflict?15:0;
 const propertyPoints=propertyText==='SAME_REFERENCE_UNVERIFIED'&&!hasConflict?10:0;
 const score=Math.min(75,docEvidence+sourceLegibility+identityPoints+propertyPoints);
 return {
   documentSupportLevel:SUPPORT[classification.kind].level,
   provisionalProgressPoints:score,
   provisionalMaxPoints:75,
   reservedOfficialVerificationPoints:25,
   components:{
     documentEvidencePoints:docEvidence,
     sourceLegibilityPoints:sourceLegibility,
     preliminaryIdentityTextPoints:identityPoints,
     preliminaryPropertyScopePoints:propertyPoints,
   },
   issues:[...issues],
   intakeDecision:'ACCEPT_FOR_PRIVATE_REVIEW',
   scoringModel:'HEURISTIC_PRIVATE_REVIEW_NOT_PROBABILITY',
   officialOwnershipVerified:false,
   sellerIdentityVerified:false,
   displayAsVerifiedSeller:false,
   mayPublish:false,
 };
}
