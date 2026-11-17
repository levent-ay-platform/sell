import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID,createHash} from 'node:crypto';
import {uploadIndividualEvidence} from '../src/evidence-upload.ts';
import type {UploadDependencies,EvidenceRow} from '../src/evidence-upload.ts';

test('per-evidence Vault context is server-generated and ephemeral plaintext key always zeroized',async()=>{
 const caseId=randomUUID(), owner=randomUUID(), records:EvidenceRow[]=[], captures:{caseId:string;evidenceId:string}[]=[];
 const raw=Buffer.from('%PDF-1.7\nONLY FAKE ANONYMOUS EVIDENCE');
 const key=new Uint8Array(32).fill(72);
 const deps:UploadDependencies={
  auth:{authenticate:async()=>({userId:owner})},
  scanner:{scan:async()=> 'CLEAN'},
  cases:{inCaseLock:async(_c,work)=>work(),getUploadCase:async()=>({id:caseId,claimantKind:'PERSON',actorMayUpload:true,status:'PENDING',consent:{personUserId:owner,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',policyVersion:'ci',acceptedAt:'2026-11-10',revokedAt:null}}),findReplay:async()=>null,attachEvidence:async row=>{records.push(row);}},
  objects:{putPrivate:async()=>{},deletePrivate:async()=>{}},
  generateDataKey:async scope=>{captures.push(scope);return {plaintextKey:key,wrappedKey:'',keyVersion:'v1'};}
 };
 const result=await uploadIndividualEvidence({bearerToken:'fake',caseId,idempotencyKey:'SYNTHETIC_ONLY_KEY_1234',bytes:raw,evidenceKind:'OWNERSHIP_CERTIFICATE',pageNumber:1},deps);
 assert.equal(result.ok,false);assert.equal(records.length,0);
 assert.equal(captures.length,1);
 assert.equal(captures[0]?.caseId,caseId);
 assert.match(captures[0]!.evidenceId,/^[0-9a-f]{8}-/);
 assert.equal(key.every(v=>v===0),true,'must zeroize even when Vault returned malformed metadata');
});
