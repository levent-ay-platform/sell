import {extractCpfSmartFields} from './smart-fields.ts';
import type {RecognizedLine, SmartExtraction} from './smart-fields.ts';
import {classifyLandPaper} from './document-training-v2.ts';
import type {DocumentTrainingCandidate} from './document-training-v2.ts';
import {extractPrivateLandDocumentFacts} from './document-family-fields-v2.ts';
import type {PrivateLandFacts} from './document-family-fields-v2.ts';
import {assessPrivateEvidenceReadiness} from './evidence-readiness-v2.ts';
import type {PrivateEvidenceReadiness} from './evidence-readiness-v2.ts';
import {locateOwnerTableFromWords} from './owner-table-layout-v2.ts';
import type {SpatialPage, SpatialWord, SpatialTableCandidate} from './owner-table-layout-v2.ts';
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
  /** Only generated on securely rasterized/decoded OCR pages. */
  privateSpatialPage?:SpatialPage;
  spatialWordsTruncated?:boolean;
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
  /** Type-specific, sourced values requiring human confirmation; PRIVATE ONLY. */
  privateFamilyFields:PrivateLandFacts;
  /** Heuristic internal progress, not a public seller-trust or ownership badge. */
  privateEvidenceReadiness:PrivateEvidenceReadiness;
  /** Private, unreviewed candidates; never expose through a public listing. */
  ownerTableLayout:SpatialTableCandidate|null;
  pagesReceived:number;
  extractionMethods:readonly ('pdf-text'|'ocr')[];
  qualityReviewPages:readonly number[];
  requiresIndependentOwnershipReview:true;
  mayPublish:false;
}
const QUALITY_WARNINGS=new Set<QualityWarning>(['LOW_RESOLUTION','LOW_CONTRAST','POSSIBLE_BLUR','REVIEW_ORIENTATION']);
const isObject=(x:unknown):x is Record<string,unknown> =>
  typeof x==='object' && x!==null && !Array.isArray(x);
const validRasterBox=(x:unknown):x is {left:number;top:number;right:number;bottom:number}=>{
  if(!isObject(x))return false;
  const {left,top,right,bottom}=x;
  return [left,top,right,bottom].every(n=>typeof n==='number'&&
    Number.isSafeInteger(n)&&n>=0&&n<=50000)&&
    (right as number)>(left as number)&&(bottom as number)>(top as number);
};

/**
 * A parsed private OCR page stores spatial boxes in a DIFFERENT schema from
 * raw Tesseract output. Legacy text-only consumers must not reparse that
 * in-memory layout as if it were untrusted incoming worker metadata.
 * Preserve source lines, page methods and capture-quality warnings only.
 */
export function asTextOnlyOcrDocument(input:IngestedDocument):IngestedDocument {
  return {format:input.format,mayApproveOwnership:false,
    pages:input.pages.map(p=>({
      page:p.page,method:p.method,lines:p.lines,
      ...(p.qualityWarnings!==undefined?{qualityWarnings:p.qualityWarnings}:{})
    }))};
}

export function parseOcrWorkerOutput(input:unknown):IngestedDocument {
  if(!isObject(input) || !['pdf','image'].includes(String(input.format)) ||
     input.mayApproveOwnership!==false || !Array.isArray(input.pages) ||
     input.pages.length<1 || input.pages.length>8) throw new TypeError('INVALID_OCR_PAYLOAD');
  const seen=new Set<number>();
  let count=0;
  let spatialCount=0;
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
    const spatialMetadata=[
      item.ocrWords,item.pageImageWidth,item.pageImageHeight,
      item.spatialWordsTruncated
    ];
    const hasSpatial=spatialMetadata.some(x=>x!==undefined);
    if(hasSpatial&&(
      item.method!=='ocr'||!Number.isSafeInteger(item.pageImageWidth)||
      !Number.isSafeInteger(item.pageImageHeight)||
      Number(item.pageImageWidth)<200||Number(item.pageImageWidth)>10000||
      Number(item.pageImageHeight)<300||Number(item.pageImageHeight)>10000||
      !Array.isArray(item.ocrWords)||item.ocrWords.length>1000||
      typeof item.spatialWordsTruncated!=='boolean'))
      throw new TypeError('INVALID_SPATIAL_OCR_METADATA');
    const spatialWords:SpatialWord[]=[];
    if(hasSpatial){
      for(const w of item.ocrWords as unknown[]){
        if(!isObject(w)||w.page!==page||typeof w.text!=='string'||
          w.text.length<1||w.text.length>120||
          typeof w.confidence!=='number'||!Number.isFinite(w.confidence)||
          w.confidence<0||w.confidence>1||!validRasterBox(w.bbox)||
          w.bbox.right>Number(item.pageImageWidth)||
          w.bbox.bottom>Number(item.pageImageHeight))
          throw new TypeError('INVALID_SPATIAL_OCR_WORD');
        spatialWords.push({page,text:w.text,confidence:w.confidence,
          bbox:{left:w.bbox.left,top:w.bbox.top,
                right:w.bbox.right,bottom:w.bbox.bottom}});
      }
      spatialCount+=spatialWords.length;
      if(spatialCount>2500)throw new RangeError('SPATIAL_OCR_TOO_LARGE');
    }
    const lines:RecognizedLine[]=[];
    for(const line of item.lines){
      if(!isObject(line) || typeof line.text!=='string' ||
         line.text.length>1200 || line.page!==page ||
         (line.confidence!==null && (typeof line.confidence!=='number' ||
          !Number.isFinite(line.confidence) || line.confidence<0 || line.confidence>1)) ||
         !['pdf-text','ocr-ar','ocr-fr'].includes(String(line.source)) ||
         (item.method==='pdf-text' && line.source!=='pdf-text') ||
         (item.method==='ocr' && line.source==='pdf-text') ||
         (line.bbox!==undefined && (item.method!=='ocr'||
           !validRasterBox(line.bbox)))) throw new TypeError('INVALID_OCR_LINE');
      count+=line.text.length;
      if(count>100000) throw new RangeError('OCR_TEXT_TOO_LARGE');
      lines.push({text:line.text,page,confidence:line.confidence as number|null,
                  source:line.source as RecognizedLine['source'],
                  ...(line.bbox!==undefined?{bbox:{
                    left:(line.bbox as {left:number}).left,
                    top:(line.bbox as {top:number}).top,
                    right:(line.bbox as {right:number}).right,
                    bottom:(line.bbox as {bottom:number}).bottom}}:{})});
    }
    pages.push({page,method:item.method as IngestedPage['method'],lines,
      ...(hasSpatial?{
        privateSpatialPage:{page,width:Number(item.pageImageWidth),
          height:Number(item.pageImageHeight),words:spatialWords},
        spatialWordsTruncated:item.spatialWordsTruncated as boolean
      }:{}),
      ...(item.qualityWarnings!==undefined ?
        {qualityWarnings:item.qualityWarnings as QualityWarning[]} : {})});
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
  const privateFamilyFields=extractPrivateLandDocumentFacts(lines,documentFamilyCandidate);
  const privateEvidenceReadiness=assessPrivateEvidenceReadiness(documentFamilyCandidate,privateFamilyFields);
  // Table association is safe only if EVERY page has complete original
  // raster-page word geometry. Partial/ROI/native-PDF words never count.
  const spatialReady=parsed.pages.every(pg=>pg.privateSpatialPage!==undefined&&
    !pg.spatialWordsTruncated&&pg.privateSpatialPage.words.length>0);
  const ownerTableLayout=spatialReady?
    locateOwnerTableFromWords(parsed.pages.map(pg=>pg.privateSpatialPage!)):null;
  return {extraction,documentFamilyCandidate,privateFamilyFields,privateEvidenceReadiness,ownerTableLayout,
    pagesReceived:parsed.pages.length,
    extractionMethods:parsed.pages.map(pg=>pg.method),
    qualityReviewPages:parsed.pages.filter(pg=>pg.qualityWarnings?.length).map(pg=>pg.page),
    requiresIndependentOwnershipReview:true,mayPublish:false};
}
