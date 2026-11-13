/**
 * Tunisian land-paper document-family classifier (Smart Listing V2, stage 1).
 *
 * Deterministic, free, fast post-OCR *candidate* detection, NOT image-model
 * training, ownership authentication, registry lookup, or publication approval.
 * All inputs are untrusted. This module NEVER transmits, stores or logs OCR text.
 * Keep real scanned deeds / names / CIN / property references out of public CI.
 */
import type {RecognizedLine} from './smart-fields.ts';

export type LandPaperKind =
  | 'OWNERSHIP_CERTIFICATE'
  | 'SHARED_OWNERSHIP_CERTIFICATE'
  | 'TRANSFER_CARD'
  | 'SALE_DEED'
  | 'POSSESSION_REPORT'
  | 'FINANCIAL_RECEIPT'
  | 'PROPERTY_SERVICE_RECEIPT'
  | 'UNKNOWN';
export type ClassificationStatus='CANDIDATE'|'LOW_CONFIDENCE'|'AMBIGUOUS'|'UNKNOWN';
export type FieldPriority='REQUIRED'|'IMPORTANT'|'OPTIONAL';
export interface DocumentFieldRule {
  /** Key in a future typed extraction contract; not an auto-populated value. */
  readonly key:string;
  readonly priority:FieldPriority;
  readonly note?:string;
}
export interface TitleEvidence {
  readonly page:number;
  readonly lineIndex:number;
  readonly confidence:number|null;
  readonly source:RecognizedLine['source'];
  /** Only a fixed safe rule name, NEVER actual OCR text/PII. */
  readonly rule:string;
}
export interface DocumentTrainingCandidate {
  readonly kind:LandPaperKind;
  readonly status:ClassificationStatus;
  readonly evidence:readonly TitleEvidence[];
  readonly fieldsToExtract:readonly DocumentFieldRule[];
  readonly warnings:readonly (
    'NO_SUPPORTED_TITLE'|'AMBIGUOUS_DOCUMENT_FAMILY'|'LOW_TITLE_CONFIDENCE'|
    'SUPPORTING_RECEIPT_ONLY'|'SUPPORTING_SERVICE_RECEIPT_ONLY'|'TRANSFER_TEMPLATE_NOT_EVALUATED'
  )[];
  /** This is a classification hint only; it cannot approve a property listing. */
  readonly mayVerifyOwnership:false;
  readonly mayPublish:false;
  readonly mayAutofillUnreviewed:false;
}

/**
 * Full extraction targets by document kind. Fields are *not* inferred when
 * absent in the source. In particular area_m2 != ownership_parts.
 */
export const LAND_DOCUMENT_FIELD_RULES:Readonly<Record<LandPaperKind,readonly DocumentFieldRule[]>>={
  OWNERSHIP_CERTIFICATE:[
    {key:'issuer_office',priority:'IMPORTANT'},
    {key:'register_reference',priority:'REQUIRED'},
    {key:'original_register_reference',priority:'OPTIONAL'},
    {key:'registered_property_name',priority:'IMPORTANT'},
    {key:'registered_area_m2',priority:'REQUIRED',note:'Whole registered estate; never offered sale area'},
    {key:'ownership_parts_denominator',priority:'REQUIRED',note:'Abstract rights parts, not square metres'},
    {key:'registered_plot_numbers',priority:'IMPORTANT'},
    {key:'land_use_and_category',priority:'IMPORTANT'},
    {key:'registered_location',priority:'REQUIRED'},
    {key:'all_rights_holders_across_pages',priority:'REQUIRED',note:'Names, CIN and DOB only when printed; never public listing fields'},
    {key:'rights_parts_per_holder',priority:'REQUIRED',note:'Decimal precision; sum must equal printed denominator before suggesting shares'},
    {key:'registration_references_per_holder',priority:'IMPORTANT'},
    {key:'registration_dates_per_holder',priority:'IMPORTANT'},
    {key:'certificate_issue_date',priority:'IMPORTANT',note:'Keep distinct from last registry update'},
    {key:'last_registry_update',priority:'IMPORTANT'},
    {key:'total_page_count_and_received_pages',priority:'REQUIRED'},
    {key:'restrictions_easements_and_legal_remarks',priority:'REQUIRED',note:'Preserve full source clause and review, never infer cleared title'},
    {key:'stamp_signature_visual_presence',priority:'OPTIONAL',note:'Appearance is NOT authenticity verification'},
  ],
  SHARED_OWNERSHIP_CERTIFICATE:[
    {key:'register_reference',priority:'REQUIRED'},
    {key:'original_register_reference',priority:'IMPORTANT'},
    {key:'whole_registered_area_m2',priority:'REQUIRED'},
    {key:'ownership_parts_denominator',priority:'REQUIRED'},
    {key:'all_coowners_and_parts_across_pages',priority:'REQUIRED'},
    {key:'registration_references',priority:'IMPORTANT'},
    {key:'registry_plot_numbers',priority:'IMPORTANT'},
    {key:'land_type_and_location',priority:'IMPORTANT'},
    {key:'certificate_dates_and_pages',priority:'REQUIRED'},
    {key:'rights_limits_and_remarks',priority:'REQUIRED'},
  ],
  TRANSFER_CARD:[
    {key:'document_title_and_issuing_body',priority:'REQUIRED'},
    {key:'register_reference',priority:'REQUIRED'},
    {key:'transferor_and_transferee',priority:'REQUIRED',note:'Both parties and capacities as written, no current-owner inference'},
    {key:'transfer_operation_and_reference',priority:'REQUIRED'},
    {key:'transfer_date_or_registration_date',priority:'REQUIRED'},
    {key:'transferred_scope_plot_area_or_parts',priority:'REQUIRED',note:'Keep physical area, plot and abstract parts distinct'},
    {key:'record_book_and_filing_details',priority:'IMPORTANT'},
    {key:'document_pages_and_caveats',priority:'IMPORTANT'},
  ],
  SALE_DEED:[
    {key:'notary_issuer_and_contract_date',priority:'REQUIRED'},
    {key:'seller_buyer_and_legal_representatives',priority:'REQUIRED',note:'Role extraction, not just first detected person'},
    {key:'register_parent_reference',priority:'REQUIRED'},
    {key:'parent_registered_area_m2',priority:'IMPORTANT'},
    {key:'sold_plot_reference',priority:'REQUIRED'},
    {key:'sold_area_m2',priority:'REQUIRED',note:'Never substitute parent registered area'},
    {key:'sale_price_tnd_and_payment_terms',priority:'IMPORTANT',note:'Tunisian millime formatting must be explicit'},
    {key:'sale_registration_references',priority:'IMPORTANT'},
    {key:'boundaries_and_adjacent_plots',priority:'IMPORTANT'},
    {key:'proxy_authority_when_mentioned',priority:'REQUIRED'},
    {key:'multi_page_signatures_and_caveats',priority:'REQUIRED'},
  ],
  POSSESSION_REPORT:[
    {key:'officer_issuer_and_act_datetime',priority:'REQUIRED'},
    {key:'applicant_and_parties_mentioned',priority:'IMPORTANT'},
    {key:'parent_register_reference',priority:'REQUIRED'},
    {key:'possessed_plot_reference',priority:'REQUIRED'},
    {key:'observed_possession_area_m2',priority:'REQUIRED',note:'Possession does NOT establish registered ownership'},
    {key:'boundaries_measurements_and_access',priority:'IMPORTANT'},
    {key:'underlying_sale_contract_reference',priority:'IMPORTANT'},
    {key:'signed_observations_and_pages',priority:'REQUIRED'},
  ],
  FINANCIAL_RECEIPT:[
    {key:'receipt_issuer_number_and_series',priority:'REQUIRED'},
    {key:'payer_and_fiscal_id',priority:'IMPORTANT'},
    {key:'transaction_purpose_and_reference',priority:'IMPORTANT'},
    {key:'amount_millimes_and_payment_method',priority:'REQUIRED'},
    {key:'payment_date_and_stamp',priority:'IMPORTANT'},
  ],
  PROPERTY_SERVICE_RECEIPT:[
    {key:'service_request_number_and_date',priority:'REQUIRED'},
    {key:'service_applicant',priority:'IMPORTANT',note:'Applicant is not necessarily a registered owner'},
    {key:'land_register_references',priority:'IMPORTANT',note:'References are supporting context, not ownership'},
    {key:'service_requested',priority:'IMPORTANT'},
    {key:'fee_amount_and_payment_method',priority:'REQUIRED'},
    {key:'issuing_office_and_receipt_stamp',priority:'IMPORTANT'},
  ],
  UNKNOWN:[],
};

type SupportedKind=Exclude<LandPaperKind,'UNKNOWN'>;
const TITLES:readonly {kind:SupportedKind;rule:string;pattern:RegExp}[]=[
  {kind:'SHARED_OWNERSHIP_CERTIFICATE',rule:'SHARED_CERTIFICATE_HEADING',pattern:/شهاد[ةه]\s+اشتراك\s+في\s+الملكي[ةه]|certificat\s+(?:de\s+)?(?:co)?propri[eé]t[eé]\s+indivis/iu},
  {kind:'OWNERSHIP_CERTIFICATE',rule:'OWNERSHIP_CERTIFICATE_HEADING',pattern:/شهاد[ةه]\s+ملكي[ةه]|certificat\s+de\s+propri[eé]t[eé]/iu},
  {kind:'TRANSFER_CARD',rule:'TRANSFER_CARD_HEADING',pattern:/بطاق[ةه]\s+(?:نقل|انتقال)(?:\s+(?:ال)?ملكي[ةه])?|carte\s+de\s+mutation\s+fonci[eè]re/iu},
  {kind:'SALE_DEED',rule:'SALE_DEED_HEADING',pattern:/(?:انجاز|اشهاد|عقد)\s+(?:ال)?بيع|acte\s+de\s+vente/iu},
  {kind:'POSSESSION_REPORT',rule:'POSSESSION_REPORT_HEADING',pattern:/محضر\s+(?:معاين[ةه]\s+)?تحويز|proc[eè]s.verbal\s+de\s+prise\s+de\s+possession/iu},
  {kind:'FINANCIAL_RECEIPT',rule:'QUITTANCE_HEADING',pattern:/quittance\s*(?:n[°o.]|num[eé]ro)?|وصل\s+قبض/iu},
  {kind:'PROPERTY_SERVICE_RECEIPT',rule:'CPF_SERVICE_RECEIPT_HEADING',pattern:/وصل\s+تسلم\s+مطلب\s+خدم[ةه]\s+و\s+استخلاص\s+معاليم/iu},
];
function normalizeTitle(s:string):string {
  return s.normalize('NFKC')
    .replace(/[\u064B-\u065F\u0670\u0640\u200E\u200F\u202A-\u202E\u2066-\u2069]/gu,'')
    .replace(/[إأآ]/gu,'ا').replace(/ى/gu,'ي')
    .replace(/\s+/gu,' ').trim();
}
/**
 * Stage-1 title classification. Only the first page's short title/header
 * lines can identify a kind; body mentions of other legal papers cannot.
 * A later page alone is UNKNOWN, never assigned its referenced document kind.
 */
export function classifyLandPaper(lines:readonly RecognizedLine[]):DocumentTrainingCandidate {
  if(lines.length>2000||lines.reduce((n,x)=>n+x.text.length,0)>150000)throw new RangeError('DOCUMENT_TEXT_TOO_LARGE');
  for(const l of lines){
    if(!Number.isSafeInteger(l.page)||l.page<1||l.page>8||typeof l.text!=='string'||
      l.text.length>1200||(l.confidence!==null&&
        (!Number.isFinite(l.confidence)||l.confidence<0||l.confidence>1))||
      !['ocr-ar','ocr-fr','pdf-text'].includes(l.source))
      throw new TypeError('INVALID_DOCUMENT_OCR_LINE');
  }
  const header=lines.map((line,index)=>({line,index})).filter(x=>x.line.page===1).slice(0,36);
  const hits=new Map<SupportedKind,TitleEvidence[]>();
  for(const {line,index} of header){
    const text=normalizeTitle(line.text);
    // Document title is a short heading. A sale contract body may mention a
    // different paper ('شهادة ملكية' / 'وصل قبض'), but cannot reclassify the file.
    if(text.length<3||text.length>115)continue;
    for(const rule of TITLES){
      const match=rule.pattern.exec(text);
      if(!match)continue;
      // A true heading must appear at the start (allowing a short issuer
      // prefix and ornamental stars), not deep inside a narrative sentence.
      // Tax receipts cited inside a deed are NOT separate uploaded receipts.
      if(match.index>22 || (rule.kind==='FINANCIAL_RECEIPT' &&
          (text.length>65 || /(?:ضمن|مرفق|اشير|بموجب|سابق)/u.test(text))))continue;
      if(match.index===0 && text.length>95 &&
          rule.kind!=='SALE_DEED')continue;
      {
        const evidence:TitleEvidence={page:line.page,lineIndex:index,
          confidence:line.confidence,source:line.source,rule:rule.rule};
        hits.set(rule.kind,[...(hits.get(rule.kind)??[]),evidence]);
      }
    }
  }
  if(hits.size!==1){
    const ambiguous=hits.size>1;
    return {kind:'UNKNOWN',status:ambiguous?'AMBIGUOUS':'UNKNOWN',
      evidence:Array.from(hits.values()).flat(),
      fieldsToExtract:LAND_DOCUMENT_FIELD_RULES.UNKNOWN,
      warnings:[ambiguous?'AMBIGUOUS_DOCUMENT_FAMILY':'NO_SUPPORTED_TITLE'],
      mayVerifyOwnership:false,mayPublish:false,mayAutofillUnreviewed:false};
  }
  const [kind,evidence]=Array.from(hits.entries())[0];
  const low=evidence.every(e=>e.confidence!==null&&e.confidence<0.88);
  const warnings:DocumentTrainingCandidate['warnings'][number][]=[];
  if(low)warnings.push('LOW_TITLE_CONFIDENCE');
  if(kind==='FINANCIAL_RECEIPT')warnings.push('SUPPORTING_RECEIPT_ONLY');
  if(kind==='PROPERTY_SERVICE_RECEIPT')warnings.push('SUPPORTING_SERVICE_RECEIPT_ONLY');
  if(kind==='TRANSFER_CARD')warnings.push('TRANSFER_TEMPLATE_NOT_EVALUATED');
  return {kind,status:low?'LOW_CONFIDENCE':'CANDIDATE',evidence,
    fieldsToExtract:LAND_DOCUMENT_FIELD_RULES[kind],warnings,
    mayVerifyOwnership:false,mayPublish:false,mayAutofillUnreviewed:false};
}
