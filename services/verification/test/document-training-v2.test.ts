import test from 'node:test';
import assert from 'node:assert/strict';
import {classifyLandPaper,LAND_DOCUMENT_FIELD_RULES} from '../src/document-training-v2.ts';
import type {RecognizedLine} from '../src/smart-fields.ts';

// All fixtures are *fictional*. No user-supplied identity/ownership evidence.
const text=(rows:string[],page=1,confidence:number|null=0.99):RecognizedLine[]=>
  rows.map(line=>({text:line,page,confidence,source:'ocr-ar'}));

test('classifies ownership certificate, separate share parts and full list of owners',()=>{
  const r=classifyLandPaper([...text(['الجمهورية التونسية','شهادة ملكية','معرف الرسم العقاري: 999777','المساحة: 900 م']),
    ...text(['1/4 اسم تجريبي 45,5 جزء','شروط أو ملاحظات أخرى'],2)]);
  assert.equal(r.kind,'OWNERSHIP_CERTIFICATE');
  assert.equal(r.status,'CANDIDATE');
  assert.ok(r.fieldsToExtract.some(x=>x.key==='all_rights_holders_across_pages'&&x.priority==='REQUIRED'));
  assert.ok(r.fieldsToExtract.some(x=>x.key==='ownership_parts_denominator'));
  assert.equal(r.mayVerifyOwnership,false);
  assert.equal(r.mayPublish,false);
  assert.equal(r.mayAutofillUnreviewed,false);
});
test('shared ownership heading takes precedence over general property term',()=>{
  const r=classifyLandPaper(text(['شهادة اشتراك في الملكية','معرف الرسم العقاري: 999001']));
  assert.equal(r.kind,'SHARED_OWNERSHIP_CERTIFICATE');
  assert.ok(r.fieldsToExtract.some(x=>x.key==='all_coowners_and_parts_across_pages'));
});
test('sale deed with official-style heading and supporting certificate mention',()=>{
  const r=classifyLandPaper(text([
    'مكتب الأستاذ مثال',
    '*** إنجاز بيع ***',
    'البائع باسم وهمي والمشتري باسم وهمي',
    'ضمن العقد ترسيم الملكية وشهادة ملكية في هذا المرجع العقاري وبيانات الشركاء والحقوق لأغراض البحث والمراجعة',
    'وصل قبض بتاريخ سابق ضمن الملف ومرفق حافظة الوثائق والمراجع الخاصة بعملية التسجيل',
  ]));
  assert.equal(r.kind,'SALE_DEED');
  assert.ok(r.fieldsToExtract.some(x=>x.key==='sold_area_m2'&&x.priority==='REQUIRED'));
  assert.ok(r.fieldsToExtract.some(x=>x.key==='register_parent_reference'));
});
test('sale deed does not equate parent registered area with sale area',()=>{
 const p=LAND_DOCUMENT_FIELD_RULES.SALE_DEED;
 assert.ok(p.some(x=>x.key==='sold_area_m2'));
 assert.ok(p.some(x=>x.key==='parent_registered_area_m2'));
 assert.notEqual(p.find(x=>x.key==='sold_area_m2')?.note,
  p.find(x=>x.key==='parent_registered_area_m2')?.note);
});
test('possession report is not treated as registered ownership',()=>{
 const r=classifyLandPaper(text(['محضر معاينة تحويز','عدل منفذ بإحدى المحاكم']));
 assert.equal(r.kind,'POSSESSION_REPORT');
 assert.equal(r.mayVerifyOwnership,false);
 assert.ok(r.fieldsToExtract.some(x=>x.key==='observed_possession_area_m2'));
});
test('transfer card is reserved but NOT claimed evaluated on a real sample',()=>{
 const r=classifyLandPaper(text(['بطاقة نقل الملكية','الرسم العقاري: 999111']));
 assert.equal(r.kind,'TRANSFER_CARD');
 assert.ok(r.warnings.includes('TRANSFER_TEMPLATE_NOT_EVALUATED'));
 assert.equal(r.mayAutofillUnreviewed,false);
});
test('Quittance is supporting-only and NEVER mislabeled as a transfer card',()=>{
 const r=classifyLandPaper(text(['REPUBLIQUE TUNISIENNE','QUITTANCE N° A000000','REÇU DE: PERSONNE EXEMPLE']));
 assert.equal(r.kind,'FINANCIAL_RECEIPT');
 assert.ok(r.warnings.includes('SUPPORTING_RECEIPT_ONLY'));
 assert.notEqual(r.kind,'TRANSFER_CARD');
 assert.equal(r.mayPublish,false);
});
test('financial form title written in Arabic',()=>{
 assert.equal(classifyLandPaper(text(['وزارة المالية','وصل قبض','المبلغ: 50,000'])).kind,'FINANCIAL_RECEIPT');
});
test('a later sale-deed page alone cannot be inferred from mention of certificate',()=>{
 const r=classifyLandPaper(text(['شهادة ملكية للرجوع إليها فقط، لا تمثل عنوان هذا المحضر'],2));
 assert.equal(r.kind,'UNKNOWN');
 assert.deepEqual(r.warnings,['NO_SUPPORTED_TITLE']);
});
test('multiple distinct short document headings require manual routing',()=>{
 const r=classifyLandPaper(text(['شهادة ملكية','محضر معاينة تحويز']));
 assert.equal(r.kind,'UNKNOWN');
 assert.equal(r.status,'AMBIGUOUS');
 assert.ok(r.warnings.includes('AMBIGUOUS_DOCUMENT_FAMILY'));
});
test('low-confidence heading cannot be treated as reliable type',()=>{
 const r=classifyLandPaper(text(['شهادة ملكية'],1,0.61));
 assert.equal(r.kind,'OWNERSHIP_CERTIFICATE');
 assert.equal(r.status,'LOW_CONFIDENCE');
 assert.ok(r.warnings.includes('LOW_TITLE_CONFIDENCE'));
});
test('no title with only numeric registry is UNKNOWN (never hallucinate type)',()=>{
 assert.equal(classifyLandPaper(text(['الرسم العقاري: 987654','المساحة 250 م'])).kind,'UNKNOWN');
});
test('owner papers do not imply authority to sell the entire estate',()=>{
 for(const kind of ['OWNERSHIP_CERTIFICATE','SHARED_OWNERSHIP_CERTIFICATE','TRANSFER_CARD','SALE_DEED','POSSESSION_REPORT','FINANCIAL_RECEIPT'] as const){
  assert.equal(LAND_DOCUMENT_FIELD_RULES[kind].length>0,true);
  const source={
   OWNERSHIP_CERTIFICATE:'شهادة ملكية',SHARED_OWNERSHIP_CERTIFICATE:'شهادة اشتراك في الملكية',
   TRANSFER_CARD:'بطاقة نقل',SALE_DEED:'إنجاز بيع',
   POSSESSION_REPORT:'محضر معاينة تحويز',FINANCIAL_RECEIPT:'QUITTANCE N'
  }[kind];
  const r=classifyLandPaper(text([source]));
  assert.equal(r.mayVerifyOwnership,false);
  assert.equal(r.mayPublish,false);
  assert.equal(r.mayAutofillUnreviewed,false);
 }
});
test('rejects impossible metadata and oversized OCR input',()=>{
 assert.throws(()=>classifyLandPaper(text(['شهادة ملكية'],1,2)),/INVALID_DOCUMENT_OCR_LINE/);
 assert.throws(()=>classifyLandPaper(text(['شهادة ملكية'],9)),/INVALID_DOCUMENT_OCR_LINE/);
 assert.throws(()=>classifyLandPaper(text(['x'.repeat(150001)])),/DOCUMENT_TEXT_TOO_LARGE/);
});
test('no raw OCR text is returned in classifier evidence',()=>{
 const sensitive='شهادة ملكية واسم شخص افتراضي';
 const result=classifyLandPaper(text([sensitive]));
 assert.equal(result.kind,'OWNERSHIP_CERTIFICATE');
 assert.equal(JSON.stringify(result).includes('شخص افتراضي'),false);
 assert.equal(result.evidence[0]?.rule,'OWNERSHIP_CERTIFICATE_HEADING');
});
test('French title cases remain distinct',()=>{
 assert.equal(classifyLandPaper(text(['Certificat de propriété'])).kind,'OWNERSHIP_CERTIFICATE');
 assert.equal(classifyLandPaper(text(['Acte de vente'])).kind,'SALE_DEED');
});
