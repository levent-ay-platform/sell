/**
 * Verification helpers for Tunisian abstract ownership shares and subparcel
 * matching. No personal identifiers and no authorization decisions.
 * All arithmetic uses integer milliparts; a "part" is NEVER square metres.
 */
export type SourceValueState='EXTRACTED'|'MASKED'|'NOT_IN_SOURCE'|'UNREADABLE';
export interface RightsPartRow {
  readonly rowNumber:number;
  readonly parts:string|null;
  readonly state:SourceValueState;
}
export type RightsBalanceStatus='BALANCED'|'UNBALANCED'|'INCOMPLETE'|'INVALID';
export interface RightsBalance {
  readonly status:RightsBalanceStatus;
  readonly sumParts:string|null;
  readonly expectedParts:string|null;
  readonly rowsChecked:number;
  readonly missingRows:readonly number[];
  readonly legalOwnershipConfirmed:false;
}
function normalizeDigits(s:string):string{
  return s.replace(/[٠-٩]/gu,c=>String(c.charCodeAt(0)-0x660))
    .replace(/[۰-۹]/gu,c=>String(c.charCodeAt(0)-0x6f0));
}
/** No guessed thousands separators; only decimal dot OR comma is supported. */
function parseMilliparts(raw:string):bigint|null {
  const text=normalizeDigits(raw.trim());
  if(!/^(?:0|[1-9][0-9]{0,11})(?:[.,][0-9]{1,3})?$/u.test(text))return null;
  const [integer,decimal='']=text.replace(',','.').split('.');
  const n=BigInt(integer)*1000n+BigInt((decimal+'000').slice(0,3));
  return n>=0n?n:null;
}
function formatMilliparts(n:bigint):string{
  const whole=n/1000n;
  const frac=(n%1000n).toString().padStart(3,'0');
  return frac==='000'?whole.toString():whole.toString()+'.'+frac;
}
/**
 * Checks *arithmetic only*. Even BALANCED is NOT proof of current legal rights
 * or permission to dispose of all or any specific physical land plot.
 */
export function balanceOwnershipParts(rows:readonly RightsPartRow[],denominator:string|null):RightsBalance {
  if(rows.length>500)throw new RangeError('TOO_MANY_RIGHTS_HOLDERS');
  const expected=denominator===null?null:parseMilliparts(denominator);
  const seen=new Set<number>();
  let invalid=expected===null||expected===0n||rows.length===0, sum=0n;
  const missing:number[]=[];
  for(const row of rows){
    if(!Number.isSafeInteger(row.rowNumber)||row.rowNumber<1||
      seen.has(row.rowNumber)||!['EXTRACTED','MASKED','NOT_IN_SOURCE','UNREADABLE'].includes(row.state))
      {invalid=true;continue;}
    seen.add(row.rowNumber);
    if(row.state!=='EXTRACTED'||row.parts===null){
      missing.push(row.rowNumber);continue;
    }
    const part=parseMilliparts(row.parts);
    if(part===null){invalid=true;continue;}
    sum+=part;
  }
  const status:RightsBalanceStatus=invalid?'INVALID':
    missing.length?'INCOMPLETE':sum===expected?'BALANCED':'UNBALANCED';
  return {status,sumParts:invalid?null:formatMilliparts(sum),
    expectedParts:expected===null?null:formatMilliparts(expected),
    rowsChecked:seen.size,missingRows:missing,legalOwnershipConfirmed:false};
}
export interface ParcelReference {
  readonly registerNumber:string|null;
  readonly registryOffice:string|null;
  readonly plotNumber:string|null;
}
/**
 * Same parent land register does not mean same sold/possessed subparcel.
 * No scope match is emitted without both parties supplying exact plot IDs.
 */
export function compareDocumentParcelScope(
  a:ParcelReference,b:ParcelReference
):'SAME_PLOT'|'DIFFERENT_PLOT'|'DIFFERENT_REGISTER'|'INSUFFICIENT_REFERENCE' {
  if(!a.registerNumber||!b.registerNumber||!a.registryOffice||!b.registryOffice)
    return 'INSUFFICIENT_REFERENCE';
  if(a.registerNumber.trim()!==b.registerNumber.trim()||
     a.registryOffice.trim()!==b.registryOffice.trim())return 'DIFFERENT_REGISTER';
  if(!a.plotNumber||!b.plotNumber)return 'INSUFFICIENT_REFERENCE';
  return a.plotNumber.trim()===b.plotNumber.trim()?'SAME_PLOT':'DIFFERENT_PLOT';
}
