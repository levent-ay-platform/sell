import test from 'node:test';
import assert from 'node:assert/strict';
import {readUntrustedOcrDocument} from '../src/reader.ts';
import {planNextVerificationSteps} from '../src/readiness.ts';

const input=(lines:string[], method:'pdf-text'|'ocr'='ocr',confidence:number|null=.99)=>({
 format:'image',mayApproveOwnership:false,pages:[{page:1,method,lines:lines.map(text=>({text,page:1,confidence,source:method==='ocr'?'ocr-ar':'pdf-text'}))}]
});

test('one actual-image-like paper layout with 1 of 2 requests second page',()=>{
 const r=planNextVerificationSteps(readUntrustedOcrDocument(input([
   'شهادة ملكية','معرف الرسم العقاري : 987654','المساحة: 843 م',
   'موضوع الملكية: 512 جزء','تاريخ آخر تحيين: 2016/01/22','صفحة 1 من 2'
 ])));
 assert.ok(r.reasons.includes('MISSING_DOCUMENT_PAGES'));
 assert.ok(r.reasons.includes('HISTORIC_DATE_IS_NOT_CERTIFICATE_ISSUE_DATE'));
 assert.ok(r.followUps.includes('SEND_FULL_DOCUMENT'));
 assert.equal(r.mayPublish,false);
});
test('unreadable page footer cannot be treated as complete document',()=>{
 const r=planNextVerificationSteps(readUntrustedOcrDocument(input([
   'شهادة ملكية','معرف الرسم العقاري : 987654','المساحة: 843 م'
 ])));
 assert.ok(r.reasons.includes('PAGE_NUMBER_UNREADABLE'));
 assert.ok(r.followUps.includes('RETAKE_PAGE_FOOTER'));
 assert.equal(r.needsAdditionalImages,true);
});
test('low-confidence title and area are never silently accepted',()=>{
 const r=planNextVerificationSteps(readUntrustedOcrDocument(input([
   'شهادة ملكية','معرف الرسم العقاري : 987654','المساحة: 843 م','صفحة 1 من 1'
 ],'ocr',0.6)));
 assert.ok(r.followUps.includes('RETAKE_REGISTRY_REFERENCE'));
 assert.ok(r.followUps.includes('RETAKE_AREA'));
 assert.equal(r.ownershipVerified,false);
});
test('even perfect-looking complete text PDF needs official owner check',()=>{
 const r=planNextVerificationSteps(readUntrustedOcrDocument(input([
   'Certificat de propriété','Titre foncier: 987654', 'Superficie: 843 m2',
   'Page 1 sur 1','Date de délivrance: 2026-11-10'
 ],'pdf-text',null)));
 assert.equal(r.needsAdditionalImages,false);
 assert.deepEqual(r.followUps,['PROVIDE_RECENT_CPF_PROOF','AUTHORIZED_REVIEW_REQUIRED']);
 assert.equal(r.mayPublish,false);
 assert.ok(r.reasons.includes('PERSON_IDENTITY_NOT_YET_CORROBORATED'));
});
test('conflicting title numbers require targeted recapture, never choose one',()=>{
 const r=planNextVerificationSteps(readUntrustedOcrDocument(input([
   'شهادة ملكية', 'معرف الرسم العقاري: 987654', 'معرف الرسم العقاري: 987655',
   'المساحة: 843 م','صفحة 1 من 1'
 ])));
 assert.equal(r.criticalFields.registryReference,'CONFLICT');
 assert.ok(r.followUps.includes('RETAKE_REGISTRY_REFERENCE'));
 assert.equal(r.mayPublish,false);
});
