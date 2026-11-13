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
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
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
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
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
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
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
  assert.equal(r.assessment,'NO_PERSON_IDENTIFIED');
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
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
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
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
 assert.equal(r.legalOwnershipVerified,false);
});
test('financial payment receipt payer is NOT the owner and must be expressly labelled',()=>{
 const doc=wrap(['QUITTANCE N° F0001','دافع المعاليم: Leila Ben Salem']);
 const r=compareUnifiedPrivateDocumentIdentity(doc,identity,['FINANCIAL_RECEIPT']);
 assert.equal(r.roles[0]?.role,'PAYMENT_PAYER');
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
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
   assert.equal(r.assessment,'NO_PERSON_IDENTIFIED');
   assert.equal(r.roles[0]?.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
 }
});

const bbox=(x:number,y:number)=>({left:x-23,top:y-10,right:x+23,bottom:y+10});
const word=(page:number,text:string,x:number,y:number)=>({
 page,text,confidence:0.99,bbox:bbox(x,y)
});
function sharedAcrossTwoPages(opts:{
 secondRowCin?:string;firstRowCin?:string;stripCinMarker?:boolean;
 omitFirstCin?:boolean;ambiguousSecondCin?:boolean
}={}):IngestedDocument {
 const row=(page:number,n:number,name:string,cin:string,y:number)=>[
   word(page,'1/'+n,945,y),word(page,'100',580,y+20),
   word(page,'جزء',580,y+46),
   word(page,name,830,y+20),word(page,'بنت',790,y+20),
   word(page,'سالم',750,y+20),
   ...(!opts.stripCinMarker?[word(page,'CIN',750,y+108)]:[]),
   ...(!(opts.omitFirstCin&&n===1)?[word(page,cin,815,y+108)]:[])
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
 assert.equal(r.matchedRightsHolder?.printedRowOrdinal,1);
 assert.equal(r.matchedRightsHolder?.rightsParts,'100');
 assert.equal(r.matchedRightsHolder?.documentPage,1);
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
test('target-person row is not blocked by unrelated ownership share totals',()=>{
 const base=sharedAcrossTwoPages();
 const conflicting={...base,pages:base.pages.map(p=>({
  ...p,lines:p.lines.map(l=>({
    ...l,text:l.text==='مجموع أجزاء الملكية: 200 جزء'?
       'مجموع أجزاء الملكية: 201 جزء':l.text
  }))
 }))};
 const r=compareUnifiedPrivateDocumentIdentity(conflicting,arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(r.matchedRightsHolder?.rightsParts,'100');
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


test('unreadable other coowners do not block unique ID holder private profile',()=>{
 const doc=sharedAcrossTwoPages();
 const pages=doc.pages.map(p=>p.page!==2?p:{
  ...p,privateSpatialPage:p.privateSpatialPage?{
   ...p.privateSpatialPage,words:p.privateSpatialPage.words.filter(w=>
     w.text!=='100'&&w.text!=='جزء'&&w.text!=='عائشة')
  }:undefined
 });
 const r=compareUnifiedPrivateDocumentIdentity({...doc,pages},arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.deepEqual(r.matchedRightsHolder,{
   printedRowOrdinal:1,rightsParts:'100',documentPage:1,
   status:'PRELIMINARY_PERSON_ROW_TEXT_ONLY'});
 assert.equal(JSON.stringify(r).includes('عائشة'),false);
});
test('missing matched persons share prevents claiming any row',()=>{
 const doc=sharedAcrossTwoPages();
 const pages=doc.pages.map(p=>p.page!==1?p:{
  ...p,privateSpatialPage:p.privateSpatialPage?{
   ...p.privateSpatialPage,words:p.privateSpatialPage.words.filter(w=>
    w.text!=='100'&&w.text!=='جزء')
  }:undefined
 });
 const r=compareUnifiedPrivateDocumentIdentity({...doc,pages},arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.matchedRightsHolder?.rightsParts,null);
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.ok(r.reasons.includes('TARGET_RIGHTS_NOT_READABLE'));
});
test('duplicate matching coowner rows abstain without choosing one',()=>{
 const doc=sharedAcrossTwoPages({secondRowCin:'00001122'});
 const pages=doc.pages.map(p=>p.page!==2?p:{
  ...p,privateSpatialPage:p.privateSpatialPage?{
   ...p.privateSpatialPage,words:p.privateSpatialPage.words.map(w=>
    w.text==='عائشة'?{...w,text:'أمينة'}:w)
  }:undefined
 });
 const r=compareUnifiedPrivateDocumentIdentity({...doc,pages},arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.equal(r.matchedRightsHolder,null);
});
test('only the identity holders matched row and parts may leave private comparator',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(sharedAcrossTwoPages(),arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']);
 const json=JSON.stringify(r);
 assert.equal(r.matchedRightsHolder?.rightsParts,'100');
 for(const sensitive of ['أمينة','عائشة','00001122','00003344'])
  assert.equal(json.includes(sensitive),false);
 assert.equal(r.legalOwnershipVerified,false);
 assert.equal(r.mayPublish,false);
});

test('buyer name alone is provisional; CIN not printed does not reject a sale deed',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(wrap([
  'عقد بيع','المشتري: Leila Ben Salem','البائع: Karim Ben Ahmed',
  'القطعة المبيعة رقم: 82','المساحة المبيعة: 185.25 م²'
 ]),identity,['SALE_DEED']);
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
 assert.equal(r.roles.find(x=>x.role==='BUYER')?.documentCinStatus,'NOT_PRINTED');
 assert.ok(r.reasons.includes('DOCUMENT_CIN_NOT_PRINTED'));
 assert.equal(r.matchedRightsHolder,null);
 assert.equal(r.legalOwnershipVerified,false);
});
test('explicit buyer CIN + buyer name can agree, unrelated seller CIN cannot substitute',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(wrap([
  'عقد بيع','المشتري: Leila Ben Salem','البائع: Karim Ben Ahmed',
  'رقم بطاقة تعريف المشتري: 00001122',
  'رقم بطاقة تعريف البائع: 12345678'
 ]),identity,['SALE_DEED']);
 assert.equal(r.assessment,'ROLE_TEXT_MATCH_ONLY');
 const buyer=r.roles.find(x=>x.role==='BUYER');
 assert.equal(buyer?.documentCinStatus,'SAME_NUMBER_UNVERIFIED');
 assert.equal(r.roles.find(x=>x.role==='SELLER')?.documentCinStatus,
  'NUMBER_DIFFERS_NEEDS_REVIEW');
 assert.equal(r.legalOwnershipVerified,false);
});
test('buyer name matches but explicitly buyer-labelled CIN differs => conflict review',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(wrap([
  'عقد بيع','المشتري: Leila Ben Salem',
  'رقم بطاقة تعريف المشتري: 12345678'
 ]),identity,['SALE_DEED']);
 assert.equal(r.assessment,'CONFLICT_REVIEW');
 assert.ok(r.reasons.includes('ROLE_CIN_CONFLICT'));
 assert.equal(r.mayPublish,false);
});
test('labelled CIN with conflicting values never falls back to name-only',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(wrap([
  'عقد بيع','المشتري: Leila Ben Salem',
  'رقم بطاقة تعريف المشتري: 00001122',
  'رقم بطاقة تعريف المشتري: 12345678'
 ]),identity,['SALE_DEED']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.ok(r.reasons.includes('DOCUMENT_CIN_UNREADABLE'));
});
test('anonymous service and financial receipts remain usable but no actor is claimed',()=>{
 for(const [text,kind] of [
  ['وصل تسلم مطلب خدمة واستخلاص معاليم','PROPERTY_SERVICE_RECEIPT'],
  ['QUITTANCE N° X0001','FINANCIAL_RECEIPT']
 ] as const){
  const r=compareUnifiedPrivateDocumentIdentity(wrap([text,'المبلغ: 210.000']),
   identity,[kind]);
  assert.equal(r.detectedKind,kind);
  assert.equal(r.assessment,'NO_PERSON_IDENTIFIED');
  assert.equal(r.roles[0]?.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.equal(r.mayPublish,false);
 }
});
test('co-owner without CIN printed in target row is name-only review, not rejected',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(
  sharedAcrossTwoPages({stripCinMarker:true,omitFirstCin:true}),arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
 assert.equal(r.matchedRightsHolder?.printedRowOrdinal,1);
 assert.equal(r.matchedRightsHolder?.rightsParts,'100');
 assert.equal(r.matchedRightsHolder?.status,'NAME_ONLY_REVIEW');
 assert.equal(r.roles[0]?.documentCinStatus,'NOT_PRINTED');
 assert.equal(r.identityVerified,false);
});
test('unlabelled eight-digit number in target row prevents name-only promotion',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(
  sharedAcrossTwoPages({stripCinMarker:true}),arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'INSUFFICIENT_EVIDENCE');
 assert.equal(r.matchedRightsHolder,null);
});
test('co-owner whose printed name matches but linked CIN differs is conflict',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(
  sharedAcrossTwoPages({firstRowCin:'12345678'}),arabicFront,
  ['SHARED_OWNERSHIP_CERTIFICATE']);
 assert.equal(r.assessment,'CONFLICT_REVIEW');
 assert.equal(r.matchedRightsHolder,null);
 assert.ok(r.reasons.includes('ROLE_CIN_CONFLICT'));
});
test('generic receipt number is never treated as a payer CIN',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(
   wrap(['QUITTANCE N° 00001122','دافع المعاليم: Leila Ben Salem']),
   identity,['FINANCIAL_RECEIPT']);
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
 assert.equal(r.roles[0]?.documentCinStatus,'NOT_PRINTED');
 assert.equal(r.matchedRightsHolder,null);
});


test('masked buyer CIN is non-evaluable, not absent or verified',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(wrap([
  'عقد بيع','المشتري: Leila Ben Salem',
  'رقم بطاقة تعريف المشتري: ████████'
 ]),identity,['SALE_DEED']);
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
 assert.equal(r.roles.find(x=>x.role==='BUYER')?.documentCinStatus,'MASKED_IN_SOURCE');
 assert.ok(r.reasons.includes('DOCUMENT_CIN_MASKED'));
 assert.ok(!r.reasons.includes('DOCUMENT_CIN_NOT_PRINTED'));
 assert.equal(r.legalOwnershipVerified,false);
});
test('two CIN-role candidates on separate unrelated buyer/seller pages cannot be swapped',()=>{
 const r=compareUnifiedPrivateDocumentIdentity(wrap([
  'عقد بيع','المشتري: Leila Ben Salem',
  'البائع: Karim Ben Ahmed','رقم بطاقة تعريف البائع: 00001122'
 ]),identity,['SALE_DEED']);
 assert.equal(r.assessment,'NAME_ONLY_REVIEW');
 assert.equal(r.roles.find(x=>x.role==='BUYER')?.documentCinStatus,'NOT_PRINTED');
 assert.equal(r.legalOwnershipVerified,false);
});
