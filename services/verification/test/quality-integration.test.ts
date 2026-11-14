import test from 'node:test';
import assert from 'node:assert/strict';
import {readUntrustedOcrDocument,parseOcrWorkerOutput} from '../src/reader.ts';
import {planNextVerificationSteps} from '../src/readiness.ts';
const make=(qualityWarnings:string[]|undefined)=>({format:'image',mayApproveOwnership:false,pages:[{
  page:1,method:'ocr',...(qualityWarnings===undefined?{}:{qualityWarnings}),lines:[
    {text:'شهادة ملكية',confidence:0.97,source:'ocr-ar',page:1},
    {text:'صفحة 1 من 2',confidence:0.97,source:'ocr-ar',page:1},
    {text:'المساحة: 843 م',confidence:0.97,source:'ocr-ar',page:1}
  ]
}]});
test('quality warning becomes safe retake suggestion; no verified ownership',()=>{
  const result=readUntrustedOcrDocument(make(['POSSIBLE_BLUR','LOW_CONTRAST']));
  assert.deepEqual(result.qualityReviewPages,[1]);
  const next=planNextVerificationSteps(result);
  assert.ok(next.reasons.includes('IMAGE_QUALITY_REVIEW_REQUIRED'));
  assert.ok(next.followUps.includes('RETAKE_LOW_QUALITY_PAGE'));
  assert.deepEqual(result.extraction.missingPages,[2]);
  assert.equal(next.mayPublish,false);
});
test('missing quality metadata is backward compatible but never ownership proof',()=>{
  const result=readUntrustedOcrDocument(make(undefined));
  assert.deepEqual(result.qualityReviewPages,[]);
  assert.equal(result.mayPublish,false);
});
test('quality warnings are schema validated to prevent hostile untrusted payloads',()=>{
  assert.throws(()=>parseOcrWorkerOutput(make(['DOCUMENT_VERIFIED'])),/INVALID_OCR_QUALITY/);
  assert.throws(()=>parseOcrWorkerOutput(make(['LOW_CONTRAST','LOW_CONTRAST','LOW_CONTRAST','LOW_CONTRAST','LOW_CONTRAST'])),/INVALID_OCR_QUALITY/);
});
