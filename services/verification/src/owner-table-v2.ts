/**
 * Smart Listing V2: private, layout-assisted CPF rights-table assembly.
 *
 * A horizontal paper table is NOT safely parsable from flattened OCR lines:
 * its columns must first be associated with an owner row by a private spatial
 * layout worker or a human reviewer. This module validates those labelled cell
 * observations, including rows continued on later pages. It never creates a
 * title, ownership or publication decision.
 *
 * All owner names/CIN are sensitive. Only call inside authorized private OCR;
 * never log the result, serialize it to public listings, or use real fixtures
 * in the public repository.
 */
import {balanceOwnershipParts} from './document-rights-v2.ts';
import type {RightsBalance,SourceValueState} from './document-rights-v2.ts';
import type {RecognizedLine} from './smart-fields.ts';

export type OwnerCellField=
  'name'|'cin'|'birthDate'|'rightsParts'|'filingReference'|
  'filingDate'|'filingOffice'|'filingVolume'|'filingNumber';
export type OwnerCellStatus=
  'CANDIDATE'|'LOW_CONFIDENCE'|'CONFLICT'|'MASKED'|
  'NOT_IN_SOURCE'|'UNREADABLE'|'NOT_OBSERVED';
export interface OwnerTableCellObservation {
  /** Printed owner sequence (1/1,1/2, etc.), NOT a fractional share. */
  readonly ordinal:number;
  readonly field:OwnerCellField;
  readonly page:number;
  readonly lineIndex:number;
  readonly source:RecognizedLine['source'];
  readonly confidence:number|null;
  /** Empty for masked, unprinted or unreadable cells. */
  readonly value:string|null;
  readonly state:SourceValueState;
}
export interface OwnerFieldProvenance {
  readonly page:number;
  readonly lineIndex:number;
  readonly source:RecognizedLine['source'];
  readonly confidence:number|null;
  readonly state:SourceValueState;
}
export interface OwnerResolvedField {
  readonly value:string|null;
  readonly status:OwnerCellStatus;
  readonly evidence:readonly OwnerFieldProvenance[];
}
export interface OwnerTableRow {
  readonly ordinal:number;
  readonly sourcePages:readonly number[];
  readonly fields:Readonly<Record<OwnerCellField,OwnerResolvedField>>;
  /** Only structured OCR candidate completeness; NOT legal confirmation. */
  readonly structurallyReadable:boolean;
}
export type OwnerTableIssue=
  'PAGE_SET_INCOMPLETE'|'OWNER_ROW_MISSING'|'OWNER_ROW_CONFLICT'|
  'OWNER_NAME_MISSING'|'OWNER_PARTS_MISSING'|'OWNER_LOW_CONFIDENCE'|
  'PARTS_BALANCE_NOT_CONFIRMED'|'OWNER_CIN_INVALID';
export interface OwnerTableAnalysis {
  readonly rows:readonly OwnerTableRow[];
  readonly declaredOwnerCount:number|null;
  readonly missingOwnerOrdinals:readonly number[];
  readonly missingPages:readonly number[];
  readonly rightsBalance:RightsBalance;
  readonly issues:readonly OwnerTableIssue[];
  readonly readyForHumanReview:boolean;
  readonly ownershipVerified:false;
  readonly mayPublish:false;
}
const FIELDS:readonly OwnerCellField[]=[
  'name','cin','birthDate','rightsParts','filingReference',
  'filingDate','filingOffice','filingVolume','filingNumber'
];
const VALID_STATES=new Set<SourceValueState>([
  'EXTRACTED','MASKED','NOT_IN_SOURCE','UNREADABLE'
]);
const DIGITS=(text:string)=>text.replace(/[٠-٩]/gu,c=>String(c.charCodeAt(0)-0x660))
  .replace(/[۰-۹]/gu,c=>String(c.charCodeAt(0)-0x6f0));
const REDACTED=(text:string)=>/^[*Xx█■▇\s]+$/u.test(text);
function validateCell(cell:OwnerTableCellObservation):void {
  if(!cell||!Number.isSafeInteger(cell.ordinal)||cell.ordinal<1||cell.ordinal>500||
    !FIELDS.includes(cell.field)||!Number.isSafeInteger(cell.page)||cell.page<1||
    cell.page>8||!Number.isSafeInteger(cell.lineIndex)||cell.lineIndex<0||
    !['ocr-ar','ocr-fr','pdf-text'].includes(cell.source)||
    (cell.confidence!==null&&(!Number.isFinite(cell.confidence)||
      cell.confidence<0||cell.confidence>1))||
    !VALID_STATES.has(cell.state)||
    (cell.state==='EXTRACTED' && (typeof cell.value!=='string'||
      !cell.value.trim()||cell.value.length>160||/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/u.test(cell.value)||
      REDACTED(cell.value)))||
    (cell.state!=='EXTRACTED'&&cell.value!==null))
    throw new TypeError('INVALID_OWNER_TABLE_CELL');
}
function resolve(cells:readonly OwnerTableCellObservation[]):OwnerResolvedField {
  const evidence=cells.map(c=>({page:c.page,lineIndex:c.lineIndex,
    source:c.source,confidence:c.confidence,state:c.state}));
  if(cells.length===0)return {value:null,status:'NOT_OBSERVED',evidence};
  const values=new Set(cells.filter(c=>c.state==='EXTRACTED').map(c=>c.value!.trim()));
  const otherStates=new Set(cells.filter(c=>c.state!=='EXTRACTED').map(c=>c.state));
  if(values.size>1||otherStates.size>1||(values.size>0&&otherStates.size>0))
    return {value:null,status:'CONFLICT',evidence};
  if(values.size===0){
    const s=Array.from(otherStates)[0];
    return {value:null,status:s==='MASKED'?'MASKED':s==='NOT_IN_SOURCE'?'NOT_IN_SOURCE':'UNREADABLE',evidence};
  }
  const value=Array.from(values)[0];
  const low=cells.some(c=>c.confidence!==null&&c.confidence<0.9);
  return {value,status:low?'LOW_CONFIDENCE':'CANDIDATE',evidence};
}

/**
 * Interpret a CPF owner-row label for its **ordinal**, never as share parts.
 * Labels have a first group and owner position ("1/1", "1/2", ...).
 */
export function parsePrintedOwnerOrdinal(label:string):number|null {
  if(typeof label!=='string'||label.length>24)return null;
  const value=DIGITS(label.normalize('NFKC')).trim();
  const match=/^1\s*[/／]\s*([1-9][0-9]{0,2})$/u.exec(value);
  if(!match)return null;
  const n=Number(match[1]);
  return n<=500?n:null;
}

/**
 * This is NOT direct OCR of table geometry. `cells` must already be safely
 * associated with the correct row and column by a *private* layout extractor.
 *
 * Missing page or row, contradictory text, low confidence and incomplete
 * rights sums trigger review; nothing can imply selling authority.
 */
export function analyzeOwnerTable(
  cells:readonly OwnerTableCellObservation[],
  options:Readonly<{
    declaredPages:number|null;
    receivedPages:readonly number[];
    declaredOwnerCount:number|null;
    printedTotalParts:string|null;
  }>
):OwnerTableAnalysis {
  if(cells.length>4500)throw new RangeError('TOO_MANY_TABLE_CELLS');
  if(!options||options.declaredPages!==null&&
     (!Number.isSafeInteger(options.declaredPages)||options.declaredPages<1||
       options.declaredPages>8)||
     !Array.isArray(options.receivedPages)||options.receivedPages.length>8||
     options.receivedPages.some(p=>!Number.isSafeInteger(p)||p<1||p>8)||
     options.declaredOwnerCount!==null&&
     (!Number.isSafeInteger(options.declaredOwnerCount)||options.declaredOwnerCount<1||
       options.declaredOwnerCount>500)||
     options.printedTotalParts!==null&&typeof options.printedTotalParts!=='string')
    throw new TypeError('INVALID_OWNER_TABLE_OPTIONS');
  const seenPages=new Set(options.receivedPages);
  if(seenPages.size!==options.receivedPages.length)
    throw new TypeError('DUPLICATE_OWNER_TABLE_PAGE');
  for(const cell of cells){
    validateCell(cell);
    if(!seenPages.has(cell.page))throw new TypeError('OWNER_CELL_PAGE_NOT_RECEIVED');
  }
  const missingPages=options.declaredPages===null?[]:
    Array.from({length:options.declaredPages},(_,i)=>i+1).filter(p=>!seenPages.has(p));
  const grouped=new Map<number,OwnerTableCellObservation[]>();
  for(const c of cells)grouped.set(c.ordinal,[...(grouped.get(c.ordinal)??[]),c]);
  const ordinals=Array.from(grouped.keys()).sort((a,b)=>a-b);
  const count=options.declaredOwnerCount;
  const missingOwnerOrdinals=count===null?[]:
    Array.from({length:count},(_,i)=>i+1).filter(n=>!grouped.has(n));
  const issues=new Set<OwnerTableIssue>();
  if(options.declaredPages===null||missingPages.length||
     seenPages.size!==options.declaredPages)issues.add('PAGE_SET_INCOMPLETE');
  if(count===null||missingOwnerOrdinals.length||
     ordinals.some(n=>count!==null&&n>count))issues.add('OWNER_ROW_MISSING');
  const rows=ordinals.map(ordinal=>{
    const observations=grouped.get(ordinal)!;
    const fields={} as Record<OwnerCellField,OwnerResolvedField>;
    for(const field of FIELDS)fields[field]=resolve(observations.filter(c=>c.field===field));
    const pages=Array.from(new Set(observations.map(c=>c.page))).sort((a,b)=>a-b);
    for(const [field,value] of Object.entries(fields)){
      if(value.status==='CONFLICT')issues.add('OWNER_ROW_CONFLICT');
      if(value.status==='LOW_CONFIDENCE'&&
         (field==='name'||field==='rightsParts'||field==='cin'))
        issues.add('OWNER_LOW_CONFIDENCE');
    }
    if(!['CANDIDATE','LOW_CONFIDENCE'].includes(fields.name.status))
      issues.add('OWNER_NAME_MISSING');
    if(!['CANDIDATE','LOW_CONFIDENCE'].includes(fields.rightsParts.status))
      issues.add('OWNER_PARTS_MISSING');
    const cin=fields.cin;
    if(cin.value!==null&&!/^[0-9]{8}$/u.test(DIGITS(cin.value)))
      issues.add('OWNER_CIN_INVALID');
    return {ordinal,sourcePages:pages,fields,
      structurallyReadable:fields.name.status==='CANDIDATE'&&
        fields.rightsParts.status==='CANDIDATE'&&
        (cin.value===null||/^[0-9]{8}$/u.test(DIGITS(cin.value)))} satisfies OwnerTableRow;
  });
  const rightsBalance=balanceOwnershipParts(rows.map(row=>{
    const share=row.fields.rightsParts;
    return {rowNumber:row.ordinal,parts:share.value,
      state:share.value!==null?'EXTRACTED':share.status==='MASKED'?'MASKED':
        share.status==='NOT_IN_SOURCE'?'NOT_IN_SOURCE':'UNREADABLE'} as const;
  }),options.printedTotalParts);
  if(rightsBalance.status!=='BALANCED')issues.add('PARTS_BALANCE_NOT_CONFIRMED');
  const issueList=Array.from(issues);
  return {rows,declaredOwnerCount:count,missingOwnerOrdinals,missingPages,
    rightsBalance,issues:issueList,
    readyForHumanReview:issueList.length===0&&rows.every(r=>r.structurallyReadable),
    ownershipVerified:false,mayPublish:false};
}
