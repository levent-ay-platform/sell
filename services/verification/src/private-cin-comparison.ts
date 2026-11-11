/**
 * STRICT, PRIVATE comparison of explicitly labelled CIN text. Eight-digit title
 * references, arbitrary image numbers and unlabelled digits are NEVER a CIN.
 * OCR is untrusted: agreement cannot authenticate a card or verify ownership.
 * Never put the underlying numbers into a public response or log.
 */
import type {IngestedDocument} from './reader.ts';
import type {RecognizedLine} from './smart-fields.ts';

export type CinTextStatus='SAME_NUMBER_UNVERIFIED'|'NUMBER_DIFFERS_NEEDS_REVIEW'|
  'NOT_COMPARABLE'|'AMBIGUOUS_OR_LOW_CONFIDENCE';
export interface CinTextEvidence {
  readonly page:number;
  readonly source:RecognizedLine['source'];
  readonly confidencePercent:number|null;
}
export interface PrivateCinComparison {
  readonly status:CinTextStatus;
  readonly certificateEvidence:CinTextEvidence|null;
  readonly identityEvidence:CinTextEvidence|null;
  readonly mayVerifyIdentity:false;
}
const LABEL=/^(?:cin|n[°ºo.]?\s*(?:de\s+)?cin|num[eé]ro\s*(?:de\s+)?cin|carte\s+(?:nationale\s+)?d.identit[eé]\s+n[°ºo.]?|رقم\s+بطاقة\s+التعريف(?:\s+الوطنية)?|بطاقة\s+التعريف\s+الوطنية\s+(?:رقم|عدد))\s*[:：#\-–]?\s*([0-9\u0660-\u0669\u06f0-\u06f9]{8})\s*$/iu;
function asciiDigits(s:string):string {
  return s.replace(/[\u0660-\u0669\u06f0-\u06f9]/g,d=>{
    const n=d.charCodeAt(0);
    return String(n>=0x6f0?n-0x6f0:n-0x660);
  });
}
interface Match {number:string;evidence:CinTextEvidence;confidence:number|null;}
function collect(doc:IngestedDocument):Match[] {
  const out:Match[]=[];
  for(const page of doc.pages)for(const line of page.lines){
    if(line.text.length>140)continue;
    const t=line.text.normalize('NFKC').replace(/[\u200e\u200f\u202a-\u202e\u2066-\u2069]/g,'').trim();
    const m=LABEL.exec(t);
    if(!m)continue;
    out.push({number:asciiDigits(m[1]!),confidence:line.confidence,
      evidence:{page:line.page,source:line.source,
        confidencePercent:line.confidence===null?null:Math.round(line.confidence*100)}});
  }
  // Input is bounded by the OCR reader. Truncation can conceal a conflicting
  // 31st labelled number, so examine the full set before any agreement.
  return out;
}
function unique(items:Match[]):Match|null {
  if(!items.length||new Set(items.map(x=>x.number)).size!==1)return null;
  return items.reduce((a,b)=>(a.confidence??1)<(b.confidence??1)?a:b);
}
/** Full CIN digits NEVER appear in the returned report. */
export function compareCertificateIdentityCinText(cert:IngestedDocument,id:IngestedDocument):PrivateCinComparison {
  const ac=collect(cert),bc=collect(id),a=unique(ac),b=unique(bc);
  const uncertainImage=[...cert.pages,...id.pages].some(p=>(p.qualityWarnings?.length??0)>0);
  const ambiguous=uncertainImage||(ac.length>0&&!a)||(bc.length>0&&!b)||
    (a?.confidence!==null&&a?.confidence!==undefined&&a.confidence<0.90)||
    (b?.confidence!==null&&b?.confidence!==undefined&&b.confidence<0.90);
  const status:CinTextStatus=ambiguous?'AMBIGUOUS_OR_LOW_CONFIDENCE':
    !a||!b?'NOT_COMPARABLE':
    a.number===b.number?'SAME_NUMBER_UNVERIFIED':'NUMBER_DIFFERS_NEEDS_REVIEW';
  return {status,certificateEvidence:a?.evidence??null,identityEvidence:b?.evidence??null,
    mayVerifyIdentity:false};
}
