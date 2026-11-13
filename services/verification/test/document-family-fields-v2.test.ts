import test from 'node:test';
import assert from 'node:assert/strict';
import {classifyLandPaper} from '../src/document-training-v2.ts';
import {extractPrivateLandDocumentFacts} from '../src/document-family-fields-v2.ts';
import type {RecognizedLine} from '../src/smart-fields.ts';

// Deliberately fictional: real Tunisian legal papers and their private owners
// remain outside the public source repository and CI.
const lines=(rows:readonly string[],confidence:number|null=0.99,page=1):RecognizedLine[] =>
 rows.map((text,i)=>({text,page,confidence,source:confidence===null?'pdf-text':'ocr-ar',
  ...(i===1?{bbox:{left:20,top:100,right:240,bottom:140}}:{})}));
const reading=(rows:readonly string[],confidence:number|null=0.99)=>{
 const ll=lines(rows,confidence);
 return extractPrivateLandDocumentFacts(ll,classifyLandPaper(ll));
};
const field=(r:ReturnType<typeof reading>,name:string)=>r.facts.find(x=>x.key===name);

test('ownership certificate: total estate m2 and abstract rights denominator are separate',()=>{
 const r=reading([
  'شهادة ملكية',
  'معرف الرسم العقاري: 999901',
  'المساحة الكلية: 20800 م²',
  'مجموع أجزاء الملكية: 20800 جزء',
  'القطعة عدد: 01',
  'CIN: 00011223'
 ]);
 assert.equal(r.kind,'OWNERSHIP_CERTIFICATE');
 assert.equal(field(r,'registerReference')?.value,'999901');
 assert.equal(field(r,'registeredAreaM2')?.value,'20800');
 assert.equal(field(r,'rightsDenominator')?.value,'20800');
 assert.equal(field(r,'registeredPlotNumber')?.value,'01');
 assert.equal(field(r,'soldAreaM2'),undefined);
 assert.equal(field(r,'salePriceTnd'),undefined);
 assert.equal(field(r,'registerReference')?.evidence[0].page,1);
 assert.deepEqual(field(r,'registerReference')?.evidence[0].bbox,
  {left:20,top:100,right:240,bottom:140});
 assert.equal(r.ownershipVerified,false);
 assert.equal(r.mayPublish,false);
 assert.equal(r.mayAutofillUnreviewed,false);
});
test('shared ownership remains separate document family and does not grant entire title',()=>{
 const r=reading([
   'شهادة اشتراك في الملكية',
   'معرف الرسم العقاري: 999902',
   'المساحة: 42910 م²',
   'مجموع أجزاء الملكية: 7000'
 ]);
 assert.equal(r.kind,'SHARED_OWNERSHIP_CERTIFICATE');
 assert.equal(field(r,'registeredAreaM2')?.value,'42910');
 assert.equal(field(r,'rightsDenominator')?.value,'7000');
 assert.equal(r.mayAutofillUnreviewed,false);
});
test('sale deed: sold plot and sold physical area do not inherit whole-title area',()=>{
 const r=reading([
   'إنجاز بيع',
   'الرسم العقاري الأم: 999903',
   'القطعة المبيعة عدد: 82',
   'المساحة المبيعة: 185.25 م²',
   'المساحة الكلية: 42910 م²',
   'البائع: شركة سهل وهمية',
   'المشتري: زينب بنت مالك',
   'الثمن الجملي: 28000 دينار',
   'القطعة المجاورة عدد 81'
 ]);
 assert.equal(r.kind,'SALE_DEED');
 assert.equal(field(r,'parentRegisterReference')?.value,'999903');
 assert.equal(field(r,'soldPlotNumber')?.value,'82');
 assert.equal(field(r,'soldAreaM2')?.value,'185.25');
 assert.equal(field(r,'registeredAreaM2'),undefined);
 assert.equal(field(r,'possessedAreaM2'),undefined);
 assert.equal(field(r,'seller')?.value,'شركة سهل وهمية');
 assert.equal(field(r,'buyer')?.value,'زينب بنت مالك');
 assert.equal(field(r,'salePriceTnd')?.value,'28000');
 assert.equal(r.access,'AUTHORIZED_SELLER_OR_REVIEWER_ONLY');
});
test('possession report area cannot be treated as registered/sold ownership area',()=>{
 const r=reading([
   'محضر معاينة تحويز',
   'معرف الرسم العقاري: 999903',
   'القطعة محل التحويز عدد: 81',
   'مساحة القطعة محل التحويز: 314.40 م²'
 ]);
 assert.equal(r.kind,'POSSESSION_REPORT');
 assert.equal(field(r,'possessedPlotNumber')?.value,'81');
 assert.equal(field(r,'possessedAreaM2')?.value,'314.40');
 assert.equal(field(r,'soldAreaM2'),undefined);
 assert.equal(field(r,'registeredAreaM2'),undefined);
 assert.equal(r.ownershipVerified,false);
});
test('transfer-card extraction stays tentative until a real template is evaluated',()=>{
 const r=reading([
   'بطاقة نقل الملكية',
   'معرف الرسم العقاري: 999905',
   'المساحة المنقولة: 105.50 م²',
   'الناقل: كريم بن سالم',
   'المنقول إليه: ليلى بنت أمين',
   'مرجع عملية النقل: FIC-001'
 ]);
 assert.equal(r.kind,'TRANSFER_CARD');
 assert.equal(field(r,'transferredAreaM2')?.value,'105.50');
 assert.equal(field(r,'transferor')?.value,'كريم بن سالم');
 assert.equal(field(r,'transferee')?.value,'ليلى بنت أمين');
 assert.equal(field(r,'transferReference')?.value,'FIC-001');
 assert.ok(r.issues.includes('UNTESTED_TRANSFER_TEMPLATE'));
 assert.equal(r.ownershipVerified,false);
});
test('receipt is a supporting financial proof, never a transfer card or land title',()=>{
 const r=reading([
   'QUITTANCE N° M000123',
   'MONTANT: 50,000',
   'Reçu de: personne fictive'
 ]);
 assert.equal(r.kind,'FINANCIAL_RECEIPT');
 assert.equal(field(r,'receiptNumber')?.value,'M000123');
 assert.equal(field(r,'receiptAmountTnd')?.value,'50.000');
 assert.equal(field(r,'receiptAmountTnd')?.status,'LOW_CONFIDENCE');
 assert.ok(r.issues.includes('SUPPORTING_RECEIPT_NOT_TITLE'));
 assert.equal(field(r,'registerReference'),undefined);
 assert.equal(r.mayPublish,false);
});
test('conflicting sale areas require correction, never pick first or last match',()=>{
 const r=reading([
   'عقد بيع',
   'المساحة المبيعة: 185.25 م²',
   'المساحة المبيعة: 314.40 م²'
 ]);
 assert.equal(field(r,'soldAreaM2')?.status,'CONFLICT');
 assert.equal(field(r,'soldAreaM2')?.value,null);
 assert.equal(field(r,'soldAreaM2')?.evidence.length,2);
 assert.ok(r.issues.includes('CONFLICTING_CRITICAL_FIELDS'));
});
test('repeated identical title references are corroborating, not conflicting',()=>{
 const r=reading(['شهادة ملكية',
   'معرف الرسم العقاري: 999904',
   'معرف الرسم العقاري: 999904']);
 assert.equal(field(r,'registerReference')?.value,'999904');
 assert.equal(field(r,'registerReference')?.status,'CANDIDATE');
 assert.equal(field(r,'registerReference')?.evidence.length,2);
});
test('masked actor is not interpreted as a real person',()=>{
 const r=reading(['عقد بيع','البائع: █████','المشتري: ليلى بنت شمس']);
 assert.equal(field(r,'seller')?.status,'MASKED');
 assert.equal(field(r,'seller')?.value,null);
 assert.equal(field(r,'buyer')?.value,'ليلى بنت شمس');
 assert.equal(r.mayAutofillUnreviewed,false);
});
test('low OCR confidence also lowers field confidence despite exact digits',()=>{
 const r=reading(['شهادة ملكية','معرف الرسم العقاري: 999911'],0.55);
 assert.equal(r.documentStatus,'LOW_CONFIDENCE');
 assert.equal(field(r,'registerReference')?.status,'LOW_CONFIDENCE');
 assert.ok(r.issues.includes('LOW_CONFIDENCE_CRITICAL_FIELDS'));
});
test('ambiguous and unknown family never scan incidental title-looking numbers',()=>{
 const a=reading(['شهادة ملكية','محضر معاينة تحويز','المساحة: 300 م²']);
 assert.equal(a.kind,'UNKNOWN');
 assert.equal(a.facts.length,0);
 assert.ok(a.issues.includes('UNKNOWN_OR_AMBIGUOUS_FAMILY'));
 const b=reading(['الرسم العقاري: 999991','المساحة: 300 م²']);
 assert.equal(b.kind,'UNKNOWN');
 assert.deepEqual(b.facts,[]);
});
test('CIN, dates and incidental prose cannot be mistaken for title or price',()=>{
 const r=reading([
  'إنجاز بيع','CIN: 12345678',
  'النص يذكر الرسم العقاري عدد 999903 داخل المعاملة',
  'وقع البيع على مساحة 185.25 م²',
  'السعر 28000',
  'البائع: كريم 12345678'
 ]);
 assert.equal(field(r,'registerReference')?.status,'ABSENT');
 assert.equal(field(r,'soldAreaM2')?.status,'ABSENT');
 assert.equal(field(r,'salePriceTnd')?.status,'ABSENT');
 assert.equal(field(r,'seller')?.status,'ABSENT');
});
test('comma three-digit area is ambiguous and must be reviewed',()=>{
 const r=reading(['شهادة ملكية','المساحة: 42,910 م²']);
 assert.equal(field(r,'registeredAreaM2')?.value,'42.910');
 assert.equal(field(r,'registeredAreaM2')?.status,'LOW_CONFIDENCE');
});
test('Arabic digits are preserved as strings without losing leading zeroes',()=>{
 const r=reading(['شهادة ملكية','معرف الرسم العقاري: ٠٠٩٩٩١']);
 assert.equal(field(r,'registerReference')?.value,'009991');
});
test('field extraction source limits reject malformed metadata',()=>{
 const ll=lines(['شهادة ملكية']);
 const classification=classifyLandPaper(ll);
 assert.throws(()=>extractPrivateLandDocumentFacts(
  [{...ll[0],page:99}],classification),/INVALID_LAND_FACT_SOURCE/);
 assert.throws(()=>extractPrivateLandDocumentFacts(
  [{...ll[0],confidence:3}],classification),/INVALID_LAND_FACT_SOURCE/);
});


test('possession requester is extracted only from a labelled role',()=>{
 const r=reading(['محضر معاينة تحويز',
  'طالب التحويز: ليلى بنت سالم','المساحة محل التحويز: 314.4 م²']);
 assert.equal(field(r,'possessionRequester')?.value,'ليلى بنت سالم');
 assert.equal(field(r,'possessionRequester')?.status,'CANDIDATE');
 assert.equal(r.ownershipVerified,false);
});
test('financial receipt payer is a role, not registered owner',()=>{
 const r=reading(['QUITTANCE N° Q0012','دافع المعاليم: فاطمة بنت كريم',
  'MONTANT: 50.000']);
 assert.equal(field(r,'receiptPayer')?.value,'فاطمة بنت كريم');
 assert.equal(field(r,'receiptPayer')?.status,'CANDIDATE');
 assert.equal(field(r,'seller'),undefined);
 assert.ok(r.issues.includes('SUPPORTING_RECEIPT_NOT_TITLE'));
});
test('masked or contradictory receipt payer never becomes an identity match',()=>{
 const r=reading(['QUITTANCE N° Q0012','دافع المعاليم: فاطمة بنت كريم',
  'دافع المعاليم: █████']);
 assert.equal(field(r,'receiptPayer')?.status,'CONFLICT');
 assert.equal(field(r,'receiptPayer')?.value,null);
});
