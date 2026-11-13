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
  assert.ok(r.reasons.includes('UNSUPPORTED_ROLE_EXTRACTION'));
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
