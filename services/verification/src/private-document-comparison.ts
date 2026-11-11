/**
 * Preliminary, owner/reviewer-only OCR text comparison for Tunisia.
 * Does not authenticate a CIN, prove a property right or replace current CPF
 * title lookup and qualified human review. Neither input may be published.
 *
 * This deliberately rejects fuzzy name matching, transliteration and missing
 * names rather than claiming a legal identity match from imperfect OCR.
 */
import {parseOcrWorkerOutput} from './reader.ts';
import {extractCpfSmartFields} from './smart-fields.ts';
import type {IngestedDocument} from './reader.ts';
import type {RecognizedLine} from './smart-fields.ts';

export type ComparisonAssessment =
  'PRELIMINARY_TEXT_AGREEMENT' | 'TEXT_DIFFERENCE_NEEDS_REVIEW' |
  'INSUFFICIENT_OR_AMBIGUOUS';
export interface CandidateName {
  readonly value:string; // PRIVATE PII; never return in a public listing.
  readonly page:number;
  readonly source:RecognizedLine['source'];
  readonly confidence:number|null;
}
export interface PrivateDocumentComparison {
  readonly assessment:ComparisonAssessment;
  readonly ownershipVerified:false;
  readonly identityVerified:false;
  readonly mayPublish:false;
  readonly requiresHumanReview:true;
  readonly certificateName:CandidateName|null;
  readonly identityName:CandidateName|null;
  readonly reasons:readonly string[];
}
// If a title mentions other rightsholders or undivided ownership, matching
// one person's printed name is NOT an agreement about the whole title.
// OCR evidence is advisory; always route such cases to human review.
const SHARED_TITLE_CUE=/(?:\bco[\s-]?propri[eé]taire(?:s)?\b|\bpropri[eé]taires\s*[:：\-–]|\bco[\s-]?titulaire(?:s)?\b|\bindivision\b|الملاك|المالكون|المالكين|مالكان|الشياع|مشاع|الملكية\s+المشتركة)/iu;
const MARKS=/\p{M}/gu;
function canonical(name:string):string|null {
  const input=name.normalize('NFKD').replace(MARKS,'').replace(/[إأآ]/g,'ا').replace(/ى/g,'ي')
    .replace(/[\u200e\u200f\u202a-\u202e\u2066-\u2069]/g,'').toLocaleLowerCase('und')
    .replace(/[^\p{L}\s'-]/gu,' ').replace(/[-'\u2019]/g,' ').replace(/\s+/g,' ').trim();
  if(input.length<7 || input.length>90)return null;
  const tokens=input.split(' ').filter(Boolean);
  if(tokens.length<2||tokens.length>8||tokens.some(t=>t.length<2))return null;
  const ar=/[\p{Script=Arabic}]/u.test(input);
  const lat=/[\p{Script=Latin}]/u.test(input);
  if((ar&&lat)||(!ar&&!lat))return null;
  // Preserve token order: sorting could treat two different personal names
  // with the same words in a different order as an apparent agreement.
  // Safer to require review for inverted/reordered names.
  return tokens.join(' ');
}
function parsedName(raw:string):string|null {
  // Forbid digits, OCR control characters and field-value leakage.
  // OCR text is untrusted. Bidi overrides/zero-width formatting can make the
  // displayed identity look different from the value used in comparison.
  // Unicode digits or punctuation can also be silently discarded by canonical().
  // Reject them rather than showing a cosmetically "matching" owner name.
  if(raw.length>100||/[\p{N}\p{Cc}\p{Cf}]/u.test(raw))return null;
  const trimmed=raw.trim().replace(/^[\s:：–\-]+/u,'').replace(/\s+$/u,'');
  if(!/^[\p{L}\p{M}\p{Zs}'’\-]+$/u.test(trimmed))return null;
  return canonical(trimmed)?trimmed:null;
}
function collect(doc:IngestedDocument, kind:'CERTIFICATE'|'IDENTITY'):CandidateName[] {
  const out:CandidateName[]=[];
  const lastname:CandidateName[]=[];
  const firstname:CandidateName[]=[];
  let unreadableLabel=false;
  for(const p of doc.pages)for(const line of p.lines){
    const t=line.text.trim().normalize('NFKC');
    if(t.length>180)continue;
    const re=kind==='CERTIFICATE'?
      /^(?:propri[eé]taire|titulaire\s+du\s+titre|اسم\s+(?:و\s*لقب\s+)?المالك|المالك)\s*[:：\-–]\s*(.{7,95})$/iu:
      /^(?:nom\s+et\s+pr[eé]nom|nom\s+complet|الاسم\s+واللقب|اللقب\s+والاسم)\s*[:：\-–]\s*(.{7,95})$/iu;
    const m=re.exec(t);
    // Invalid/blank lines with a recognized owner label must not disappear
    // merely because another line on the same certificate has a valid name.
    const label=kind==='CERTIFICATE'?
      /^(?:propri[eé]taire|titulaire\s+du\s+titre|اسم\s+(?:و\s*لقب\s+)?المالك|المالك)\s*[:：\-–]/iu:
      /^(?:nom\s+et\s+pr[eé]nom|nom\s+complet|الاسم\s+واللقب|اللقب\s+والاسم)\s*[:：\-–]/iu;
    if(label.test(t)&&!m)unreadableLabel=true;
    if(m){
      const value=parsedName(m[1]!);
      if(value)out.push({value,page:line.page,source:line.source,confidence:line.confidence});
      else unreadableLabel=true;
    }
    if(kind==='IDENTITY'){
      const last=/^nom\s*[:：\-–]\s*(.{2,60})$/iu.exec(t);
      const first=/^pr[eé]nom\s*[:：\-–]\s*(.{2,60})$/iu.exec(t);
      const add=(hit:RegExpExecArray|null,into:CandidateName[])=>{
        if(hit&&hit[1]&&/^[\p{Script=Latin}\p{M}\s'-]{2,60}$/u.test(hit[1]))
          into.push({value:hit[1].trim(),page:line.page,source:line.source,confidence:line.confidence});
      };
      if((/^nom\s*[:：\-–]/iu.test(t)&&!last)||
         (/^pr[eé]nom\s*[:：\-–]/iu.test(t)&&!first))
        unreadableLabel=true;
      add(last,lastname);add(first,firstname);
      if((last&&!lastname.some(x=>x.page===line.page&&x.value===last[1]?.trim()))||
         (first&&!firstname.some(x=>x.page===line.page&&x.value===first[1]?.trim())))
        unreadableLabel=true;
    }
  }
  if(unreadableLabel)return [];
  // A complete ID-name field and a separate Nom / Prénom pair are independent
  // OCR observations. Compare BOTH; never ignore a contradictory second field.
  // Partial or duplicated separate components are ambiguous, not corroboration.
  if(kind==='IDENTITY'&&(lastname.length>0||firstname.length>0)) {
    if(lastname.length!==1||firstname.length!==1)return [];
    const value=parsedName(firstname[0]!.value+' '+lastname[0]!.value);
    if(!value)return [];
    out.push({value,page:firstname[0]!.page,source:firstname[0]!.source,
      confidence:firstname[0]!.confidence===null||lastname[0]!.confidence===null?null:
        Math.min(firstname[0]!.confidence!,lastname[0]!.confidence!)});
  }
  // The reader already bounds input at 8 pages × 600 OCR lines. Truncating
  // candidates here could hide a contradictory 13th owner/name.
  return out;
}
function unique(candidates:CandidateName[]):CandidateName|null {
  if(!candidates.length)return null;
  const normalized=new Set(candidates.map(x=>canonical(x.value)));
  if(normalized.size!==1||normalized.has(null))return null;
  // Unknown confidence from an OCR source must never be discarded just
  // because a duplicate native-PDF or high-confidence line exists.
  const certainty=(x:CandidateName)=>x.confidence===null?
    (x.source==='pdf-text'?1:-1):x.confidence;
  return candidates.reduce((a,b)=>certainty(a)<certainty(b)?a:b);
}
function uncertainNameConfidence(x:CandidateName|null):boolean {
  return !!x&&((x.confidence===null&&x.source!=='pdf-text')||
    (x.confidence!==null&&x.confidence<0.90));
}
/** Source must already have been authenticated, malware-scanned and privately decrypted. */
export function comparePrivateCertificateIdentityOcr(certInput:unknown,idInput:unknown):PrivateDocumentComparison {
  const cert=parseOcrWorkerOutput(certInput),identity=parseOcrWorkerOutput(idInput);
  const a=unique(collect(cert,'CERTIFICATE'));
  const b=unique(collect(identity,'IDENTITY'));
  const sharedTitle=cert.pages.some(page=>page.lines.some(line=>
    SHARED_TITLE_CUE.test(line.text.normalize('NFKC'))));
  const issues:string[]=[];
  let assessment:ComparisonAssessment='INSUFFICIENT_OR_AMBIGUOUS';
  if(!a||!b)issues.push('MISSING_OR_AMBIGUOUS_LABELED_NAME');
  if(sharedTitle)issues.push('MULTIPLE_OWNERS_OR_SHARED_TITLE_NEEDS_REVIEW');
  // A title that explicitly says page 1/2 but only supplies page 1 can
  // omit additional owners on page 2. Never agree on a known partial title.
  const pageReview=extractCpfSmartFields(cert.pages.flatMap(p=>p.lines));
  if(pageReview.missingPages.length>0)issues.push('CERTIFICATE_PAGES_MISSING');
  if(pageReview.issues.includes('INCONSISTENT_PAGE_COUNT'))
    issues.push('CERTIFICATE_PAGE_NUMBER_INCONSISTENT');
  // An OCR worker may return a high-confidence name from an otherwise blurred,
  // low-resolution or badly oriented document; that is not a clean agreement.
  if([...cert.pages,...identity.pages].some(p=>(p.qualityWarnings?.length??0)>0))
    issues.push('SCAN_QUALITY_NEEDS_REVIEW');
  if(uncertainNameConfidence(a)||uncertainNameConfidence(b))
    issues.push('LOW_OCR_CONFIDENCE');
  if(issues.length===0&&a&&b) {
    if(canonical(a.value)===canonical(b.value))assessment='PRELIMINARY_TEXT_AGREEMENT';
    else {
      assessment='TEXT_DIFFERENCE_NEEDS_REVIEW';
      issues.push('NAME_TEXT_DIFFERS_OR_SCRIPT_MISMATCH');
    }
  }
  if(assessment==='PRELIMINARY_TEXT_AGREEMENT')issues.push('OFFICIAL_CPF_AND_DOCUMENT_AUTHENTICITY_NOT_CHECKED');
  return {assessment,certificateName:a,identityName:b,reasons:issues,
    ownershipVerified:false,identityVerified:false,mayPublish:false,requiresHumanReview:true};
}
