import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {previewOwnerCin,CinPreviewDenied} from '../src/private-cin-v5-service.ts';
import type {AuthorizedUploadCase,Authenticator} from '../src/evidence-upload.ts';

const user=randomUUID(),caseId=randomUUID(),token='synthetic-private-verified-owner-token';
const bytes=Buffer.concat([Buffer.from([255,216,255,224]),Buffer.alloc(32,65)]);
const granted:AuthorizedUploadCase={
  id:caseId,claimantKind:'PERSON',actorMayUpload:true,status:'PENDING',
  consent:{personUserId:user,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',
    policyVersion:'synthetic_test_only',acceptedAt:'2026-11-12T10:00:00Z',revokedAt:null}
};
const request={bearerToken:token,caseId,bytes,mime:'image/jpeg' as const};
const reading={surname:'المثال',givenName:'سالم',cinLast4:null,
  birthDate:null,confidencePercent:94};
function harness() {
  let identity=user,caseState:AuthorizedUploadCase|null=granted,run=0;
  let response={...reading};
  const auth:Authenticator={authenticate:async t=>t===token&&identity?{userId:identity}:null};
  const deps={auth,cases:{getUploadCase:async()=>caseState},
    worker:{readFront:async()=>{run++;return response;}}};
  return {deps,setCase:(c:AuthorizedUploadCase|null)=>{caseState=c;},
    setIdentity:(v:string)=>{identity=v;},
    setResponse:(v:typeof reading)=>{response=v;},
    get runs(){return run;}};
}
test('only consented authenticated natural-person owner may read private Huawei v5 fields',async()=>{
  const h=harness();
  assert.deepEqual(await previewOwnerCin(request,h.deps),reading);
  assert.equal(h.runs,1);
});
test('reject unauthenticated, invalid file magic, wrong case and unconsented owners',async()=>{
  const h=harness();
  h.setIdentity('');
  await assert.rejects(previewOwnerCin(request,h.deps),CinPreviewDenied);
  h.setIdentity(user);
  await assert.rejects(previewOwnerCin({...request,bytes:Buffer.alloc(34,0)},h.deps),
    CinPreviewDenied);
  h.setCase({...granted,consent:null});
  await assert.rejects(previewOwnerCin(request,h.deps),CinPreviewDenied);
  h.setCase({...granted,claimantKind:'COMPANY'});
  await assert.rejects(previewOwnerCin(request,h.deps),CinPreviewDenied);
  assert.equal(h.runs,0);
});
test('revoked consent during OCR suppresses extracted personal fields',async()=>{
  const h=harness();
  h.deps.worker.readFront=async()=>{h.setCase({...granted,consent:null});return reading;};
  await assert.rejects(previewOwnerCin(request,h.deps),CinPreviewDenied);
});
test('session swap during processing is denied',async()=>{
  const h=harness();
  h.deps.worker.readFront=async()=>{h.setIdentity(randomUUID());return reading;};
  await assert.rejects(previewOwnerCin(request,h.deps),CinPreviewDenied);
});
test('foreign Latin OCR garbage is never accepted as Tunisian surname',async()=>{
  const h=harness();
  h.setResponse({...reading,surname:'RATIO'});
  await assert.rejects(previewOwnerCin(request,h.deps),/PRIVATE_CIN_MODEL_INVALID/);
});
test('conflicted or partial Arabic name fails closed, never invents a verified ID',async()=>{
  const h=harness();
  h.setResponse({...reading,surname:null});
  const result=await previewOwnerCin(request,h.deps);
  assert.equal(result.surname,null);assert.equal(result.givenName,null);
  assert.equal(result.confidencePercent,0);
});
