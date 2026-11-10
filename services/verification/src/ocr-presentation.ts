/**
 * Private seller-facing OCR presentation from the existing CPF reader.
 * Never put this DTO into a PUBLIC listing / CDN response: title references,
 * share data and dates can be personal. Authz/consent must be checked at ingress.
 * An extracted OCR candidate is NEVER a legal or identity match.
 */
import type {ReadDocumentResult} from './reader.ts';
import type {FieldName, ExtractedField, SmartExtraction} from './smart-fields.ts';

const LABELS: Record<FieldName,{ar:string;fr:string}>={
  registryReference:{ar:'مرجع الرسم العقاري',fr:'Référence du titre foncier'},
  areaSquareMetres:{ar:'المساحة حسب الشهادة',fr:'Superficie du certificat'},
  ownershipShareUnits:{ar:'الأنصبة المذكورة',fr:'Parts mentionnées'},
  ownershipTotalUnits:{ar:'إجمالي الأنصبة',fr:'Total des parts'},
  parcelCount:{ar:'عدد القطع',fr:'Nombre de parcelles'},
  parcelNumber:{ar:'رقم القطعة',fr:'Numéro de parcelle'},
  documentPage:{ar:'صفحة الوثيقة',fr:'Page du document'},
  documentPageCount:{ar:'عدد صفحات الوثيقة',fr:'Nombre de pages'},
  certificateIssuedAt:{ar:'تاريخ إصدار الشهادة',fr:'Date de délivrance'},
  lastRegistryUpdatedAt:{ar:'تاريخ آخر تحيين للسجل',fr:'Dernière mise à jour du registre'}
};
const DISPLAY: readonly FieldName[]=[
  'areaSquareMetres','registryReference','ownershipShareUnits',
  'ownershipTotalUnits','parcelCount','parcelNumber',
  'certificateIssuedAt','lastRegistryUpdatedAt','documentPageCount'
];

export interface PrivateOcrFact {
  readonly id:FieldName;
  readonly labelAr:string;
  readonly labelFr:string;
  readonly value:string|null;
  readonly status:ExtractedField['status'];
  readonly page:number|null;
  readonly source:'pdf-text'|'ocr-ar'|'ocr-fr'|null;
  readonly confidencePercent:number|null;
}
export interface PrivateOcrPresentation {
  readonly schemaVersion:1;
  readonly access:'AUTHORIZED_OWNER_OR_REVIEWER_ONLY';
  readonly documentKind:SmartExtraction['documentKind'];
  readonly pagesReceived:number;
  readonly missingPages:readonly number[];
  readonly qualityReviewPages:readonly number[];
  readonly facts:readonly PrivateOcrFact[];
  readonly declaredAreaComparison:'SAME_NUMBER_UNVERIFIED'|'DIFFERENT_NUMBER_REVIEW'|'NOT_COMPARABLE';
  readonly ownerIdentityMatch:'NOT_STARTED';
  readonly officialCpfOwnershipCheck:'NOT_STARTED';
  readonly requiresIndependentReview:true;
  readonly ownershipVerified:false;
  readonly mayPublish:false;
}

function firstSource(f:ExtractedField):PrivateOcrFact['source'] {return f.evidence[0]?.source??null;}
function safeValue(f:ExtractedField):string|null {
  if(f.value===null||f.status==='CONFLICT'||f.status==='ABSENT')return null;
  return String(f.value).slice(0,64);
}
function safeArea(input:unknown):number|null {
  if(typeof input!=='string'&&typeof input!=='number')return null;
  const t=String(input).trim().replace(',','.');
  if(!/^\d{1,10}(?:\.\d{1,3})?$/.test(t))return null;
  const n=Number(t);return Number.isFinite(n)&&n>0&&n<=1e10?n:null;
}
/**
 * Data-only view for a PRIVILEGED application boundary. Never call this from an
 * unauthenticated public listing route; it intentionally includes title numbers.
 */
export function presentPrivateCpfReading(read:ReadDocumentResult,declaredArea:unknown):PrivateOcrPresentation {
  if(read?.requiresIndependentOwnershipReview!==true||read.mayPublish!==false||
     read.extraction?.mayUseForAutomaticApproval!==false)
    throw new TypeError('UNTRUSTED_OCR_READING');
  const facts=DISPLAY.map(id=>{
    const f=read.extraction.fields[id];
    if(!f||!Array.isArray(f.evidence))throw new TypeError('INVALID_OCR_FIELD');
    const source=f.evidence[0];
    return {
      id,labelAr:LABELS[id].ar,labelFr:LABELS[id].fr,
      value:safeValue(f),status:f.status,
      page:source?.page??null,source:firstSource(f),
      confidencePercent:source?.confidence===null||source?.confidence===undefined?
        null:Math.round(source.confidence*100)
    } satisfies PrivateOcrFact;
  });
  const documentArea=read.extraction.fields.areaSquareMetres;
  const declared=safeArea(declaredArea);
  const extracted=documentArea.status==='CANDIDATE'?safeArea(documentArea.value):null;
  const comparison=declared===null||extracted===null?'NOT_COMPARABLE':
    declared===extracted?'SAME_NUMBER_UNVERIFIED':'DIFFERENT_NUMBER_REVIEW';
  return {
    schemaVersion:1,access:'AUTHORIZED_OWNER_OR_REVIEWER_ONLY',
    documentKind:read.extraction.documentKind,
    pagesReceived:read.pagesReceived,
    missingPages:[...read.extraction.missingPages],
    qualityReviewPages:[...read.qualityReviewPages],
    facts,declaredAreaComparison:comparison,
    ownerIdentityMatch:'NOT_STARTED',officialCpfOwnershipCheck:'NOT_STARTED',
    requiresIndependentReview:true,ownershipVerified:false,mayPublish:false
  };
}
