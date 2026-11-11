/**
 * Preliminary, owner/reviewer-only OCR text comparison for Tunisia.
 * Does not authenticate a CIN, prove a property right or replace current CPF
 * title lookup and qualified human review. Neither input may be published.
 *
 * This deliberately rejects fuzzy name matching, transliteration and missing
 * names rather than claiming a legal identity match from imperfect OCR.
 */
import {parseOcrWorkerOutput} from './reader.ts';
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
  if(raw.length>100||/\d/.test(raw)||/[\u0000-\u001f]/.test(raw))return null;
  const trimmed=raw.trim().replace(/^[\s:：–\-]+/u,'').replace(/\s+$/u,'');
  return canonical(trimmed)?trimmed:null;
}
function collect(doc:IngestedDocument, kind:'CERTIFICATE'|'IDENTITY'):CandidateName[] {
  const out:CandidateName[]=[];
  const lastname:CandidateName[]=[];
  const firstname:CandidateName[]=[];
  for(const p of doc.pages)for(const line of p.lines){
    const t=line.text.trim().normalize('NFKC');
    if(t.length>180)continue;
    const re=kind==='CERTIFICATE'?
      /^(?:propri[eé]taire|titulaire\s+du\s+titre|اسم\s+(?:و\s*لقب\s+)?المالك|المالك)\s*[:：\-–]\s*(.{7,95})$/iu:
      /^(?:nom\s+et\s+pr[eé]nom|nom\s+complet|الاسم\s+واللقب|اللقب\s+والاسم)\s*[:：\-–]\s*(.{7,95})$/iu;
    const m=re.exec(t);
    if(m){
      const value=parsedName(m[1]!);
      if(value)out.push({value,page:line.page,source:line.source,confidence:line.confidence});
    }
    if(kind==='IDENTITY'){
      const last=/^nom\s*[:：\-–]\s*(.{2,60})$/iu.exec(t);
      const first=/^pr[eé]nom\s*[:：\-–]\s*(.{2,60})$/iu.exec(t);
      const add=(hit:RegExpExecArray|null,into:CandidateName[])=>{
        if(hit&&hit[1]&&/^[\p{Script=Latin}\p{M}\s'-]{2,60}$/u.test(hit[1]))
          into.push({value:hit[1].trim(),page:line.page,source:line.source,confidence:line.confidence});
      };
      add(last,lastname);add(first,firstname);
    }
  }
  // Only assemble separate French ID components if each has exactly one value.
  if(kind==='IDENTITY'&&lastname.length===1&&firstname.length===1&&out.length===0) {
    const value=parsedName(firstname[0]!.value+' '+lastname[0]!.value);
    if(value)out.push({value,page:firstname[0]!.page,source:firstname[0]!.source,
      confidence:firstname[0]!.confidence===null||lastname[0]!.confidence===null?null:
        Math.min(firstname[0]!.confidence!,lastname[0]!.confidence!)});
  }
  return out.slice(0,12);
}
function unique(candidates:CandidateName[]):CandidateName|null {
  if(!candidates.length)return null;
  const normalized=new Set(candidates.map(x=>canonical(x.value)));
  if(normalized.size!==1||normalized.has(null))return null;
  return candidates.reduce((a,b)=> (a.confidence??1)<(b.confidence??1)?a:b);
}
/** Source must already have been authenticated, malware-scanned and privately decrypted. */
export function comparePrivateCertificateIdentityOcr(certInput:unknown,idInput:unknown):PrivateDocumentComparison {
  const cert=parseOcrWorkerOutput(certInput),identity=parseOcrWorkerOutput(idInput);
  const a=unique(collect(cert,'CERTIFICATE'));
  const b=unique(collect(identity,'IDENTITY'));
  const issues:string[]=[];
  let assessment:ComparisonAssessment='INSUFFICIENT_OR_AMBIGUOUS';
  if(!a||!b)issues.push('MISSING_OR_AMBIGUOUS_LABELED_NAME');
  else if((a.confidence!==null&&a.confidence<0.90)||(b.confidence!==null&&b.confidence<0.90))
    issues.push('LOW_OCR_CONFIDENCE');
  else if(canonical(a.value)===canonical(b.value))
    assessment='PRELIMINARY_TEXT_AGREEMENT';
  else {
    assessment='TEXT_DIFFERENCE_NEEDS_REVIEW';
    issues.push('NAME_TEXT_DIFFERS_OR_SCRIPT_MISMATCH');
  }
  if(assessment==='PRELIMINARY_TEXT_AGREEMENT')issues.push('OFFICIAL_CPF_AND_DOCUMENT_AUTHENTICITY_NOT_CHECKED');
  return {assessment,certificateName:a,identityName:b,reasons:issues,
    ownershipVerified:false,identityVerified:false,mayPublish:false,requiresHumanReview:true};
}
