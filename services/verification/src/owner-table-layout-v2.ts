/**
 * Layout-only extraction of candidate CPF rights rows from word-level OCR boxes.
 * Runs inside the private OCR boundary. Zero third-party calls / no model.
 *
 * HARD SAFETY GATES:
 *  - No header anchors => no rows. Never assume fixed column positions.
 *  - No clear printed 1/n ordinal => no row.
 *  - Rights value requires a visible adjacent "جزء" token and unique value.
 *  - Human must check owner names, shares and original document before use.
 *  - No legal verification, title clearance or publication is possible here.
 */
import {parsePrintedOwnerOrdinal} from './owner-table-v2.ts';
import type {OwnerTableCellObservation} from './owner-table-v2.ts';
import type {OcrPixelBox} from './smart-fields.ts';

export interface SpatialWord {
  readonly page:number;
  readonly text:string;
  readonly confidence:number;
  readonly bbox:OcrPixelBox;
}
export interface SpatialPage {
  readonly page:number;
  readonly width:number;
  readonly height:number;
  readonly words:readonly SpatialWord[];
}
export type LayoutFinding=
  'NO_PAGE_1'|'HEADER_NOT_FOUND'|'HEADER_GEOMETRY_AMBIGUOUS'|
  'NO_NUMBERED_OWNER_ROWS'|'DUPLICATE_OWNER_ORDINAL'|
  'SHARE_NOT_LOCATED'|'SHARE_AMBIGUOUS'|'NAME_NOT_LOCATED'|
  'PAGE_LAYOUT_MISMATCH'|'CIN_AMBIGUOUS';
export interface SpatialTableCandidate {
  readonly cells:readonly OwnerTableCellObservation[];
  readonly ownerOrdinals:readonly number[];
  readonly findings:readonly LayoutFinding[];
  readonly pageNumbers:readonly number[];
  readonly requiresHumanReview:true;
  readonly mayVerifyOwnership:false;
  readonly mayPublish:false;
}
const center=(b:OcrPixelBox)=>(b.left+b.right)/2;
const middle=(b:OcrPixelBox)=>(b.top+b.bottom)/2;
const norm=(s:string)=>s.normalize('NFKC')
  .replace(/[\u064B-\u065F\u0670\u0640\u200e\u200f]/gu,'')
  .replace(/[إأآ]/gu,'ا').replace(/ى/gu,'ي').trim();
function ensureValid(pages:readonly SpatialPage[]):void {
  if(!Array.isArray(pages)||pages.length>8||pages.length===0)
    throw new TypeError('INVALID_SPATIAL_PAGES');
  let total=0;
  const seen=new Set<number>();
  for(const page of pages){
    if(!Number.isSafeInteger(page.page)||page.page<1||page.page>8||
      seen.has(page.page)||!Number.isSafeInteger(page.width)||
      !Number.isSafeInteger(page.height)||page.width<200||page.height<300||
      page.width>10000||page.height>10000||
      !Array.isArray(page.words)||page.words.length>1500)
      throw new TypeError('INVALID_SPATIAL_PAGE');
    seen.add(page.page);
    total+=page.words.length;
    for(const word of page.words){
      const b=word.bbox;
      if(word.page!==page.page||typeof word.text!=='string'||
        word.text.length<1||word.text.length>120||
        !Number.isFinite(word.confidence)||word.confidence<0||
        word.confidence>1||!b||
        [b.left,b.top,b.right,b.bottom].some(v=>!Number.isSafeInteger(v))||
        b.left<0||b.top<0||b.right>b.left+10000||
        b.bottom>b.top+10000||b.left>=b.right||b.top>=b.bottom||
        b.right>page.width||b.bottom>page.height)
        throw new TypeError('INVALID_SPATIAL_WORD');
    }
  }
  if(total>3500)throw new RangeError('SPATIAL_WORDS_LIMIT');
}
interface Header {
  readonly ordinalX:number;readonly identityX:number;readonly shareX:number;
  readonly upperY:number;
}
const ordinalLabel=(s:string)=>/العدد|الرتبي|الترتيبي/u.test(norm(s));
const identityLabel=(s:string)=>/هوية|المالك|اللقب|الجنسية/u.test(norm(s));
const shareLabel=(s:string)=>/موضوع|الملكية/u.test(norm(s));
const isParts=(s:string)=>/^\d{1,9}(?:[.,]\d{1,3})?$/u.test(s);
const partLabel=(s:string)=>/^(?:جزء|اجزاء)$/u.test(norm(s));
const cinLabel=(s:string)=>/^(?:cin|c\.i\.n\.?|رقم\s*بطاقة\s*التعريف)$/iu.test(norm(s));
const cinDigits=(s:string)=>s.replace(/[٠-٩]/gu,d=>String(d.charCodeAt(0)-0x660))
  .replace(/[۰-۹]/gu,d=>String(d.charCodeAt(0)-0x6f0));
function findHeader(page:SpatialPage):Header|null|'AMBIGUOUS' {
  const items=page.words.filter(w=>w.confidence>=0.6 && middle(w.bbox)<page.height*.65);
  const ord=items.filter(w=>ordinalLabel(w.text));
  const ident=items.filter(w=>identityLabel(w.text));
  const share=items.filter(w=>shareLabel(w.text));
  // No heading or multiple incompatible column layouts => human review.
  const possible:{xO:number;xI:number;xS:number;y:number;rank:number}[]=[];
  for(const o of ord)for(const i of ident)for(const s of share){
    const yO=middle(o.bbox),yI=middle(i.bbox),yS=middle(s.bbox);
    if(Math.max(yO,yI,yS)-Math.min(yO,yI,yS)>page.height*.085)continue;
    const xO=center(o.bbox),xI=center(i.bbox),xS=center(s.bbox);
    if(xO-xI<page.width*.035||xI-xS<page.width*.04)continue;
    if(xO<page.width*.67||xS>page.width*.8)continue;
    possible.push({xO,xI,xS,y:Math.max(o.bbox.bottom,i.bbox.bottom,s.bbox.bottom),
      rank:o.confidence+i.confidence+s.confidence});
  }
  if(!possible.length)return null;
  possible.sort((a,b)=>b.rank-a.rank);
  const best=possible[0];
  if(possible.some(p=>Math.abs(p.xO-best.xO)>page.width*.08||
    Math.abs(p.xS-best.xS)>page.width*.08))return 'AMBIGUOUS';
  return {ordinalX:best.xO/page.width,identityX:best.xI/page.width,
    shareX:best.xS/page.width,upperY:best.y/page.height};
}
function makeCell(word:SpatialWord,ordinal:number,field:'name'|'rightsParts'|'cin',value:string,
  confidence:number):OwnerTableCellObservation {
  return {ordinal,field,page:word.page,lineIndex:0,source:'ocr-ar',
    confidence,value,state:'EXTRACTED'};
}
/**
 * Conservative token grouping. Accept names only if a clear personal-name
 * marker is present; legal descriptors and dates are deliberately excluded.
 * No names are ever returned in the public API by this function.
 */
function nameCandidate(words:readonly SpatialWord[],ordinal:number):OwnerTableCellObservation|null {
  const sorted=[...words].filter(w=>w.confidence>=0.87)
    .sort((a,b)=>a.bbox.top-b.bbox.top||b.bbox.right-a.bbox.right);
  const firstRowTop=sorted[0]?.bbox.top;
  if(firstRowTop===undefined)return null;
  const first=sorted.filter(w=>w.bbox.top-firstRowTop<48).slice(0,15);
  const usable:string[]=[];
  for(const word of first){
    const n=norm(word.text);
    if(/^(?:صاحب|صاحبة|بطاقة|تعريف|وطنية|مولود|مولودة|متزوج|متزوجة|الساكن)$/u.test(n)||
      /\d{3,}/u.test(n))break;
    if(/^[\p{Script=Arabic}\s]{2,45}$/u.test(n))usable.push(n);
  }
  if(usable.length<3 || !usable.some(s=>/^(?:بن|بنت)$/u.test(s)))return null;
  const name=usable.join(' ');
  if(name.length>120)return null;
  const confidence=Math.min(...first.slice(0,usable.length).map(w=>w.confidence));
  return makeCell(first[0],ordinal,'name',name,confidence);
}
export function locateOwnerTableFromWords(pages:readonly SpatialPage[]):SpatialTableCandidate {
  ensureValid(pages);
  const findings=new Set<LayoutFinding>();
  const pageList=[...pages].sort((a,b)=>a.page-b.page);
  const first=pageList.find(p=>p.page===1);
  if(!first){
    return {cells:[],ownerOrdinals:[],findings:['NO_PAGE_1'],pageNumbers:pageList.map(p=>p.page),
      requiresHumanReview:true,mayVerifyOwnership:false,mayPublish:false};
  }
  const header=findHeader(first);
  if(header==='AMBIGUOUS'||header===null){
    return {cells:[],ownerOrdinals:[],
      findings:[header===null?'HEADER_NOT_FOUND':'HEADER_GEOMETRY_AMBIGUOUS'],
      pageNumbers:pageList.map(p=>p.page),requiresHumanReview:true,
      mayVerifyOwnership:false,mayPublish:false};
  }
  const result:OwnerTableCellObservation[]=[];
  const ordinals:number[]=[];
  for(const page of pageList){
    const start=page.page===1?header.upperY:0;
    const ordinalWords=page.words.map(word=>({word,n:parsePrintedOwnerOrdinal(word.text)}))
      .filter(x=>x.n!==null&&x.word.confidence>=0.87 &&
        middle(x.word.bbox)/page.height>start &&
        Math.abs(center(x.word.bbox)/page.width-header.ordinalX)<.085)
      .sort((a,b)=>a.word.bbox.top-b.word.bbox.top);
    for(let i=0;i<ordinalWords.length;i++){
      const mark=ordinalWords[i],n=mark.n!;
      if(ordinals.includes(n)){findings.add('DUPLICATE_OWNER_ORDINAL');continue;}
      ordinals.push(n);
      const top=mark.word.bbox.top-page.height*.015;
      const next=ordinalWords[i+1]?.word.bbox.top;
      const bottom=Math.min(next??page.height*.93,mark.word.bbox.top+page.height*.26);
      const rowWords=page.words.filter(w=>{
        const y=middle(w.bbox),x=center(w.bbox)/page.width;
        return y>=top&&y<bottom&&w.confidence>=.75&&
          x>header.shareX-page.width*.001/page.width&&x<header.ordinalX+.07;
      });
      const shareWords=rowWords.filter(w=>
        Math.abs(center(w.bbox)/page.width-header.shareX)<.085);
      const nums=shareWords.filter(w=>isParts(w.text)&&w.confidence>=.87);
      const chosen=nums.filter(w=>shareWords.some(q=>
        partLabel(q.text)&&Math.abs(center(q.bbox)-center(w.bbox))<page.width*.08&&
        middle(q.bbox)>middle(w.bbox)&&middle(q.bbox)-middle(w.bbox)<page.height*.065));
      if(chosen.length===1){
        result.push(makeCell(chosen[0],n,'rightsParts',chosen[0].text,chosen[0].confidence));
      }else findings.add(chosen.length?'SHARE_AMBIGUOUS':'SHARE_NOT_LOCATED');
      const identityWords=rowWords.filter(w=>{
        const x=center(w.bbox)/page.width;
        return x>(header.shareX+header.identityX)/2 &&
          x<(header.identityX+header.ordinalX)/2;
      });
      const name=nameCandidate(identityWords,n);
      if(name)result.push(name);else findings.add('NAME_NOT_LOCATED');
      // A title can contain many CIN-like 8-digit numbers. Only an explicit
      // CIN label and an adjacent, SAME-ROW identity-column token can form a
      // private row CIN candidate. Never match "any CIN" on the whole paper.
      const labels=identityWords.filter(w=>cinLabel(w.text)&&w.confidence>=.90);
      const candidates=identityWords.filter(w=>
        w.confidence>=.90 && /^[0-9]{8}$/u.test(cinDigits(w.text)) &&
        labels.some(label=>
          Math.abs(center(label.bbox)-center(w.bbox))<page.width*.20 &&
          Math.abs(middle(label.bbox)-middle(w.bbox))<page.height*.06));
      if(candidates.length===1)
        result.push(makeCell(candidates[0]!,n,'cin',
          cinDigits(candidates[0]!.text),candidates[0]!.confidence));
      else if(candidates.length>1)findings.add('CIN_AMBIGUOUS');
      else {
        const masked=identityWords.filter(w=>
          /^(?:[Xx*█■▇#]{4,12})$/u.test(w.text.trim())&&
          labels.some(label=>
            Math.abs(center(label.bbox)-center(w.bbox))<page.width*.20&&
            Math.abs(middle(label.bbox)-middle(w.bbox))<page.height*.06));
        if(masked.length===1){
          result.push({ordinal:n,field:'cin',page:page.page,
            lineIndex:0,source:'ocr-ar',confidence:null,value:null,
            state:'MASKED'});
          continue;
        }
        // A CIN-like number without a reliably adjacent role label is not
        // proof that this row has NO CIN. Prevent name-only promotion when
        // an unassociated identity number or unreadable label is visible.
        const unbound=identityWords.some(w=>
          /^[0-9]{8}$/u.test(cinDigits(w.text)));
        if(unbound||labels.length>0)
          result.push({ordinal:n,field:'cin',page:page.page,
            lineIndex:0,source:'ocr-ar',confidence:null,value:null,
            state:'UNREADABLE'});
      }
    }
  }
  if(!ordinals.length)findings.add('NO_NUMBERED_OWNER_ROWS');
  return {cells:result,ownerOrdinals:ordinals,
    findings:Array.from(findings),pageNumbers:pageList.map(p=>p.page),
    requiresHumanReview:true,mayVerifyOwnership:false,mayPublish:false};
}
