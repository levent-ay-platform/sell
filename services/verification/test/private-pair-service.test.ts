import test from 'node:test';
import assert from 'node:assert/strict';
import {createCipheriv,createHash,randomBytes,randomUUID} from 'node:crypto';
import {analyzeAuthorizedPrivateEvidencePair as analyze,PrivatePairAccessDenied,
  PrivatePairUnavailable} from '../src/private-pair-service.ts';
import type {AuthorizedUploadCase,EvidenceRow} from '../src/evidence-upload.ts';

const userId=randomUUID(),caseId=randomUUID(),certificateId=randomUUID(),identityId=randomUUID();
const token='synthetic-pair-bearer-token-long-enough';
const key=randomBytes(32);
const consent:AuthorizedUploadCase={id:caseId,claimantKind:'PERSON',actorMayUpload:true,
  status:'PENDING',consent:{personUserId:userId,purpose:'PROPERTY_OWNERSHIP_EVIDENCE',
  policyVersion:'synthetic-only',acceptedAt:'2026-11-11T00:00:00Z',revokedAt:null}};
const magic=Buffer.from('MALAK-EV1\0','ascii');
const certBytes=Buffer.from('%PDF-1.4\nCERT SYNTHETIC PRIVATE\n%%EOF');
const idBytes=Buffer.from('%PDF-1.4\nID2 SYNTHETIC PRIVATE\n%%EOF');
function seal(bytes:Buffer,evidenceId:string):Buffer {
  const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);
  cipher.setAAD(Buffer.from('malak-evidence-v1:'+caseId+':'+evidenceId));
  return Buffer.concat([magic,iv,(()=>{const clear=cipher.update(bytes),end=cipher.final();
    const tag=cipher.getAuthTag();return Buffer.concat([tag,clear,end]);})()]);
}
function harness(){
  let active:AuthorizedUploadCase|null=consent,sessionUser:string|null=userId;
  let reads=0,ocrCalls=0;
  const certRow:EvidenceRow={evidenceId:certificateId,caseId,uploadedBy:userId,
    objectKey:'private/evidence/'+randomUUID(),digest:createHash('sha256').update(certBytes).digest('hex'),
    mime:'application/pdf',kind:'OWNERSHIP_CERTIFICATE',pageNumber:null,fileSize:certBytes.length,
    wrappedDataKey:'synthetic_vault_key',keyVersion:'v1',idempotencyKey:'synthetic_unique_cert_key'};
  const identityRow:EvidenceRow={...certRow,evidenceId:identityId,kind:'IDENTITY',
    objectKey:'private/evidence/'+randomUUID(),
    digest:createHash('sha256').update(idBytes).digest('hex'),fileSize:idBytes.length,
    idempotencyKey:'synthetic_unique_id_key'};
  const rows=new Map([[certificateId,certRow],[identityId,identityRow]]);
  const objects=new Map([[certRow.objectKey,seal(certBytes,certificateId)],
    [identityRow.objectKey,seal(idBytes,identityId)]]);
  const deps={
    auth:{authenticate:async(t:string)=>t===token&&sessionUser?{userId:sessionUser}:null},
    cases:{
      getUploadCase:async()=>{reads++;return active;},
      getPrivateEvidence:async(_case:string,_user:string,evidenceId:string)=>rows.get(evidenceId)??null
    },
    objects:{readPrivate:async(k:string)=>Buffer.from(objects.get(k)??Buffer.alloc(0))},
    keys:{unwrapDataKey:async()=>Buffer.from(key)},
    worker:{extract:async(bytes:Uint8Array)=>{
      ocrCalls++;
      const content=Buffer.from(bytes).toString('utf8');
      const id=content.includes('ID2')||content.includes('ID1')||content.includes('DIFF');
      const name=content.includes('DIFF')?'Amira Gharbi':'Amira Ben Salem';
      const line=id?'Nom et prénom: '+name:'Propriétaire: '+name;
      const numberOfPages=content.includes('ID2')?2:1;
      return {format:'pdf' as const,mayApproveOwnership:false as const,
        pages:Array.from({length:numberOfPages},(_,i)=>({page:i+1,method:'pdf-text',
          lines:i===0?[{text:line,page:1,source:'pdf-text',confidence:null}]:[]}))};
    }}
  };
  const request={bearerToken:token,caseId,certificateEvidenceIds:[certificateId],
    identityEvidenceIds:[identityId]};
  return {request,deps,rows,objects,certRow,identityRow,
    setCase:(v:AuthorizedUploadCase|null)=>{active=v;},
    setUser:(v:string|null)=>{sessionUser=v;},
    get reads(){return reads;},get ocrCalls(){return ocrCalls;}};
}
test('sealed certificate + multi-page identity yields ONLY preliminary text agreement',async()=>{
  const h=harness(),r=await analyze(h.request,h.deps);
  assert.equal(r.comparison.assessment,'PRELIMINARY_TEXT_AGREEMENT');
  assert.equal(r.identityPagesReceived,2);
  assert.equal(r.certificatePagesReceived,1);
  assert.equal(r.identitySides,'MULTI_PAGE_UNCONFIRMED');
  assert.equal(r.comparison.certificateName?.value,'Amira Ben Salem');
  assert.equal(r.comparison.identityName?.page,1);
  assert.equal(r.ownershipVerified,false);
  assert.equal(r.identityVerified,false);
  assert.equal(r.mayPublish,false);
  assert.equal(r.requiresHumanReview,true);
  assert.equal(h.ocrCalls,2);
  assert.ok(h.reads>=2);
});
test('a missing reverse side never returns a positive preliminary match',async()=>{
  const h=harness();
  const one=Buffer.from('%PDF-1.4\nID1 SYNTHETIC PRIVATE\n%%EOF');
  h.rows.set(identityId,{...h.identityRow,digest:createHash('sha256').update(one).digest('hex'),
    fileSize:one.length});
  h.objects.set(h.identityRow.objectKey,seal(one,identityId));
  const r=await analyze(h.request,h.deps);
  assert.equal(r.identitySides,'MISSING_REVERSE');
  assert.equal(r.comparison.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.ok(r.comparison.reasons.includes('IDENTITY_REVERSE_MISSING'));
});
test('different owner name is a review difference, never fraud or verified',async()=>{
  const h=harness();
  const other=Buffer.from('%PDF-1.4\nDIFF SYNTHETIC PRIVATE\n%%EOF');
  h.rows.set(identityId,{...h.identityRow,digest:createHash('sha256').update(other).digest('hex'),
    fileSize:other.length});
  h.objects.set(h.identityRow.objectKey,seal(other,identityId));
  const r=await analyze(h.request,h.deps);
  assert.equal(r.comparison.assessment,'TEXT_DIFFERENCE_NEEDS_REVIEW');
  assert.equal(r.mayPublish,false);
});
test('revoked consent before reading blocks OCR and field return',async()=>{
  const h=harness();h.setCase({...consent,consent:null});
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
  assert.equal(h.ocrCalls,0);
});
test('revoked consent DURING OCR blocks any private result',async()=>{
  const h=harness();
  const original=h.deps.worker.extract;
  h.deps.worker.extract=async b=>{const result=await original(b);h.setCase({...consent,consent:null});return result;};
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
});
test('session identity swap DURING OCR suppresses private result',async()=>{
  const h=harness();const original=h.deps.worker.extract;
  h.deps.worker.extract=async b=>{const result=await original(b);h.setUser(randomUUID());return result;};
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
});
test('cross-case and wrong uploader receipt denied before document read',async()=>{
  const h=harness();h.rows.set(identityId,{...h.identityRow,caseId:randomUUID()});
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
  assert.equal(h.ocrCalls,0);
  h.rows.set(identityId,{...h.identityRow,uploadedBy:randomUUID()});
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
});
test('wrong evidence kind, missing receipt and duplicate identity ID fail closed',async()=>{
  const h=harness();h.rows.set(identityId,{...h.identityRow,kind:'OWNERSHIP_CERTIFICATE'});
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
  h.rows.delete(identityId);
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
  await assert.rejects(analyze({...h.request,identityEvidenceIds:[certificateId]},h.deps),PrivatePairAccessDenied);
  await assert.rejects(analyze({...h.request,identityEvidenceIds:[identityId,identityId]},h.deps),PrivatePairAccessDenied);
});
test('tampered GCM ciphertext, key or receipt digest never produces a report',async()=>{
  const h=harness(),sealed=Buffer.from(h.objects.get(h.certRow.objectKey)!);
  sealed[sealed.length-2]^=1;h.objects.set(h.certRow.objectKey,sealed);
  await assert.rejects(analyze(h.request,h.deps),PrivatePairUnavailable);
  const second=harness();second.rows.set(certificateId,{...second.certRow,digest:'0'.repeat(64)});
  await assert.rejects(analyze(second.request,second.deps),PrivatePairUnavailable);
});
test('missing or inaccessible consent case and company claims fail closed',async()=>{
  const h=harness();h.setCase(null);
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
  h.setCase({...consent,claimantKind:'COMPANY'});
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
  h.setCase(consent);h.setUser(null);
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
});
