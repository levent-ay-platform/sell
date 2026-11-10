import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {analyzeOwnerUploadedEvidence,PrivateOcrAccessDenied} from '../src/seller-ocr-service.ts';
import type {AuthorizedUploadCase,Authenticator} from '../src/evidence-upload.ts';

const user=randomUUID(),caseId=randomUUID(),evidenceId=randomUUID(),token='synthetic-opaque-owner-session';
const bytes=Buffer.from('%PDF-1.4\nSYNTHETIC PRIVATE OCR OWNER-ONLY TEST\n%%EOF');
const granted:AuthorizedUploadCase={id:caseId,claimantKind:'PERSON',actorMayUpload:true,status:'PENDING',
  consent:{personUserId:user,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',policyVersion:'test',
    acceptedAt:'2026-11-10T10:00:00Z',revokedAt:null}};
const payload={format:'pdf',mayApproveOwnership:false,pages:[{page:1,method:'pdf-text',lines:[
  {text:'Certificat de propriété',page:1,confidence:null,source:'pdf-text'},
  {text:'Superficie: 240 m2',page:1,confidence:null,source:'pdf-text'},
  {text:'Titre foncier: 987654',page:1,confidence:null,source:'pdf-text'}]}]};
const args={bearerToken:token,caseId,evidenceId,bytes,mime:'application/pdf' as const,declaredArea:'240'};
function harness(){
 let identity=user,caseState:AuthorizedUploadCase|null=granted,reads=0,scans=0;
 const auth:Authenticator={authenticate:async t=>t===token && identity?{userId:identity}:null};
 const deps={auth,cases:{getUploadCase:async()=>{reads++;return caseState;}},
   worker:{extract:async()=>{scans++;return payload;}}};
 return {deps,setIdentity:(x:string)=>{identity=x;},setCase:(x:AuthorizedUploadCase|null)=>{caseState=x;},
    get reads(){return reads;},get scans(){return scans;}};
}
test('real OCR presentation is owner-only with page evidence; never claims verified ownership',async()=>{
 const h=harness();const result=await analyzeOwnerUploadedEvidence(args,h.deps);
 assert.equal(h.scans,1);assert.equal(h.reads,2);
 assert.equal(result.facts.find(x=>x.id==='areaSquareMetres')?.value,'240');
 assert.equal(result.facts.find(x=>x.id==='registryReference')?.value,'987654');
 assert.equal(result.declaredAreaComparison,'SAME_NUMBER_UNVERIFIED');
 assert.equal(result.ownerIdentityMatch,'NOT_STARTED');assert.equal(result.ownershipVerified,false);assert.equal(result.mayPublish,false);
});
test('revoked consent before OCR denies and never invokes worker',async()=>{
 const h=harness();h.setCase({...granted,consent:null});
 await assert.rejects(()=>analyzeOwnerUploadedEvidence(args,h.deps),PrivateOcrAccessDenied);
 assert.equal(h.scans,0);
});
test('revoked consent during OCR suppresses personal OCR response',async()=>{
 const h=harness();h.deps.worker.extract=async()=>{h.setCase({...granted,consent:null});return payload;};
 await assert.rejects(()=>analyzeOwnerUploadedEvidence(args,h.deps),PrivateOcrAccessDenied);
 assert.equal(h.reads,2);
});
test('session changes during OCR deny result without leaking fields',async()=>{
 const h=harness();h.deps.worker.extract=async()=>{h.setIdentity(randomUUID());return payload;};
 await assert.rejects(()=>analyzeOwnerUploadedEvidence(args,h.deps),PrivateOcrAccessDenied);
});
test('user not owning case and companies are excluded',async()=>{
 const h=harness();h.setCase({...granted,actorMayUpload:false});
 await assert.rejects(()=>analyzeOwnerUploadedEvidence(args,h.deps),PrivateOcrAccessDenied);
 h.setCase({...granted,claimantKind:'COMPANY'});
 await assert.rejects(()=>analyzeOwnerUploadedEvidence(args,h.deps),PrivateOcrAccessDenied);
 assert.equal(h.scans,0);
});
test('untrusted OCR payload cannot assert approval and is rejected',async()=>{
 const h=harness();h.deps.worker.extract=async()=>({...payload,mayApproveOwnership:true});
 await assert.rejects(()=>analyzeOwnerUploadedEvidence(args,h.deps),/INVALID_OCR_PAYLOAD/);
});
