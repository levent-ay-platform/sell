import test from 'node:test';
import assert from 'node:assert/strict';
import {preflightIndividualCapture} from '../src/capture.ts';
import type {CapturePreflightInput,PageCaptureMetadata} from '../src/capture.ts';
const SHA_A='a'.repeat(64), SHA_B='b'.repeat(64);
const page=(n:number,sha=SHA_A,extra:Partial<PageCaptureMetadata>={}):PageCaptureMetadata=>({
  logicalPage:n,filePage:1,fileSha256:sha,mime:'image/jpeg',sizeBytes:800000,qualityWarnings:[],...extra
});
const doc=(pages:PageCaptureMetadata[],extras:Partial<CapturePreflightInput>={}):CapturePreflightInput=>({
  claimantKind:'PERSON',consentGiven:true,printedTotalPages:2,pages,...extras
});
test('two different scans with consent can go only to private OCR',()=>{
  const x=preflightIndividualCapture(doc([page(1,SHA_A),page(2,SHA_B)]));
  assert.equal(x.status,'READY_FOR_PRIVATE_OCR');
  assert.equal(x.canProcessInPrivateWorker,true);
  assert.equal(x.mayPublish,false);
  assert.equal(x.ownershipVerified,false);
});
test('missing page 2 is not treated as complete',()=>{
  const x=preflightIndividualCapture(doc([page(1)]));
  assert.equal(x.status,'NEEDS_RECAPTURE');
  assert.deepEqual(x.missingPages,[2]);
  assert.equal(x.canProcessInPrivateWorker,true); // OCR existing page before recapture
  assert.ok(x.actions.includes('SEND_FULL_DOCUMENT'));
});
test('unknown printed page count always requires confirmation',()=>{
  const x=preflightIndividualCapture(doc([page(1)],{printedTotalPages:null}));
  assert.equal(x.status,'NEEDS_RECAPTURE');
  assert.ok(x.actions.includes('CONFIRM_PAGE_NUMBERING'));
  assert.equal(x.canProcessInPrivateWorker,true); // OCR should discover printed page count
});
test('a missing informed-consent record prevents OCR',()=>{
  const x=preflightIndividualCapture(doc([page(1,SHA_A),page(2,SHA_B)],{consentGiven:false}));
  assert.equal(x.status,'NEEDS_CONSENT');
  assert.equal(x.canProcessInPrivateWorker,false);
});
test('company sellers excluded',()=>{
  const x=preflightIndividualCapture(doc([page(1,SHA_A),page(2,SHA_B)],{claimantKind:'COMPANY'}));
  assert.equal(x.status,'REJECTED_INPUT');
});
test('duplicate image bytes supplied as two pages are rejected',()=>{
  const x=preflightIndividualCapture(doc([page(1),page(2)]));
  assert.equal(x.status,'REJECTED_INPUT');
  assert.ok(x.findings.includes('DUPLICATE_CAPTURE'));
});
test('the same multi-page PDF may contain two separate pages',()=>{
  const a=page(1,SHA_A,{mime:'application/pdf',filePage:1});
  const b=page(2,SHA_A,{mime:'application/pdf',filePage:2});
  assert.equal(preflightIndividualCapture(doc([a,b])).status,'READY_FOR_PRIVATE_OCR');
});
test('impossible duplicates in logical page numbering are rejected',()=>{
  const x=preflightIndividualCapture(doc([page(1,SHA_A),page(1,SHA_B)]));
  assert.equal(x.status,'REJECTED_INPUT');
  assert.ok(x.findings.includes('PAGE_NUMBER_DUPLICATE'));
});
test('bad sha and mime cannot enter private processing',()=>{
  assert.equal(preflightIndividualCapture(doc([page(1,'ABC'),page(2,SHA_B)])).status,'REJECTED_INPUT');
  assert.equal(preflightIndividualCapture(doc([page(1,SHA_A,{mime:'image/gif' as any}),page(2,SHA_B)])).status,'REJECTED_INPUT');
});
test('low quality can only request retake, never declare forgery',()=>{
  const x=preflightIndividualCapture(doc([page(1,SHA_A,{qualityWarnings:['LOW_CONTRAST','POSSIBLE_BLUR']}),page(2,SHA_B)]));
  assert.equal(x.status,'NEEDS_RECAPTURE');
  assert.deepEqual(x.qualityReviewPages,[1]);
  assert.equal(x.canProcessInPrivateWorker,true); // advisory flag, not a document invalidation
  assert.equal(x.mayPublish,false);
});
test('unrecognized quality warnings are rejected, not passed to UI',()=>{
  assert.equal(preflightIndividualCapture(doc([page(1,SHA_A,{qualityWarnings:['OWNERSHIP_VERIFIED' as any]}),page(2,SHA_B)])).status,'REJECTED_INPUT');
});
test('filePage 2 is impossible for an image; valid for a PDF',()=>{
  assert.equal(preflightIndividualCapture(doc([page(1,SHA_A,{filePage:2}),page(2,SHA_B)])).status,'REJECTED_INPUT');
});
test('extra out of range page cannot be treated as complete',()=>{
  assert.equal(preflightIndividualCapture(doc([page(1,SHA_A),page(3,SHA_B)])).status,'REJECTED_INPUT');
});
