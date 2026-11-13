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
const idBytes=Buffer.from('ID1 SYNTHETIC PRIVATE FRONT IMAGE FIXTURE DATA');
function seal(bytes:Buffer,evidenceId:string):Buffer {
  const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);
  cipher.setAAD(Buffer.from('malak-evidence-v1:'+caseId+':'+evidenceId));
  return Buffer.concat([magic,iv,(()=>{const clear=cipher.update(bytes),end=cipher.final();
    const tag=cipher.getAuthTag();return Buffer.concat([tag,clear,end]);})()]);
}
function harness(kind:EvidenceRow['kind']='OWNERSHIP_CERTIFICATE'){
  let active:AuthorizedUploadCase|null=consent,sessionUser:string|null=userId;
  let reads=0,ocrCalls=0;
  const certRow:EvidenceRow={evidenceId:certificateId,caseId,uploadedBy:userId,
    objectKey:'private/evidence/'+randomUUID(),digest:createHash('sha256').update(certBytes).digest('hex'),
    mime:'application/pdf',kind,pageNumber:null,fileSize:certBytes.length,
    wrappedDataKey:'synthetic_vault_key',keyVersion:'v1',idempotencyKey:'synthetic_unique_cert_key'};
  const identityRow:EvidenceRow={...certRow,evidenceId:identityId,kind:'IDENTITY',
    mime:'image/png',pageNumber:1,
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
      const id=content.includes('ID1')||content.includes('DIFF');
      const name=content.includes('DIFF')?'Amira Gharbi':'Amira Ben Salem';
      if(kind==='SHARED_OWNERSHIP_CERTIFICATE'){
        if(id)return {format:'image' as const,mayApproveOwnership:false as const,
          pages:[{page:1,method:'ocr' as const,lines:[
            {page:1,text:'الاسم واللقب: أمينة بنت سالم',
              source:'ocr-ar' as const,confidence:0.99},
            {page:1,text:'CIN: 00001122',source:'ocr-ar' as const,confidence:0.99}
          ]}]};
        const word=(page:number,text:string,x:number,y:number)=>({
          page,text,confidence:0.99,bbox:{
            left:x-22,top:y-10,right:x+22,bottom:y+10}
        });
        const row=(page:number,n:number,first:string,cin:string,y:number)=>[
          word(page,'1/'+n,945,y),word(page,'100',580,y+20),
          word(page,'جزء',580,y+46),word(page,first,830,y+20),
          word(page,'بنت',790,y+20),word(page,'سالم',750,y+20),
          word(page,'CIN',750,y+108),word(page,cin,815,y+108)
        ];
        const words1=[word(1,'موضوع',580,290),word(1,'هوية',790,290),
          word(1,'العدد',945,290),...row(1,1,'أمينة','00001122',490)];
        const words2=row(2,2,'عائشة','00003344',320);
        const page=(n:number,words:ReturnType<typeof word>[],title:string)=>({
          page:n,method:'ocr' as const,pageImageWidth:1000,pageImageHeight:1500,
          spatialWordsTruncated:false,ocrWords:words,
          lines:[{page:n,text:title,source:'ocr-ar' as const,confidence:0.99}]
        });
        return {format:'pdf' as const,mayApproveOwnership:false as const,
          pages:[page(1,words1,'شهادة اشتراك في الملكية'),
            page(2,words2,'صفحة 2 من 2')]};
      }
      const documentLines=kind==='SALE_DEED'?
        ['عقد بيع','المشتري: '+name,'البائع: Karim Ben Ahmed']:
        kind==='PROPERTY_SERVICE_RECEIPT'?
          ['وصل تسلم مطلب خدمة واستخلاص معاليم','طالب الخدمة: '+name]:
          kind==='FINANCIAL_RECEIPT'?['QUITTANCE N° X0001']:
          kind==='SHARED_OWNERSHIP_CERTIFICATE'?
            ['شهادة اشتراك في الملكية','Propriétaire: '+name]:
            kind==='TRANSFER_CARD'?
              ['بطاقة نقل الملكية','المنقول إليه: '+name]:
              ['Propriétaire: '+name];
      const observed=id?['Nom et prénom: '+name]:documentLines;
      return {format:(id?'image':'pdf') as 'image'|'pdf',
        mayApproveOwnership:false as const,
        pages:[{page:1,method:(id?'ocr':'pdf-text') as 'ocr'|'pdf-text',
          lines:observed.map(text=>({text,page:1,
            source:(id?'ocr-fr':'pdf-text') as 'ocr-fr'|'pdf-text',
            confidence:id?0.98:null}))}]};
    }}
  };
  const request={bearerToken:token,caseId,certificateEvidenceIds:[certificateId],
    identityEvidenceIds:[identityId]};
  return {request,deps,rows,objects,certRow,identityRow,
    setCase:(v:AuthorizedUploadCase|null)=>{active=v;},
    setUser:(v:string|null)=>{sessionUser=v;},
    get reads(){return reads;},get ocrCalls(){return ocrCalls;}};
}
test('sealed certificate plus ONE front image has only preliminary text agreement',async()=>{
  const h=harness(),r=await analyze(h.request,h.deps);
  assert.equal(r.comparison.assessment,'PRELIMINARY_TEXT_AGREEMENT');
  assert.equal(r.identityPagesReceived,1);
  assert.equal(r.certificatePagesReceived,1);
  assert.equal(r.identityCapture,'FRONT_IMAGE_ONLY_UNVERIFIED');
  assert.equal(r.comparison.certificateName?.value,'Amira Ben Salem');
  assert.equal(r.comparison.identityName?.page,1);
  assert.equal(r.ownershipVerified,false);
  assert.equal(r.identityVerified,false);
  assert.equal(r.mayPublish,false);
  assert.equal(r.requiresHumanReview,true);
  assert.equal(h.ocrCalls,2);
  assert.ok(h.reads>=2);
});
test('a single front image never requires a reverse-side image',async()=>{
  const h=harness(),r=await analyze(h.request,h.deps);
  assert.equal(r.identityCapture,'FRONT_IMAGE_ONLY_UNVERIFIED');
  assert.equal(r.comparison.assessment,'PRELIMINARY_TEXT_AGREEMENT');
  assert.equal(r.identityPagesReceived,1);
});
test('different owner name is a review difference, never fraud or verified',async()=>{
  const h=harness();
  const other=Buffer.from('DIFF SYNTHETIC PRIVATE FRONT IMAGE FIXTURE DATA');
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
  await assert.rejects(analyze({...h.request,identityEvidenceIds:[]},h.deps),PrivatePairAccessDenied);
});
test('image receipt cannot claim multi-page PDF OCR output',async()=>{
  const h=harness();
  const original=h.deps.worker.extract;
  const deps={...h.deps,worker:{extract:async(bytes:Uint8Array,mime:string)=>{
    const result=await original(bytes);
    return mime==='image/png'?{...result,format:'pdf' as const}:result;
  }}};
  await assert.rejects(analyze(h.request,deps),PrivatePairUnavailable);
});
test('single image receipt cannot claim two physical image pages',async()=>{
  const h=harness();
  const original=h.deps.worker.extract;
  const deps={...h.deps,worker:{extract:async(bytes:Uint8Array,mime:string)=>{
    const result=await original(bytes);
    return mime==='image/png'?{...result,pages:[...result.pages,
      {...result.pages[0]!,page:2}]}:result;
  }}};
  await assert.rejects(analyze(h.request,deps),PrivatePairUnavailable);
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

test('explicit CIN mismatch overrides otherwise agreeing owner names; no digits leaked',async()=>{
  const h=harness();
  const original=h.deps.worker.extract;
  h.deps.worker.extract=async bytes=>{
    const payload=await original(bytes);
    const id=Buffer.from(bytes).toString('utf8').includes('ID1');
    const label=id?'CIN: 87654321':'CIN: 12345678';
    payload.pages[0]!.lines.push({text:label,page:1,
      source:id?'ocr-fr':'pdf-text',confidence:id?.98:null});
    return payload;
  };
  const r=await analyze(h.request,h.deps);
  assert.equal(r.comparison.assessment,'TEXT_DIFFERENCE_NEEDS_REVIEW');
  assert.equal(r.cinComparison.status,'NUMBER_DIFFERS_NEEDS_REVIEW');
  assert.equal(r.ownershipVerified,false);
  assert.equal(r.identityVerified,false);
  assert.equal(JSON.stringify(r.cinComparison).includes('87654321'),false);
  assert.equal(JSON.stringify(r.cinComparison).includes('12345678'),false);
});

test('page-two image as first or only selected page never reaches OCR',async()=>{
  const h=harness();
  h.rows.set(certificateId,{...h.certRow,mime:'image/png',pageNumber:2});
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
  assert.equal(h.ocrCalls,0);
  const other=harness();
  other.rows.set(identityId,{...other.identityRow,mime:'image/jpeg',pageNumber:2});
  await assert.rejects(analyze(other.request,other.deps),PrivatePairAccessDenied);
  assert.equal(other.ocrCalls,0);
});
test('single PDF cannot disguise itself as a numbered scanned page',async()=>{
  const h=harness();
  h.rows.set(certificateId,{...h.certRow,pageNumber:1});
  await assert.rejects(analyze(h.request,h.deps),PrivatePairAccessDenied);
  assert.equal(h.ocrCalls,0);
  const other=harness();
  other.rows.set(identityId,{...other.identityRow,mime:'application/pdf',pageNumber:1});
  await assert.rejects(analyze(other.request,other.deps),PrivatePairAccessDenied);
  assert.equal(other.ocrCalls,0);
});


test('unified private comparator reports sale BUYER text only without legal ownership',async()=>{
 const h=harness('SALE_DEED');
 const result=await analyze(h.request,h.deps);
 assert.equal(result.unifiedComparison?.detectedKind,'SALE_DEED');
 assert.equal(result.unifiedComparison?.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(result.unifiedComparison?.roles.find(r=>r.role==='BUYER')?.assessment,
  'PRELIMINARY_TEXT_AGREEMENT');
 assert.equal(result.comparison.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
 assert.equal(result.cinComparison.status,'NOT_COMPARABLE');
 assert.equal(result.ownershipVerified,false);
 assert.equal(result.identityVerified,false);
 assert.equal(result.mayPublish,false);
 assert.equal(JSON.stringify(result.unifiedComparison).includes('Amira Ben Salem'),false);
});
test('service receipt applicant may match text but never be treated as an owner',async()=>{
 const h=harness('PROPERTY_SERVICE_RECEIPT');
 const result=await analyze(h.request,h.deps);
 assert.equal(result.unifiedComparison?.detectedKind,'PROPERTY_SERVICE_RECEIPT');
 assert.equal(result.unifiedComparison?.roles[0]?.role,'SERVICE_APPLICANT');
 assert.equal(result.unifiedComparison?.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(result.ownershipVerified,false);
 assert.equal(result.cinComparison.status,'NOT_COMPARABLE');
});
test('financial receipt with no person role stays inconclusive',async()=>{
 const h=harness('FINANCIAL_RECEIPT');
 const result=await analyze(h.request,h.deps);
 assert.equal(result.unifiedComparison?.detectedKind,'FINANCIAL_RECEIPT');
 assert.equal(result.unifiedComparison?.assessment,'INSUFFICIENT_EVIDENCE');
 assert.equal(result.unifiedComparison?.roles[0]?.role,'PAYMENT_PAYER');
 assert.equal(result.unifiedComparison?.roles[0]?.assessment,
  'INSUFFICIENT_OR_AMBIGUOUS');
 assert.equal(result.mayPublish,false);
});
test('stored kind disagreeing with OCR heading cannot claim a role match',async()=>{
 const h=harness('SALE_DEED');
 h.rows.set(certificateId,{...h.certRow,kind:'OWNERSHIP_CERTIFICATE'});
 const result=await analyze(h.request,h.deps);
 assert.equal(result.unifiedComparison?.assessment,'INSUFFICIENT_EVIDENCE');
 assert.ok(result.unifiedComparison?.reasons.includes('DOCUMENT_TYPE_MISMATCH'));
 assert.equal(result.comparison.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
 assert.equal(result.mayPublish,false);
});

test('encrypted two-page co-owner PDF preserves SAME-ROW CIN geometry and matches role text only',async()=>{
 const h=harness('SHARED_OWNERSHIP_CERTIFICATE');
 const result=await analyze(h.request,h.deps);
 assert.equal(result.certificatePagesReceived,2);
 assert.equal(result.unifiedComparison?.detectedKind,'SHARED_OWNERSHIP_CERTIFICATE');
 assert.equal(result.unifiedComparison?.assessment,'ROLE_TEXT_MATCH_ONLY');
 assert.equal(result.unifiedComparison?.roles[0]?.role,'SHARED_RIGHTS_HOLDER');
 assert.equal(result.unifiedComparison?.roles[0]?.documentPage,1);
 assert.equal(result.comparison.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
 assert.equal(result.cinComparison.status,'NOT_COMPARABLE');
 assert.equal(result.ownershipVerified,false);
 assert.equal(result.identityVerified,false);
 assert.equal(result.mayPublish,false);
 assert.equal(JSON.stringify(result.unifiedComparison).includes('أمينة'),false);
 assert.equal(JSON.stringify(result.unifiedComparison).includes('00001122'),false);
});
