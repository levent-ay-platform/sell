/**
 * Smart Listing V2, stage 4: bounded *private* source-backed field candidates.
 *
 * Legal documents are not interchangeable: registered whole-estate area,
 * purchased sub-plot area, possession area and abstract rights shares are
 * separate fields. Never infer one from another. No title/legal approval,
 * public publication or unattended autofill is possible here.
 *
 * No generative guesses, paid API, logging or real private fixtures.
 */
import type {RecognizedLine, OcrPixelBox} from './smart-fields.ts';
import type {DocumentTrainingCandidate,LandPaperKind} from './document-training-v2.ts';
export type LandFactKey=
  'registerReference'|'parentRegisterReference'|'registeredAreaM2'|
  'soldAreaM2'|'possessedAreaM2'|'transferredAreaM2'|
  'registeredPlotNumber'|'soldPlotNumber'|'possessedPlotNumber'|
  'rightsDenominator'|'seller'|'buyer'|'transferor'|'transferee'|
  'salePriceTnd'|'receiptAmountTnd'|'receiptNumber'|'transferReference'|
  'serviceRequestNumber'|'serviceRequestDate'|'serviceApplicant'|
  'possessionRequester'|'receiptPayer';
export type LandFactStatus='CANDIDATE'|'LOW_CONFIDENCE'|'CONFLICT'|'MASKED'|'ABSENT';
export interface PrivateLandFactEvidence {
  readonly page:number;
  readonly lineIndex:number;
  readonly confidence:number|null;
  readonly source:RecognizedLine['source'];
  readonly bbox:OcrPixelBox|null;
}
export interface PrivateLandFact {
  readonly key:LandFactKey;
  readonly value:string|null;
  readonly status:LandFactStatus;
  readonly evidence:readonly PrivateLandFactEvidence[];
}
export interface PrivateLandFacts {
  readonly kind:LandPaperKind;
  readonly documentStatus:DocumentTrainingCandidate['status'];
  readonly facts:readonly PrivateLandFact[];
  readonly issues:readonly (
    'UNKNOWN_OR_AMBIGUOUS_FAMILY'|'UNTESTED_TRANSFER_TEMPLATE'|
    'SUPPORTING_RECEIPT_NOT_TITLE'|'SUPPORTING_SERVICE_RECEIPT_NOT_TITLE'|'CONFLICTING_CRITICAL_FIELDS'|
    'LOW_CONFIDENCE_CRITICAL_FIELDS'
  )[];
  readonly access:'AUTHORIZED_SELLER_OR_REVIEWER_ONLY';
  readonly mayAutofillUnreviewed:false;
  readonly ownershipVerified:false;
  readonly mayPublish:false;
}
type Shape='title'|'area'|'parts'|'plot'|'money'|'actor'|'reference'|'date';
interface Rule {key:LandFactKey;pattern:RegExp;shape:Shape;}
const commonTitle:Rule={
 key:'registerReference',
 pattern:/^(?:معرف\s+(?:ال)?رسم\s+العقاري|الرسم\s+العقاري\s+(?:عدد|رقم|n[°o])|titre\s+foncier\s*(?:n[°o.]|num[eé]ro)?)[\s:#：-]*([0-9]{3,12})(?:\s+(?:نابل|تونس|سوسة|صفاقس))?\s*$/iu,
 shape:'title'
};
const parent:Rule={
 key:'parentRegisterReference',
 pattern:/^(?:الرسم\s+العقاري\s+(?:ال)?ام|الرسم\s+(?:العقاري\s+)?الاصلي|parent\s+titre\s+foncier)[\s:#：-]*([0-9]{3,12})\s*$/iu,
 shape:'title'
};
const totalArea:Rule={
 key:'registeredAreaM2',
 pattern:/^(?:المساح[ةه]\s*(?:الكلي[ةه])?|superficie(?:\s+totale)?)[\s:#：-]*([0-9]{1,10}(?:[.,][0-9]{1,3})?)\s*(?:م(?:تر)?(?:\s*مربع|²|2)?|m²|m2)?\s*$/iu,
 shape:'area'
};
const denominator:Rule={
 key:'rightsDenominator',
 pattern:/^(?:مجموع\s+(?:اجزاء|الاجزاء)\s+الملكي[ةه]|total\s+des\s+parts)[\s:#：-]*([0-9]{1,12}(?:[.,][0-9]{1,3})?)\s*(?:جزء|اجزاء)?\s*$/iu,
 shape:'parts'
};
const registeredPlot:Rule={
 key:'registeredPlotNumber',
 pattern:/^(?:القطع[ةه]\s+(?:عدد|رقم)|num[eé]ro\s+de\s+parcelle)[\s:#：-]*([0-9]{1,8})\s*$/iu,
 shape:'plot'
};
const soldPlot:Rule={
 key:'soldPlotNumber',
 pattern:/^(?:القطع[ةه]\s+(?:المبيع[ةه]|موضوع\s+البيع)\s+(?:عدد|رقم)|lot\s+vendu\s*(?:n[°o.]|num[eé]ro)?)[\s:#：-]*([0-9]{1,8})\s*$/iu,
 shape:'plot'
};
const possessedPlot:Rule={
 key:'possessedPlotNumber',
 pattern:/^(?:القطع[ةه]\s+(?:محل\s+التحويز|المعاين[ةه])\s+(?:عدد|رقم)|lot\s+constat[eé]\s*(?:n[°o.]|num[eé]ro)?)[\s:#：-]*([0-9]{1,8})\s*$/iu,
 shape:'plot'
};
const soldArea:Rule={
 key:'soldAreaM2',
 pattern:/^(?:(?:ال)?مساح[ةه]\s+(?:القطع[ةه]\s+)?المبيع[ةه]|المساح[ةه]\s+موضوع\s+البيع|superficie\s+vendue)[\s:#：-]*([0-9]{1,10}(?:[.,][0-9]{1,3})?)\s*(?:م(?:تر)?(?:\s*مربع|²|2)?|m²|m2)?\s*$/iu,
 shape:'area'
};
const possessedArea:Rule={
 key:'possessedAreaM2',
 pattern:/^(?:مساح[ةه]\s+(?:القطع[ةه]\s+)?(?:محل\s+التحويز|المعاين[ةه]|الحياز[ةه])|superficie\s+constat[eé]e)[\s:#：-]*([0-9]{1,10}(?:[.,][0-9]{1,3})?)\s*(?:م(?:تر)?(?:\s*مربع|²|2)?|m²|m2)?\s*$/iu,
 shape:'area'
};
const transferredArea:Rule={
 key:'transferredAreaM2',
 pattern:/^(?:المساح[ةه]\s+المنقول[ةه]|المساح[ةه]\s+موضوع\s+النقل|superficie\s+transf[eé]r[eé]e)[\s:#：-]*([0-9]{1,10}(?:[.,][0-9]{1,3})?)\s*(?:م(?:تر)?(?:\s*مربع|²|2)?|m²|m2)?\s*$/iu,
 shape:'area'
};
const seller:Rule={
 key:'seller',pattern:/^(?:البائع|vendeu?r)\s*[:：-]\s*(.{2,100})$/iu,shape:'actor'
};
const buyer:Rule={
 key:'buyer',pattern:/^(?:المشتري|acqu[eé]reur|acheteur)\s*[:：-]\s*(.{2,100})$/iu,shape:'actor'
};
const transferor:Rule={
 key:'transferor',pattern:/^(?:الناقل|المحيل|c[eé]dant)\s*[:：-]\s*(.{2,100})$/iu,shape:'actor'
};
const transferee:Rule={
 key:'transferee',pattern:/^(?:المنقول\s+اليه|المحال\s+اليه|cessionnaire)\s*[:：-]\s*(.{2,100})$/iu,shape:'actor'
};
const salePrice:Rule={
 key:'salePriceTnd',
 pattern:/^(?:ثمن\s+(?:البيع|العقار)|الثمن\s+الجملي|prix\s+de\s+vente)[\s:#：-]*([0-9]{1,12}(?:[.,][0-9]{1,3})?)\s*(?:دينار(?:ا)?|د\.?\s*ت\.?|dt|tnd)\s*$/iu,
 shape:'money'
};
const receiptNumber:Rule={
 key:'receiptNumber',
 pattern:/^(?:quittance\s+n[°ºo.]?|وصل\s+قبض\s+(?:عدد|رقم))[\s:#：-]*([A-Za-z0-9-]{2,30})\s*$/iu,
 shape:'reference'
};
const receiptAmount:Rule={
 key:'receiptAmountTnd',
 pattern:/^(?:montant|المبلغ|total\s+g[eé]n[eé]ral)[\s:#：-]*([0-9]{1,12}(?:[.,][0-9]{1,3})?)\s*(?:dt|tnd|دينار(?:ا)?)?\s*$/iu,
 shape:'money'
};
const transferRef:Rule={
 key:'transferReference',
 pattern:/^(?:مرجع\s+(?:عملي[ةه]\s+)?النقل|r[eé]f[eé]rence\s+de\s+mutation)[\s:#：-]*([A-Za-z0-9/-]{2,30})\s*$/iu,
 shape:'reference'
};
const serviceRequestNumber:Rule={
 key:'serviceRequestNumber',
 pattern:/^(?:العدد|رقم\s+(?:ال)?مطلب|مطلب\s+عدد|num[eé]ro\s+de\s+demande)[\s:#：-]*([0-9]{1,12})\s*$/iu,
 shape:'reference'
};
const serviceRequestDate:Rule={
 key:'serviceRequestDate',
 pattern:/^(?:التاريخ|تاريخ\s+(?:المطلب|الخدم[ةه])|date\s+de\s+demande)[\s:#：-]*([0-9]{4}[/.-][0-9]{2}[/.-][0-9]{2}|[0-9]{2}[/.-][0-9]{2}[/.-][0-9]{4})\s*$/iu,
 shape:'date'
};
const serviceApplicant:Rule={
 key:'serviceApplicant',
 pattern:/^(?:طالب\s+الخدم[ةه]|اسم\s+طالب\s+الخدم[ةه]|demandeur)[\s:#：-]*(.{2,100})$/iu,
 shape:'actor'
};
const possessionRequester:Rule={
 key:'possessionRequester',
 pattern:/^(?:طالب\s+(?:معاين[ةه]\s+)?التحويز|طالب\s+معاين[ةه]\s+الحياز[ةه]|requ[eé]rant\s+du\s+constat)[\s:#：-]+(.{2,100})$/iu,
 shape:'actor'
};
const receiptPayer:Rule={
 key:'receiptPayer',
 pattern:/^(?:دافع\s+(?:ال)?معاليم|اسم\s+الدافع|payeur)[\s:#：-]+(.{2,100})$/iu,
 shape:'actor'
};
const serviceReceiptAmount:Rule={
 key:'receiptAmountTnd',
 pattern:/^(?:المبلغ\s+(?:الجملي|المستخلص)|المبلغ|total\s+g[eé]n[eé]ral|montant\s+per[cç]u)[\s:#：-]*([0-9]{1,12}(?:[.,][0-9]{1,3})?)\s*(?:dt|tnd|دينار(?:ا)?)?\s*$/iu,
 shape:'money'
};
const RULES:Readonly<Record<LandPaperKind,readonly Rule[]>>={
 OWNERSHIP_CERTIFICATE:[commonTitle,parent,totalArea,registeredPlot,denominator],
 SHARED_OWNERSHIP_CERTIFICATE:[commonTitle,parent,totalArea,registeredPlot,denominator],
 SALE_DEED:[commonTitle,parent,soldPlot,soldArea,seller,buyer,salePrice],
 POSSESSION_REPORT:[commonTitle,parent,possessedPlot,possessedArea,possessionRequester],
 TRANSFER_CARD:[commonTitle,parent,transferredArea,transferor,transferee,transferRef],
 FINANCIAL_RECEIPT:[receiptNumber,receiptAmount,receiptPayer],
 PROPERTY_SERVICE_RECEIPT:[commonTitle,serviceRequestNumber,serviceRequestDate,
   serviceApplicant,serviceReceiptAmount],
 UNKNOWN:[]
};
function normalize(s:string):string {
 return s.normalize('NFKC')
  .replace(/[\u064B-\u065F\u0670\u0640\u200e\u200f\u202a-\u202e\u2066-\u2069]/gu,'')
  .replace(/[إأآ]/gu,'ا').replace(/ى/gu,'ي')
  .replace(/[ \t]+/gu,' ').trim();
}
function digits(s:string):string {
 return s.replace(/[٠-٩]/gu,d=>String(d.charCodeAt(0)-0x660))
  .replace(/[۰-۹]/gu,d=>String(d.charCodeAt(0)-0x6f0));
}
const masked=(s:string)=>/^(?:[Xx*█■▇#\s-]+)$/u.test(s);
function parseValue(raw:string,shape:Shape):{value:string|null;uncertain:boolean;masked:boolean} {
 const v=digits(raw.trim());
 if(masked(v))return {value:null,uncertain:false,masked:true};
 if(shape==='actor'){
   // Explicit role labels only; no CIN/DOB/transaction number in name.
   if(v.length>90||/[0-9\u0000-\u001f\u007f]/u.test(v)||
     !/^[\p{L}\p{M}\p{Zs}'’.-]+$/u.test(v))
     return {value:null,uncertain:false,masked:false};
   return {value:v,uncertain:false,masked:false};
 }
 if(shape==='title'||shape==='plot'){
   if(!/^\d{1,12}$/u.test(v)||Number(v)<=0)return {value:null,uncertain:false,masked:false};
   return {value:v,uncertain:false,masked:false};
 }
 if(shape==='date'){
   const tokens=v.split(/[\/.-]/u).map(Number);
   let year:number,month:number,day:number;
   if(tokens.length!==3)return {value:null,uncertain:false,masked:false};
   const first=tokens[0]!,second=tokens[1]!,third=tokens[2]!;
   if(first>999){year=first;month=second;day=third;}
   else{day=first;month=second;year=third;}
   if(year<1900||year>2100||month<1||month>12||day<1||day>31)
     return {value:null,uncertain:false,masked:false};
   const d=new Date(Date.UTC(year,month-1,day));
   if(d.getUTCFullYear()!==year||d.getUTCMonth()!==month-1||
      d.getUTCDate()!==day)return {value:null,uncertain:false,masked:false};
   return {value:String(year).padStart(4,'0')+'-'+String(month).padStart(2,'0')+
     '-'+String(day).padStart(2,'0'),uncertain:false,masked:false};
 }
 if(shape==='reference'){
   if(!/^[A-Za-z0-9/-]{2,30}$/u.test(v))return {value:null,uncertain:false,masked:false};
   return {value:v,uncertain:false,masked:false};
 }
 if(!/^\d{1,12}(?:[.,]\d{1,3})?$/u.test(v))return {value:null,uncertain:false,masked:false};
 const normalized=v.replace(',','.');
 const n=Number(normalized);
 if(!Number.isFinite(n)||n<=0||n>1e12)return {value:null,uncertain:false,masked:false};
 // 42,910 can be a decimal in Tunisian formatting OR grouping elsewhere.
 // Preserve exact text and flag any 3-digit comma suffix for review.
 const uncertain=/[,.]\d{3}$/u.test(v);
 return {value:normalized,uncertain,masked:false};
}
interface Candidate {value:string|null;status:LandFactStatus;evidence:PrivateLandFactEvidence;}
/** Only line-anchored explicit labels; narrative mentions are NOT facts. */
export function extractPrivateLandDocumentFacts(
 lines:readonly RecognizedLine[],classification:DocumentTrainingCandidate
):PrivateLandFacts {
 if(lines.length>2000||lines.reduce((n,l)=>n+l.text.length,0)>150000)
   throw new RangeError('DOCUMENT_FIELDS_TEXT_TOO_LARGE');
 const issues:PrivateLandFacts['issues'][number][]=[];
 const blocked=classification.kind==='UNKNOWN'||
   classification.status==='UNKNOWN'||classification.status==='AMBIGUOUS';
 if(blocked)issues.push('UNKNOWN_OR_AMBIGUOUS_FAMILY');
 if(classification.kind==='TRANSFER_CARD')issues.push('UNTESTED_TRANSFER_TEMPLATE');
 if(classification.kind==='FINANCIAL_RECEIPT')issues.push('SUPPORTING_RECEIPT_NOT_TITLE');
 if(classification.kind==='PROPERTY_SERVICE_RECEIPT')
   issues.push('SUPPORTING_SERVICE_RECEIPT_NOT_TITLE');
 const rules=blocked?[]:RULES[classification.kind];
 const collections=new Map<LandFactKey,Candidate[]>(rules.map(r=>[r.key,[]]));
 for(let i=0;i<lines.length;i++){
   const l=lines[i];
   if(typeof l.text!=='string'||l.text.length>1200||
     !Number.isSafeInteger(l.page)||l.page<1||l.page>8||
     (l.confidence!==null&&(!Number.isFinite(l.confidence)||l.confidence<0||
        l.confidence>1))||!['ocr-ar','ocr-fr','pdf-text'].includes(l.source))
     throw new TypeError('INVALID_LAND_FACT_SOURCE');
   const source:PrivateLandFactEvidence={
     page:l.page,lineIndex:i,source:l.source,confidence:l.confidence,
     bbox:l.bbox??null
   };
   const t=digits(normalize(l.text));
   if(t.length>170)continue; // Long contracts need explicit ROI labels.
   for(const rule of rules){
     const match=rule.pattern.exec(t);
     if(!match?.[1])continue;
     // Match canonicalized labels, but keep the actual personal name as
     // written. Normalizing hamzas/alef-maqsura in source names is a data
     // corruption even when it helps recognize the document heading.
     const originalActor=rule.shape==='actor'?
       /^[^:：-]{1,45}\s*[:：-]\s*(.{2,100})$/u.exec(l.text.normalize('NFKC'))?.[1]:null;
     if(rule.shape==='actor'&&!originalActor)continue;
     const parsed=parseValue(rule.shape==='actor'?originalActor!:match[1],rule.shape);
     if(parsed.value===null&&!parsed.masked)continue;
     const low=classification.status==='LOW_CONFIDENCE'||
       (l.confidence!==null&&l.confidence<0.90)||parsed.uncertain;
     const status:LandFactStatus=parsed.masked?'MASKED':low?'LOW_CONFIDENCE':'CANDIDATE';
     collections.get(rule.key)!.push({value:parsed.value,status,evidence:source});
   }
 }
 const facts:PrivateLandFact[]=rules.map(rule=>{
   const c=collections.get(rule.key)!;
   const values=new Set(c.filter(x=>x.value!==null).map(x=>x.value!));
   const uncertain=c.some(x=>x.status==='LOW_CONFIDENCE');
   const masked=c.some(x=>x.status==='MASKED');
   const status:LandFactStatus=
     values.size>1 || (masked&&values.size>0)?'CONFLICT':
     values.size===0?(masked?'MASKED':'ABSENT'):
     uncertain?'LOW_CONFIDENCE':'CANDIDATE';
   return {key:rule.key,status,value:
     status==='CANDIDATE'||status==='LOW_CONFIDENCE'?
       [...values][0]??null:null,
     evidence:c.map(x=>x.evidence)};
 });
 if(facts.some(x=>x.status==='CONFLICT'))issues.push('CONFLICTING_CRITICAL_FIELDS');
 if(facts.some(x=>x.status==='LOW_CONFIDENCE'))issues.push('LOW_CONFIDENCE_CRITICAL_FIELDS');
 return {
   kind:classification.kind,documentStatus:classification.status,facts,issues,
   access:'AUTHORIZED_SELLER_OR_REVIEWER_ONLY',
   mayAutofillUnreviewed:false,ownershipVerified:false,mayPublish:false
 };
}
