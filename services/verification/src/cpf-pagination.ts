/** Shared printed CPF pagination evidence. Physical file pages and printed page
 * labels are different facts; low-confidence numbers never establish absence.
 */
import type {RecognizedLine} from './smart-fields.ts';

export interface PrintedPageNumber {page:number;total:number}
function normalize(s:string):string {
  return s.normalize('NFKC')
    .replace(/[٠-٩]/gu,d=>String(d.charCodeAt(0)-0x660))
    .replace(/[۰-۹]/gu,d=>String(d.charCodeAt(0)-0x6f0))
    .replace(/[\u064B-\u065F\u0670\u0640\u200e\u200f\u202a-\u202e\u2066-\u2069]/gu,'')
    .trim();
}
/** Accept explicit Arabic/French/English page markers only, not owner 1/n rows. */
export function printedPageNumbers(raw:string):PrintedPageNumber[] {
  if(raw.length>300)return [];
  const text=normalize(raw);
  const direct=[...text.matchAll(/(?:صفحة|صفحه)\s*[:：-]?\s*(\d{1,3})\s*(?:من|\/)\s*(\d{1,3})(?!\d)|\bpage\s*[:：-]?\s*(\d{1,3})\s*(?:sur|de|of|\/)\s*(\d{1,3})(?!\d)/giu)]
    .map(m=>({page:Number(m[1]??m[3]),total:Number(m[2]??m[4])}));
  const bidi=/^(\d{1,3})\s+(?:صفحة|صفحه)\s+(\d{1,3})\s+من\s*$/u.exec(text);
  return bidi?[...direct,{page:Number(bidi[2]),total:Number(bidi[1])}]:direct;
}
export interface PaginationAudit {
  status:'COMPLETE'|'MISSING_PAGES'|'UNREADABLE'|'CONFLICT';
  expectedPages:number|null;
  receivedPages:number;
  missingPages:number[];
}
export function auditPrintedPagination(pages:readonly {page:number;lines:readonly RecognizedLine[]}[]):PaginationAudit {
  const result:PaginationAudit={status:'UNREADABLE',expectedPages:null,receivedPages:pages.length,missingPages:[]};
  if(pages.length<1||pages.length>8||pages.some((p,i)=>p.page!==i+1))return {...result,status:'CONFLICT'};
  const observations=pages.flatMap(p=>p.lines.flatMap(l=>printedPageNumbers(l.text).map(n=>({
    ...n,physical:p.page,trusted:l.source==='pdf-text'?l.confidence===null||l.confidence>=.9:
      l.confidence!==null&&l.confidence>=.9
  }))));
  if(!observations.length)return result;
  const totals=new Set(observations.map(o=>o.total));
  if(totals.size!==1||observations.some(o=>o.page!==o.physical||o.total<1||o.total>8||o.page>o.total))
    return {...result,status:'CONFLICT'};
  const total=observations[0]!.total;
  if(total<pages.length)return {...result,status:'CONFLICT'};
  const trusted=observations.filter(o=>o.trusted);
  if(!trusted.length)return result;
  const expected={...result,expectedPages:total};
  // Physical pages received but their printed footers are unreadable: request
  // review, not a duplicate upload of a page that is already present.
  if(total>pages.length)return {...expected,status:'MISSING_PAGES',
    missingPages:Array.from({length:total-pages.length},(_,i)=>pages.length+i+1)};
  if(pages.some(p=>!trusted.some(o=>o.physical===p.page)))return expected;
  return {...expected,status:'COMPLETE'};
}
