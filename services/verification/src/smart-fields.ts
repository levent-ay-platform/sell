/**
 * High-integrity, low-latency *post-OCR* field extraction for Tunisia CPF papers.
 * Input OCR text/confidence is UNTRUSTED. Never yields ownership approval.
 * No generative correction of names/title numbers; ambiguous values stay unresolved.
 */
export type FieldName =
  | 'registryReference' | 'areaSquareMetres' | 'ownershipShareUnits'
  | 'ownershipTotalUnits' | 'parcelCount' | 'parcelNumber'
  | 'documentPage' | 'documentPageCount' | 'certificateIssuedAt' | 'lastRegistryUpdatedAt';
export interface RecognizedLine {
  text: string;
  page: number;
  confidence: number | null; // OCR confidence 0..1; null for native PDF or unavailable.
  source: 'pdf-text' | 'ocr-ar' | 'ocr-fr';
}
export interface FieldEvidence {
  value: string | number;
  page: number;
  lineIndex: number;
  confidence: number | null;
  source: RecognizedLine['source'];
}
export interface ExtractedField {
  value: string | number | null;
  status: 'CANDIDATE' | 'LOW_CONFIDENCE' | 'CONFLICT' | 'ABSENT';
  evidence: readonly FieldEvidence[];
}
export interface SmartExtraction {
  fields: Readonly<Record<FieldName, ExtractedField>>;
  documentKind: 'CPF_OWNERSHIP_CERTIFICATE' | 'CPF_COOWNERSHIP_CERTIFICATE' | 'UNKNOWN';
  missingPages: readonly number[];
  issues: readonly ('UNREADABLE_PAGE_COUNT' | 'INCONSISTENT_PAGE_COUNT' | 'LOW_CONFIDENCE' | 'CONFLICTING_FIELDS')[];
  mayUseForAutomaticApproval: false;
}
const FIELDS: readonly FieldName[] = [
  'registryReference','areaSquareMetres','ownershipShareUnits','ownershipTotalUnits',
  'parcelCount','parcelNumber','documentPage','documentPageCount',
  'certificateIssuedAt','lastRegistryUpdatedAt'
];
function normalize(s:string):string {
  return s.normalize('NFKC')
    .replace(/[٠-٩]/g,c=>String(c.charCodeAt(0)-0x660))
    .replace(/[۰-۹]/g,c=>String(c.charCodeAt(0)-0x6f0))
    .replace(/[\u064B-\u065F\u0670\u0640\u200e\u200f\u202a-\u202e\u2066-\u2069]/g,'')
    .replace(/[إأآ]/g,'ا').replace(/ى/g,'ي')
    .replace(/[ \t]+/g,' ').trim();
}
function parseDate(s:string):string|undefined {
  const m=s.match(/^(\d{4})[\/-](\d{1,2})[\/-](\d{1,2})$/);
  if(!m) return undefined;
  const [year,month,day]=m.slice(1).map(Number);
  const date=new Date(Date.UTC(year,month-1,day));
  if(year<1900 || year>2100 || date.getUTCFullYear()!==year || date.getUTCMonth()!==month-1 || date.getUTCDate()!==day) return undefined;
  return `${year}-${String(month).padStart(2,'0')}-${String(day).padStart(2,'0')}`;
}
function extractLineFields(text:string): Array<[FieldName,string|number]> {
  const out:Array<[FieldName,string|number]>=[];
  const add=(key:FieldName,regex:RegExp,kind:'integer'|'area'|'string'|'date'='string')=>{
    const m=text.match(regex);if(!m?.[1])return;
    const raw=m[1];
    const value=kind==='date'?parseDate(raw):kind==='area'?Number(raw.replace(',','.')):
      kind==='integer'?Number(raw):raw;
    if(value===undefined || value==='' || (typeof value==='number' && (!Number.isFinite(value)||value<=0||value>1e12)))return;
    out.push([key,value]);
  };
  add('registryReference',/(?:معر?ف\s*(?:ال)?رسم\s*العقاري|(?:titre\s*foncier|n[°o]\s*(?:du\s*)?titre))\s*[:：#-]?\s*(\d{3,12})(?!\d)/iu);
  // Area must be anchored to 'المساحة / superficie', never from 'جزء'.
  add('areaSquareMetres',/(?:المساحه|المساحة|superficie)\s*[:：-]?\s*(\d{1,10}(?:[.,]\d{1,3})?)\s*(?:م(?:تر)?(?:\s*مربع|²|2)?|m²|m2)?(?=\s|$|[،؛])/iu,'area');
  add('ownershipShareUnits',/(?:موضوع\s*الملكيه|موضوع\s*الملكية|الحصه\s*المملوكه|الحصة\s*المملوكة)\s*[:：-]?\s*(\d{1,12})\s*(?:جزء|اجزاء)/u,'integer');
  add('ownershipShareUnits',/(\d{1,12})\s*(?:جزء|اجزاء)\s*(?:موضوع\s*الملكية|موضوع\s*الملكيه)/u,'integer');
  // Denominator only when EXPLICIT; 'التجزئة' alone is not enough.
  add('ownershipTotalUnits',/(?:مجموع\s*(?:اجزاء|الاجزاء)\s*الملكي[ةه]|(?:total\s*des\s*parts))\s*[:：-]?\s*(\d{1,12})/iu,'integer');
  add('parcelCount',/(?:مجموع\s*القطع|nombre\s*de\s*parcelles)\s*[:：-]?\s*(\d{1,4})(?!\d)/iu,'integer');
  add('parcelNumber',/(?:القطع[ة]?\s*عدد|num(?:ero|éro)?\s*de\s*parcelle)\s*[:：-]?\s*(\d{1,8})(?!\d)/iu);
  const pages=text.match(/(?:صفحه|صفحة)\s*[:：-]?\s*(\d{1,3})\s*(?:من|\/)\s*(\d{1,3})(?!\d)/u) ||
    text.match(/page\s*(\d{1,3})\s*(?:sur|of|\/)\s*(\d{1,3})(?!\d)/iu);
  if(pages){out.push(['documentPage',Number(pages[1])]);out.push(['documentPageCount',Number(pages[2])]);}
  else {
    // Tesseract bidi line order may produce "2 صفحة 1 من" from "صفحة 1 من 2".
    // Treat as untrusted OCR candidate; still subject to page consistency checks.
    const rtlBidi=text.match(/^(\d{1,3})\s+(?:صفحة|صفحه)\s+(\d{1,3})\s+من(?:\s|$)/u);
    if(rtlBidi){out.push(['documentPage',Number(rtlBidi[2])]);out.push(['documentPageCount',Number(rtlBidi[1])]);}
  }
  add('lastRegistryUpdatedAt',/(?:تاريخ\s*(?:اخر\s*)?تحيين|derni[eè]re\s*mise\s*[aà]\s*jour)\s*[:：-]?\s*(\d{4}[\/-]\d{1,2}[\/-]\d{1,2})/iu,'date');
  add('certificateIssuedAt',/(?:تاريخ\s*(?:اصدار|استخراج)\s*الشهاده|date\s*de\s*d[eé]livrance)\s*[:：-]?\s*(\d{4}[\/-]\d{1,2}[\/-]\d{1,2})/iu,'date');
  return out;
}
/** O(number of recognized characters), no network calls, no fuzzy guessing. */
export function extractCpfSmartFields(lines:readonly RecognizedLine[]):SmartExtraction {
  if(lines.length>2000 || lines.reduce((n,x)=>n+x.text.length,0)>150000)throw new RangeError('OCR input too large');
  const buckets=new Map<FieldName,FieldEvidence[]>(FIELDS.map(k=>[k,[]]));
  let certificate=false,coownership=false,issuerHeader=false,noisyTitle=false;
  for(let i=0;i<lines.length;i++){
    const l=lines[i];
    if(!Number.isInteger(l.page)||l.page<1||l.page>100 ||
      (l.confidence!==null && (!Number.isFinite(l.confidence)||l.confidence<0||l.confidence>1)))
      throw new TypeError('Invalid OCR line metadata');
    const normalized=normalize(l.text);
    if(/شهاده\s*(?:اشتراك\s*في\s*)?ملكيه|شهادة\s*(?:اشتراك\s*في\s*)?ملكية|certificat\s*de\s*(?:co)?propri[eé]t[eé]/iu.test(normalized))certificate=true;
    if(/ادار[ةه]\s+الملكي[ةه]\s+العقاري[ةه]/u.test(normalized))issuerHeader=true;
    if(/شهعاد[ةه]\s+ملكي[ةه]/u.test(normalized))noisyTitle=true;
    if(/اشتراك\s*في\s*الملكيه|copropri[eé]t[eé]/iu.test(normalized))coownership=true;
    for(const [key,value] of extractLineFields(normalized)){
      buckets.get(key)!.push({value,page:l.page,lineIndex:i,confidence:l.confidence,source:l.source});
    }
  }
  // OCR can distort a paper heading; recognize as a non-authoritative candidate
  // only if the CPF administration heading was independently read too.
  const guessedHeading=!certificate && issuerHeader && noisyTitle;
  if(guessedHeading)certificate=true;
  const fields={} as Record<FieldName,ExtractedField>;
  let low=false,conflict=false;
  for(const field of FIELDS){
    const evidence=buckets.get(field)!;
    const values=new Set(evidence.map(e=>String(e.value)));
    const hasConflict=values.size>1 && field!=='documentPage'; // Each page has a distinct page number.
    const hasLow=evidence.some(e=>e.confidence!==null && e.confidence<0.9);
    fields[field]={value:values.size===1?evidence[0].value:null,
      status:hasConflict?'CONFLICT':!evidence.length?'ABSENT':hasLow?'LOW_CONFIDENCE':'CANDIDATE',evidence};
    if(hasConflict) conflict=true;if(hasLow) low=true;
  }
  const declared=fields.documentPageCount;
  const pageCount=declared.status==='CANDIDATE' || declared.status==='LOW_CONFIDENCE' ? Number(declared.value):null;
  const pages=fields.documentPage.evidence.map(e=>Number(e.value));
  const pageNumbers=new Set(pages);
  const pageByPhysicalPage=new Map<number,Set<number>>();
  for(const e of fields.documentPage.evidence){
    if(!pageByPhysicalPage.has(e.page))pageByPhysicalPage.set(e.page,new Set());
    pageByPhysicalPage.get(e.page)!.add(Number(e.value));
  }
  const inconsistent=pages.some(n=>n<1 || (pageCount!==null && n>pageCount)) ||
    fields.documentPage.evidence.some(e=>e.value!==e.page) ||
    declared.status==='CONFLICT' || Array.from(pageByPhysicalPage.values()).some(set=>set.size>1);
  // Only claim missing pages if the document actually declares a plausible count.
  const missingPages=pageCount!==null && Number.isSafeInteger(pageCount) && pageCount>=1 && pageCount<=100 ?
    Array.from({length:pageCount},(_,i)=>i+1).filter(p=>!pageNumbers.has(p)):[];
  const issues:SmartExtraction['issues'][number][]=[];
  if(pageCount===null || !Number.isSafeInteger(pageCount) || pageCount<1 || pageCount>100)issues.push('UNREADABLE_PAGE_COUNT');
  if(inconsistent)issues.push('INCONSISTENT_PAGE_COUNT');
  if(low || guessedHeading)issues.push('LOW_CONFIDENCE');
  if(conflict)issues.push('CONFLICTING_FIELDS');
  return {fields,documentKind:!certificate?'UNKNOWN':coownership?'CPF_COOWNERSHIP_CERTIFICATE':'CPF_OWNERSHIP_CERTIFICATE',missingPages,issues,
    mayUseForAutomaticApproval:false};
}
