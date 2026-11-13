import {extractCpfSmartFields} from './smart-fields.ts';
import type {RecognizedLine, SmartExtraction} from './smart-fields.ts';
import {classifyLandPaper} from './document-training-v2.ts';
import type {DocumentTrainingCandidate} from './document-training-v2.ts';
import type {QualityWarning} from './capture.ts';

/**
 * Isolated OCR/PDF worker boundary. Text/confidence are UNTRUSTED.
 * Never verifies current ownership or permits publication.
 */
export interface IngestedPage {
  page:number;
  method:'pdf-text'|'ocr';
  lines:readonly RecognizedLine[];
  qualityWarnings?:readonly QualityWarning[];
}
export interface IngestedDocument {
  format:'pdf'|'image';
  pages:readonly IngestedPage[];
  mayApproveOwnership:false;
}
export interface ReadDocumentResult {
  extraction:SmartExtraction;
  /** Non-authoritative, zero-PII document-family hint for later private review. */
  documentFamilyCandidate:DocumentTrainingCandidate;
  pagesReceived:number;
  extractionMethods:readonly ('pdf-text'|'ocr')[];
  qualityReviewPages:readonly number[];
  requiresIndependentOwnershipReview:true;
  mayPublish:false;
}
const QUALITY_WARNINGS=new Set<QualityWarning>(['LOW_RESOLUTION','LOW_CONTRAST','POSSIBLE_BLUR','REVIEW_ORIENTATION']);
const isObject=(x:unknown):x is Record<string,unknown> =>
  typeof x==='object' && x!==null && !Array.isArray(x);

export function parseOcrWorkerOutput(input:unknown):IngestedDocument {
  if(!isObject(input) || !['pdf','image'].includes(String(input.format)) ||
     input.mayApproveOwnership!==false || !Array.isArray(input.pages) ||
     input.pages.length<1 || input.pages.length>8) throw new TypeError('INVALID_OCR_PAYLOAD');
  const seen=new Set<number>();
  let count=0;
  const pages:IngestedPage[]=[];
  for(const item of input.pages){
    if(!isObject(item) || !Number.isSafeInteger(item.page) ||
       Number(item.page)<1 || Number(item.page)>8 || seen.has(Number(item.page)) ||
       !['pdf-text','ocr'].includes(String(item.method)) ||
       !Array.isArray(item.lines) || item.lines.length>600) throw new TypeError('INVALID_OCR_PAGE');
    if(item.qualityWarnings!==undefined &&
       (!Array.isArray(item.qualityWarnings)||item.qualityWarnings.length>4||
         item.qualityWarnings.some((x:unknown)=>!QUALITY_WARNINGS.has(x as QualityWarning))))
       throw new TypeError('INVALID_OCR_QUALITY');
    const page=Number(item.page);seen.add(page);
    const lines:RecognizedLine[]=[];
    for(const line of item.lines){
      if(!isObject(line) || typeof line.text!=='string' ||
         line.text.length>1200 || line.page!==page ||
         (line.confidence!==null && (typeof line.confidence!=='number' ||
          !Number.isFinite(line.confidence) || line.confidence<0 || line.confidence>1)) ||
         !['pdf-text','ocr-ar','ocr-fr'].includes(String(line.source)) ||
         (item.method==='pdf-text' && line.source!=='pdf-text') ||
         (item.method==='ocr' && line.source==='pdf-text')) throw new TypeError('INVALID_OCR_LINE');
      count+=line.text.length;
      if(count>100000) throw new RangeError('OCR_TEXT_TOO_LARGE');
      lines.push({text:line.text,page,confidence:line.confidence as number|null,
                  source:line.source as RecognizedLine['source']});
    }
    pages.push({page,method:item.method as IngestedPage['method'],lines,
      ...(item.qualityWarnings!==undefined ? {qualityWarnings:item.qualityWarnings as QualityWarning[]} : {})});
  }
  // Never silently lose scanned PDF pages between worker and domain parser.
  if(pages.some((_pg,i)=>!seen.has(i+1))) throw new TypeError('OCR_PAGE_GAP');
  pages.sort((a,b)=>a.page-b.page);
  return {format:input.format as IngestedDocument['format'],pages,mayApproveOwnership:false};
}
export function readUntrustedOcrDocument(payload:unknown):ReadDocumentResult {
  const parsed=parseOcrWorkerOutput(payload);
  const lines=parsed.pages.flatMap(pg=>pg.lines);
  const extraction=extractCpfSmartFields(lines);
  const documentFamilyCandidate=classifyLandPaper(lines);
  return {extraction,documentFamilyCandidate,pagesReceived:parsed.pages.length,
    extractionMethods:parsed.pages.map(pg=>pg.method),
    qualityReviewPages:parsed.pages.filter(pg=>pg.qualityWarnings?.length).map(pg=>pg.page),
    requiresIndependentOwnershipReview:true,mayPublish:false};
}
