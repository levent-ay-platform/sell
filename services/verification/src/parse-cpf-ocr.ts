import type {UntrustedDocumentExtraction} from './triage.ts';
import {extractCpfSmartFields} from './smart-fields.ts';
import type {FieldName, SmartExtraction, RecognizedLine} from './smart-fields.ts';

/**
 * Compatibility API: consumes already OCR-extracted text (NOT an image/PDF).
 * All ambiguous or low-confidence fields are excluded; source and signer checks
 * must occur independently before any legal decision.
 */
export function parseCpfOcrText(input: string): UntrustedDocumentExtraction {
  if (typeof input !== 'string' || input.length > 50000) throw new TypeError('Invalid OCR text size');
  const lines:RecognizedLine[]=input.split(/\r?\n/).filter(Boolean).map(text=>({
    text,page:1,confidence:null,source:'ocr-ar'
  }));
  const parsed=extractCpfSmartFields(lines);
  const trustedCandidate=(key:FieldName):string|number|undefined=>{
    const field=parsed.fields[key];
    return field.status==='CANDIDATE' ? field.value??undefined : undefined;
  };
  const number=(key:FieldName):number|undefined=>{
    const x=trustedCandidate(key);return typeof x==='number'?x:undefined;
  };
  const string=(key:FieldName):string|undefined=>{
    const x=trustedCandidate(key);return typeof x==='string'?x:undefined;
  };
  const page=number('documentPage');
  const count=number('documentPageCount');
  return {
    kind:parsed.documentKind==='UNKNOWN'?'OTHER_OR_UNKNOWN':parsed.documentKind,
    format:'PAPER_SCAN',hasCev:false,
    extractionWarnings:parsed.issues,
    declaredPageCount:count??null,
    receivedPageNumbers:page===undefined?[]:[page],
    extracted:{
      ...(string('registryReference')?{registryReference:string('registryReference')}:{}),
      ...(number('areaSquareMetres')?{areaSquareMetres:number('areaSquareMetres')}:{}),
      ...(number('ownershipShareUnits')?{ownershipShareUnits:number('ownershipShareUnits')}:{}),
      ...(number('ownershipTotalUnits')?{ownershipTotalUnits:number('ownershipTotalUnits')}:{}),
      ...(number('parcelCount')?{parcelCount:number('parcelCount')}:{}),
      ...(string('parcelNumber')?{parcelNumber:string('parcelNumber')}:{}),
      ...(string('lastRegistryUpdatedAt')?{lastRegistryUpdatedAt:string('lastRegistryUpdatedAt')}:{}),
      ...(string('certificateIssuedAt')?{certificateIssuedAt:string('certificateIssuedAt')}:{}),
    }
  };
}

/** Preferred API for multi-page OCR/native-PDF lines, with per-field evidence. */
export {extractCpfSmartFields};
export type {SmartExtraction,RecognizedLine};
