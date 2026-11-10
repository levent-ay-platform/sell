import test from 'node:test';
import assert from 'node:assert/strict';
import {extractCpfSmartFields} from '../src/smart-fields.ts';
import type {RecognizedLine} from '../src/smart-fields.ts';
const lines=(arr:string[], source:RecognizedLine['source']='ocr-ar',confidence=0.98,page=1):RecognizedLine[]=>
  arr.map(text=>({text,page,confidence,source}));
test('mixed Arabic/Indic and French certificate: registry and area distinguished from share units',()=>{
 const d=extractCpfSmartFields(lines(['شهادة ملكية','معرف الرسم العقاري: ٩٨٧٦٥٤','المساحة: ٥١٠ م','موضوع الملكية: ٥١٠ جزء','التجزئة: ٥١٠ جزء','صفحة 1 من 2']));
 assert.equal(d.documentKind,'CPF_OWNERSHIP_CERTIFICATE');
 assert.equal(d.fields.registryReference.value,'987654');
 assert.equal(d.fields.areaSquareMetres.value,510);
 assert.equal(d.fields.ownershipShareUnits.value,510);
 assert.equal(d.fields.ownershipTotalUnits.value,null);
 assert.deepEqual(d.missingPages,[2]);
 assert.equal(d.mayUseForAutomaticApproval,false);
});
test('multi-page same document count does not trigger conflicting page number',()=>{
 const d=extractCpfSmartFields([...lines(['شهادة ملكية','صفحة 1 من 2']),...lines(['صفحة 2 من 2'], 'ocr-ar',0.97,2)]);
 assert.deepEqual(d.missingPages,[]);
 assert.ok(!d.issues.includes('CONFLICTING_FIELDS'));
 assert.ok(!d.issues.includes('INCONSISTENT_PAGE_COUNT'));
});
test('conflicting title references are never silently resolved by best confidence',()=>{
 const d=extractCpfSmartFields(lines(['شهادة ملكية','معرف الرسم العقاري: 123456','معرف الرسم العقاري: 123457']));
 assert.equal(d.fields.registryReference.value,null);
 assert.equal(d.fields.registryReference.status,'CONFLICT');
 assert.ok(d.issues.includes('CONFLICTING_FIELDS'));
});
test('low confidence OCR does not become a trusted title',()=>{
 const d=extractCpfSmartFields(lines(['شهادة ملكية','معرف الرسم العقاري: 123456'],'ocr-ar',0.64));
 assert.equal(d.fields.registryReference.status,'LOW_CONFIDENCE');
 assert.ok(d.issues.includes('LOW_CONFIDENCE'));
});
test('validated date rejects impossible Feb 30 and keeps registry update separate from issue date',()=>{
 const d=extractCpfSmartFields(lines(['شهادة ملكية','تاريخ آخر تحيين: 2016/01/22','تاريخ إصدار الشهادة: 2026/02/30']));
 assert.equal(d.fields.lastRegistryUpdatedAt.value,'2016-01-22');
 assert.equal(d.fields.certificateIssuedAt.value,null);
});
test('recognizes French metadata and page n sur m',()=>{
 const d=extractCpfSmartFields(lines(['Certificat de propriété','Titre foncier: 998877','Superficie: 310,5 m²','Page 1 sur 2'],'ocr-fr',0.99));
 assert.equal(d.documentKind,'CPF_OWNERSHIP_CERTIFICATE');
 assert.equal(d.fields.areaSquareMetres.value,310.5);
 assert.equal(d.fields.registryReference.value,'998877');
 assert.deepEqual(d.missingPages,[2]);
});
test('document and page inconsistency trigger manual review',()=>{
 const d=extractCpfSmartFields([...lines(['صفحة 1 من 2','صفحة 1 من 3']),...lines(['صفحة 3 من 3'],'ocr-ar',0.9,2)]);
 assert.equal(d.fields.documentPageCount.status,'CONFLICT');
 assert.ok(d.issues.includes('UNREADABLE_PAGE_COUNT'));
 assert.ok(d.issues.includes('INCONSISTENT_PAGE_COUNT'));
});
test('total share denominator requires explicit label',()=>{
 const d=extractCpfSmartFields(lines(['التجزئة: 2000 جزء','موضوع الملكية: 500 جزء','مجموع أجزاء الملكية: 2000']));
 assert.equal(d.fields.ownershipTotalUnits.value,2000);
 assert.equal(d.fields.ownershipShareUnits.value,500);
});
test('reject malformed confidence and large input bounds',()=>{
 assert.throws(()=>extractCpfSmartFields([{text:'شهادة ملكية',page:1,confidence:2,source:'ocr-ar'}]),/Invalid OCR/);
 assert.throws(()=>extractCpfSmartFields(lines(['x'.repeat(150001)])),/too large/);
});
test('parsing is deterministic and does not hallucinate absent values',()=>{
 const d=extractCpfSmartFields(lines(['شهادة ملكية','الموقع: بلاد تونس','صفحة 1 من 1']));
 assert.equal(d.fields.registryReference.value,null);
 assert.equal(d.fields.areaSquareMetres.value,null);
 assert.deepEqual(d.missingPages,[]);
 assert.equal(d.mayUseForAutomaticApproval,false);
});
test('physical page index mismatch is noticed independently of paper page count',()=>{
 const d=extractCpfSmartFields(lines(['صفحة 1 من 2'], 'ocr-ar',0.99,2));
 assert.ok(d.issues.includes('INCONSISTENT_PAGE_COUNT'));
});

test('a distorted document heading is only a low-confidence candidate when CPF header is visible',()=>{
 const result=extractCpfSmartFields(lines(['إدارة الملكية العقارية شهعادة ملكية','المساحة: 735م م'], 'ocr-ar',0.95));
 assert.equal(result.documentKind,'CPF_OWNERSHIP_CERTIFICATE');
 assert.ok(result.issues.includes('LOW_CONFIDENCE'));
 assert.equal(result.fields.areaSquareMetres.value,735);
 assert.equal(result.mayUseForAutomaticApproval,false);
});

test('a malformed first-pass area is never repaired by guessing digits',()=>{
 const result=extractCpfSmartFields(lines(['اسم العقار : مثال المساحة: 10كم م التجزئة: 10كجزء']));
 assert.equal(result.fields.areaSquareMetres.value,null);
 assert.equal(result.mayUseForAutomaticApproval,false);
});
