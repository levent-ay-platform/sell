import test from 'node:test';
import assert from 'node:assert/strict';
import {classifyLandPaper} from '../src/document-training-v2.ts';
import {extractPrivateLandDocumentFacts} from '../src/document-family-fields-v2.ts';
import {assessPrivateEvidenceReadiness} from '../src/evidence-readiness-v2.ts';
import type {RecognizedLine} from '../src/smart-fields.ts';
const get=(rows:string[],confidence=.99)=>{
 const lines:RecognizedLine[]=rows.map(text=>({page:1,text,confidence,source:'ocr-ar'}));
 const classification=classifyLandPaper(lines);
 const facts=extractPrivateLandDocumentFacts(lines,classification);
 return {classification,facts};
};
test('new real-format receipt heading is a distinct supporting-only class',()=>{
 const {classification,facts}=get([
  'وصل تسلم مطلب خدمة و استخلاص معاليم',
  'العدد: 99812',
  'التاريخ: 2022/05/13',
  'معرف الرسم العقاري: 998177 نابل',
  'المبلغ الجملي: 210.000',
  'طالب الخدمة: زهرة بنت يوسف'
 ]);
 assert.equal(classification.kind,'PROPERTY_SERVICE_RECEIPT');
 assert.ok(classification.warnings.includes('SUPPORTING_SERVICE_RECEIPT_ONLY'));
 assert.equal(facts.facts.find(f=>f.key==='serviceRequestNumber')?.value,'99812');
 assert.equal(facts.facts.find(f=>f.key==='serviceRequestDate')?.value,'2022-05-13');
 assert.equal(facts.facts.find(f=>f.key==='registerReference')?.value,'998177');
 assert.equal(facts.facts.find(f=>f.key==='receiptAmountTnd')?.value,'210.000');
 assert.equal(facts.facts.find(f=>f.key==='serviceApplicant')?.value,'زهرة بنت يوسف');
 assert.ok(facts.issues.includes('SUPPORTING_SERVICE_RECEIPT_NOT_TITLE'));
 assert.equal(facts.mayPublish,false);
});
test('service receipt is accepted for review but has lowest evidence tier',()=>{
 const {classification,facts}=get(['وصل تسلم مطلب خدمة واستخلاص معاليم','العدد: 77712']);
 const score=assessPrivateEvidenceReadiness(classification,facts);
 assert.equal(score.documentSupportLevel,1);
 assert.equal(score.intakeDecision,'ACCEPT_FOR_PRIVATE_REVIEW');
 assert.ok(score.provisionalProgressPoints<=17);
 assert.equal(score.officialOwnershipVerified,false);
 assert.equal(score.displayAsVerifiedSeller,false);
 assert.equal(score.mayPublish,false);
});
test('receipt is not automatically mistaken for certificate, sale or transfer',()=>{
 const service=get(['وصل تسلّم مطلب خدمة واستخلاص معاليم','العدد: 222']);
 const quittance=get(['QUITTANCE N° S0001','MONTANT: 50,000']);
 const transfer=get(['بطاقة نقل الملكية','المساحة المنقولة: 150.00 م²']);
 assert.equal(service.classification.kind,'PROPERTY_SERVICE_RECEIPT');
 assert.equal(quittance.classification.kind,'FINANCIAL_RECEIPT');
 assert.equal(transfer.classification.kind,'TRANSFER_CARD');
});
test('unknown kind is not rejected from safe private intake, but cannot gain score or badge',()=>{
 const {classification,facts}=get(['نص غير مقروء','المساحة: 150 م²']);
 const r=assessPrivateEvidenceReadiness(classification,facts);
 assert.equal(r.documentSupportLevel,0);
 assert.equal(r.provisionalProgressPoints,0);
 assert.equal(r.intakeDecision,'ACCEPT_FOR_PRIVATE_REVIEW');
 assert.equal(r.officialOwnershipVerified,false);
 assert.ok(r.issues.includes('DOCUMENT_UNCLASSIFIED'));
});
test('owned-title support level is stronger than sale, possession, or receipt (heuristic only)',()=>{
 const titles=[
  ['شهادة ملكية',5],
  ['شهادة اشتراك في الملكية',4],
  ['عقد بيع',3],
  ['بطاقة نقل الملكية',3],
  ['محضر معاينة تحويز',2],
  ['وصل تسلم مطلب خدمة واستخلاص معاليم',1],
  ['QUITTANCE N° Z0001',1]
 ] as const;
 for(const [heading,level] of titles){
  const {classification,facts}=get([heading]);
  const r=assessPrivateEvidenceReadiness(classification,facts);
  assert.equal(r.documentSupportLevel,level);
  assert.ok(r.provisionalProgressPoints<=75);
  assert.equal(r.reservedOfficialVerificationPoints,25);
  assert.equal(r.sellerIdentityVerified,false);
 }
});
test('preliminary text matches add review points but never legal verification',()=>{
 const {classification,facts}=get([
  'شهادة ملكية','معرف الرسم العقاري: 999551','المساحة: 510 م²'
 ]);
 const before=assessPrivateEvidenceReadiness(classification,facts);
 const after=assessPrivateEvidenceReadiness(
  classification,facts,'SAME_TEXT_UNVERIFIED','SAME_REFERENCE_UNVERIFIED');
 assert.equal(after.provisionalProgressPoints-before.provisionalProgressPoints,25);
 assert.equal(after.officialOwnershipVerified,false);
 assert.equal(after.sellerIdentityVerified,false);
 assert.equal(after.displayAsVerifiedSeller,false);
 assert.equal(after.mayPublish,false);
});
test('text or parcel mismatch is flagged, never rewarded for apparent agreement',()=>{
 const {classification,facts}=get(['عقد بيع','المساحة المبيعة: 215 م²']);
 const r=assessPrivateEvidenceReadiness(classification,facts,
  'DIFFERENT_TEXT','DIFFERENT_SCOPE');
 assert.ok(r.issues.includes('IDENTITY_TEXT_DIFFERS'));
 assert.ok(r.issues.includes('PROPERTY_SCOPE_DIFFERS'));
 assert.equal(r.components.preliminaryIdentityTextPoints,0);
 assert.equal(r.components.preliminaryPropertyScopePoints,0);
 assert.equal(r.mayPublish,false);
});
test('low-quality classification cannot gain type or legibility points',()=>{
 const {classification,facts}=get(['شهادة ملكية','المساحة: 510 م²'],.55);
 const r=assessPrivateEvidenceReadiness(classification,facts);
 assert.equal(r.components.documentEvidencePoints,0);
 assert.equal(r.components.sourceLegibilityPoints,0);
 assert.ok(r.issues.includes('TYPE_LOW_CONFIDENCE'));
});
test('conflicting fields never increase legibility or text-match reward',()=>{
 const {classification,facts}=get([
  'عقد بيع','المساحة المبيعة: 185.25 م²','المساحة المبيعة: 314.40 م²'
 ]);
 const r=assessPrivateEvidenceReadiness(
  classification,facts,'SAME_TEXT_UNVERIFIED','SAME_REFERENCE_UNVERIFIED');
 assert.ok(r.issues.includes('DOCUMENT_FIELDS_CONFLICT'));
 assert.equal(r.components.preliminaryIdentityTextPoints,0);
 assert.equal(r.components.preliminaryPropertyScopePoints,0);
 assert.equal(r.components.sourceLegibilityPoints,0);
});
test('the scoring contract rejects mixed kind or invalid comparison statuses',()=>{
 const a=get(['عقد بيع']),b=get(['شهادة ملكية']);
 assert.throws(()=>assessPrivateEvidenceReadiness(a.classification,b.facts),
  /INVALID_EVIDENCE_PROGRESS_INPUT/);
 assert.throws(()=>assessPrivateEvidenceReadiness(
  a.classification,a.facts,'VERIFIED' as never),/INVALID_EVIDENCE_PROGRESS_INPUT/);
});
test('malformed service receipt date is not guessed',()=>{
 const {classification,facts}=get([
  'وصل تسلم مطلب خدمة و استخلاص معاليم',
  'التاريخ: 2022/02/31'
 ]);
 assert.equal(classification.kind,'PROPERTY_SERVICE_RECEIPT');
 assert.equal(facts.facts.find(f=>f.key==='serviceRequestDate')?.status,'ABSENT');
});
test('scoring never returns a public-facing verification flag for every document type',()=>{
 const headings=['شهادة ملكية','شهادة اشتراك في الملكية','عقد بيع',
   'بطاقة نقل الملكية','محضر معاينة تحويز','وصل تسلم مطلب خدمة واستخلاص معاليم',
   'QUITTANCE N° A0001','UNKNOWN'] as const;
 for(const heading of headings){
  const {classification,facts}=get([heading]);
  const score=assessPrivateEvidenceReadiness(classification,facts);
  assert.equal(score.intakeDecision,'ACCEPT_FOR_PRIVATE_REVIEW');
  assert.equal(score.officialOwnershipVerified,false);
  assert.equal(score.sellerIdentityVerified,false);
  assert.equal(score.mayPublish,false);
  assert.equal(score.displayAsVerifiedSeller,false);
 }
});
