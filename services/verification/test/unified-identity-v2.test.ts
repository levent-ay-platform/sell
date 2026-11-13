import test from 'node:test';
import assert from 'node:assert/strict';
import {compareUnifiedPrivateDocumentIdentity} from '../src/unified-identity-v2.ts';
import type {IngestedDocument} from '../src/reader.ts';

const wrap=(text:readonly string[],isIdentity=false,confidence:number|null=.99):IngestedDocument=>({
 format:isIdentity?'image':'pdf',mayApproveOwnership:false,
 pages:[{page:1,method:isIdentity?'ocr':'pdf-text',lines:text.map(t=>({
    page:1,text:t,confidence:isIdentity?confidence:null,
    source:isIdentity?'ocr-fr' as const:'pdf-text' as const
 }))}]
});
const identity=wrap(['Nom et prénom: Leila Ben Salem','CIN: 00001122'],true);

test('single parser classifies ownership certificate before private name and CIN comparison',()=>{
 const doc=wrap([
  'شهادة ملكية','Propriétaire: Leila Ben Salem','CIN: 00001122'
 ]);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['OWNERSHIP_CERTIFICATE']);
 assert.equal(r.detectedKind,'OWNERSHIP_CERTIFICATE');
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(r.roles[0]?.role,'REGISTERED_HOLDER');
 assert.equal(r.legalOwnershipVerified,false);
 assert.equal(r.identityVerified,false);
 assert.equal(r.mayPublish,false);
 assert.equal(JSON.stringify(r).includes('Leila'),false);
 assert.equal(JSON.stringify(r).includes('00001122'),false);
});
test('certificate with explicit CIN disagreement never gains role agreement',()=>{
 const doc=wrap(['شهادة ملكية','Propriétaire: Leila Ben Salem','CIN: 12345678']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'ROLE_TEXT_DIFFERENCE');
 assert.ok(r.reasons.includes('CIN_TEXT_DIFFERS'));
 assert.equal(r.mayPublish,false);
});
test('shared certificate must not equate one row in a multiple-owner table to entire title',()=>{
 const doc=wrap(['شهادة اشتراك في الملكية','Propriétaire: Leila Ben Salem']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.ok(r.reasons.includes('SHARED_RIGHTS_NEED_ROW_REVIEW'));
 assert.equal(r.roles[0]?.role,'SHARED_RIGHTS_HOLDER');
});
test('sale deed keeps buyer and seller separate; only buyer matches owner identity text',()=>{
 const doc=wrap([
  'عقد بيع','المشتري: Leila Ben Salem','البائع: Karim Ben Ahmed',
  'المساحة المبيعة: 185.25 م²'
 ]);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['SALE_DEED']);
 assert.equal(r.detectedKind,'SALE_DEED');
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(r.roles.find(x=>x.role==='BUYER')?.assessment,
  'PRELIMINARY_TEXT_AGREEMENT');
 assert.equal(r.roles.find(x=>x.role==='SELLER')?.assessment,
  'TEXT_DIFFERENCE_NEEDS_REVIEW');
 assert.ok(r.reasons.includes('ROLE_MATCH_NOT_OWNERSHIP'));
 assert.equal(r.legalOwnershipVerified,false);
});
test('sale deed can match seller but never assume they are the current owner',()=>{
 const doc=wrap(['عقد بيع','المشتري: Karim Ben Ahmed',
  'البائع: Leila Ben Salem']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['SALE_DEED']);
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(r.roles.find(x=>x.role==='SELLER')?.assessment,
  'PRELIMINARY_TEXT_AGREEMENT');
 assert.equal(r.legalOwnershipVerified,false);
});
test('transfer card distinguishes transferor and transferee without registered rights inference',()=>{
 const doc=wrap(['بطاقة نقل الملكية',
  'المنقول إليه: Leila Ben Salem',
  'الناقل: Karim Ben Ahmed']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['TRANSFER_CARD']);
 assert.equal(r.detectedKind,'TRANSFER_CARD');
 assert.equal(r.roles.find(x=>x.role==='TRANSFEREE')?.assessment,
  'PRELIMINARY_TEXT_AGREEMENT');
 assert.equal(r.legalOwnershipVerified,false);
});
test('service receipt applicant can match name but NOT land ownership or verification',()=>{
 const doc=wrap(['وصل تسلم مطلب خدمة واستخلاص معاليم',
  'طالب الخدمة: Leila Ben Salem','العدد: 999123']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,
  ['PROPERTY_SERVICE_RECEIPT']);
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(r.roles[0]?.role,'SERVICE_APPLICANT');
 assert.equal(r.identityVerified,false);
 assert.equal(r.legalOwnershipVerified,false);
 assert.equal(r.mayPublish,false);
});
test('financial receipt and possession report with no reliably labelled relevant person are inconclusive',()=>{
 for(const [title,kind] of [
  ['QUITTANCE N° X0001','FINANCIAL_RECEIPT'],
  ['محضر معاينة تحويز','POSSESSION_REPORT']
 ] as const){
  const r=compareUnifiedPrivateDocumentIdentity(wrap([title]),identity,[kind]);
  assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
  assert.equal(r.roles[0]?.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.ok(r.reasons.includes('LOW_CONFIDENCE_OR_CONFLICTING_ROLE'));
  assert.equal(r.mayPublish,false);
 }
});
test('unknown document is still reviewable but never automatically matched to identity',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(wrap(['unknown legal paper',
  'المالك: Leila Ben Salem']),identity,['OTHER_LEGAL_PROOF']);
 assert.equal(r.detectedKind,'UNKNOWN');
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.ok(r.reasons.includes('DOCUMENT_TYPE_NOT_CONFIRMED'));
});
test('the stored category and the OCR document family must not contradict',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(
  wrap(['عقد بيع','المشتري: Leila Ben Salem']),identity,['OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.ok(r.reasons.includes('DOCUMENT_TYPE_MISMATCH'));
});
test('generic OTHER_LEGAL_PROOF is usable as safe intake and must not force a kind',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(
  wrap(['عقد بيع','المشتري: Leila Ben Salem']),
  identity,['OTHER_LEGAL_PROOF']);
 assert.equal(r.detectedKind,'SALE_DEED');
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
});
test('two matching legal roles cannot be resolved silently',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(wrap(['عقد بيع',
  'المشتري: Leila Ben Salem','البائع: Leila Ben Salem']),identity,['SALE_DEED']);
 assert.equal(r.assessment,'ROLE_AMBIGUOUS');
 assert.ok(r.reasons.includes('MULTIPLE_MATCHING_ROLES'));
});
test('low-confidence document, actor or blurred identity cannot become agreement',()=>{
 const idLow=wrap(['Nom et prénom: Leila Ben Salem'],true,.64);
 const doc=wrap(['عقد بيع','المشتري: Leila Ben Salem']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,idLow,['SALE_DEED']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.equal(r.roles[0]?.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
});
test('each role output excludes names, CIN and other PII',()=>{
 const doc=wrap(['وصل تسلم مطلب خدمة واستخلاص معاليم',
  'طالب الخدمة: Leila Ben Salem','رقم المطلب: 448899']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['PROPERTY_SERVICE_RECEIPT']);
 assert.equal(JSON.stringify(r).includes('Leila'),false);
 assert.equal(JSON.stringify(r).includes('448899'),false);
 assert.equal(JSON.stringify(r).includes('00001122'),false);
});


test('explicitly labelled possession requester matches a role, never legal title',()=>{
 const doc=wrap(['محضر معاينة تحويز',
  'طالب التحويز: Leila Ben Salem',
  'مساحة القطعة محل التحويز: 314.40 م²']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['POSSESSION_REPORT']);
 assert.equal(r.detectedKind,'POSSESSION_REPORT');
 assert.equal(r.roles[0]?.role,'POSSESSION_REQUESTER');
 assert.equal(r.roles[0]?.assessment,'PRELIMINARY_TEXT_AGREEMENT');
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(r.legalOwnershipVerified,false);
});
test('financial payment receipt payer is NOT the owner and must be expressly labelled',()=>{
 const doc=wrap(['QUITTANCE N° F0001','دافع المعاليم: Leila Ben Salem']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['FINANCIAL_RECEIPT']);
 assert.equal(r.roles[0]?.role,'PAYMENT_PAYER');
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(r.legalOwnershipVerified,false);
 assert.equal(r.mayPublish,false);
});
test('unlabelled name in possession or receipt must NOT be a role',()=>{
 for(const [title,kind] of [
   ['محضر معاينة تحويز','POSSESSION_REPORT'],
   ['QUITTANCE N° F0001','FINANCIAL_RECEIPT']
 ] as const){
   const doc=wrap([title,'Leila Ben Salem']);
   const r=compareUnifiedPrivateDocumentIdentity(doc,identity,[kind]);
   assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
   assert.equal(r.roles[0]?.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
 }
});

const bbox=(x:number,y:number)=>({left:x-23,top:y-10,right:x+23,bottom:y+10});
const word=(page:number,text:string,x:number,y:number)=>({
 page,text,confidence:0.99,bbox:bbox(x,y)
});
function sharedAcrossTwoPages(opts:{
 secondRowCin?:string;firstRowCin?:string;stripCinMarker?:boolean;
 ambiguousSecondCin?:boolean
}={}):IngestedDocument {
 const row=(page:number,n:number,name:string,cin:string,y:number)=>[
   word(page,'1/'+n,945,y),word(page,'100',580,y+20),
   word(page,'جزء',580,y+46),
   word(page,name,830,y+20),word(page,'بنت',790,y+20),
   word(page,'سالم',750,y+20),
   ...(!opts.stripCinMarker?[word(page,'CIN',750,y+108)]:[]),
   word(page,cin,815,y+108)
 ];
 const first=[
   word(1,'موضوع',580,290),word(1,'هوية',790,290),
   word(1,'العدد',945,290),
   ...row(1,1,'أمينة',opts.firstRowCin??'00001122',490)
 ];
 const second=[
   ...row(2,2,'عائشة',opts.secondRowCin??'00003344',320),
   ...(opts.ambiguousSecondCin?[word(2,'00004455',820,428)]:[])
 ];
 return {
  format:'pdf',mayApproveOwnership:false,pages:[
   {page:1,method:'ocr',
    lines:[{page:1,text:'شهادة اشتراك في الملكية',
      source:'ocr-ar',confidence:0.99},
      {page:1,text:'صفحة 1 من 2',source:'ocr-ar',confidence:0.99}],
    privateSpatialPage:{page:1,width:1000,height:1500,words:first},
    spatialWordsTruncated:false
   },
   {page:2,method:'ocr',
    lines:[{page:2,text:'صفحة 2 من 2',source:'ocr-ar',confidence:0.99},
      {page:2,text:'مجموع أجزاء الملكية: 200 جزء',
        source:'ocr-ar',confidence:0.99}],
    privateSpatialPage:{page:2,width:1000,height:1500,words:second},
    spatialWordsTruncated:false
   }
  ]
 };
}
const arabicFront=wrap(['الاسم واللقب: أمينة بنت سالم','CIN: 00001122'],true);
test('multi-page share-name and SAME-ROW labelled CIN only can yield preliminary role text agreement',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(sharedAcrossTwoPages(),arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE','OTHER_LEGAL_PROOF']);
 assert.equal(r.detectedKind,'SHARED_OWNERSHIP_CERTIFICATE');
 assert.equal(r.roles[0]?.role,'SHARED_RIGHTS_HOLDER');
 assert.equal(r.roles[0]?.assessment,'PRELIMINARY_TEXT_AGREEMENT');
 assert.equal(r.roles[0]?.documentPage,1);
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.ok(r.reasons.includes('SHARED_RIGHTS_NEED_ROW_REVIEW'));
 assert.equal(r.legalOwnershipVerified,false);
 assert.equal(r.identityVerified,false);
 assert.equal(r.mayPublish,false);
});
test('shared certificate cannot search ANY CIN from unrelated row',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(sharedAcrossTwoPages({
  firstRowCin:'00009988',secondRowCin:'00001122'
 }),arabicFront,['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.notEqual(r.roles[0]?.assessment,'PRELIMINARY_TEXT_AGREEMENT');
});
test('unlabelled 8-digit number cannot be guessed as a CIN on shared title',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(sharedAcrossTwoPages({
  stripCinMarker:true
 }),arabicFront,['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
});
test('ambiguous extra 8-digit token in owner row blocks agreement when it competes with printed CIN',()=>{
 const frontSecond=wrap(['الاسم واللقب: عائشة بنت سالم','CIN: 00003344'],true);
 const r=compareUnifiedPrivateDocumentIdentity(sharedAcrossTwoPages({
  ambiguousSecondCin:true
 }),frontSecond,['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.equal(r.roles[0]?.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
});
test('no spatial word boxes, truncated word boxes or missing pages never assert coowner agreement',()=>{
 const original=sharedAcrossTwoPages();
 const absent={...original,pages:original.pages.map(p=>({
  ...p,privateSpatialPage:undefined
 }))};
 assert.equal(compareUnifiedPrivateDocumentIdentity(absent,arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']).assessment,'INSUFFICIENT_EVIDENCE');
 const truncated={...original,pages:original.pages.map(p=>({
  ...p,spatialWordsTruncated:true
 }))};
 assert.equal(compareUnifiedPrivateDocumentIdentity(truncated,arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']).assessment,'INSUFFICIENT_EVIDENCE');
 const missing={...original,pages:[original.pages[0]!]};
 assert.equal(compareUnifiedPrivateDocumentIdentity(missing,arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']).assessment,'INSUFFICIENT_EVIDENCE');
});


test('private co-owner text agreement requires independently printed page count',()=>{
 const full=sharedAcrossTwoPages();
 const missingPageCount={...full,pages:full.pages.map(p=>({
   ...p,lines:p.lines.filter(l=>!l.text.includes('صفحة'))
 }))};
 const r=compareUnifiedPrivateDocumentIdentity(missingPageCount,arabicFront,
    ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.equal(r.mayPublish,false);
});
test('rights-table partial sum or conflicting printed denominator prevents matching a co-owner',()=>{
 const base=sharedAcrossTwoPages();
 const conflicting={...base,pages:base.pages.map(p=>({
  ...p,lines:p.lines.map(l=>({
    ...l,text:l.text==='مجموع أجزاء الملكية: 200 جزء'?
       'مجموع أجزاء الملكية: 201 جزء':l.text
  }))
 }))};
 const r=compareUnifiedPrivateDocumentIdentity(conflicting,arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.equal(r.identityVerified,false);
 assert.equal(r.legalOwnershipVerified,false);
});


test('contradictory page-count labels on the SAME page fail closed',()=>{
 const full=sharedAcrossTwoPages();
 const pages=full.pages.map((p,i)=>i===0?{...p,lines:[
   ...p.lines,
   {page:1,text:'صفحة 1 من 3',source:'ocr-ar' as const,confidence:.99}
 ]}:p);
 const r=compareUnifiedPrivateDocumentIdentity({...full,pages},arabicFront,
   ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.equal(r.legalOwnershipVerified,false);
});
