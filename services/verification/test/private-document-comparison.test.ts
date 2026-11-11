import test from 'node:test';
import assert from 'node:assert/strict';
import {comparePrivateCertificateIdentityOcr as compare} from '../src/private-document-comparison.ts';
function doc(lines:string[],source:'ocr-fr'|'ocr-ar'|'pdf-text'='ocr-fr',confidence:number|null=0.96){
  return {format:'image',mayApproveOwnership:false,pages:[{page:1,method:source==='pdf-text'?'pdf-text':'ocr',
    lines:lines.map(text=>({text,page:1,source,confidence:source==='pdf-text'?null:confidence}))}]};
}
test('same private Latin names yield only preliminary text agreement; never verified',()=>{
  const r=compare(doc(['Propriétaire: Amira Ben Salem']),doc(['Nom et prénom: Amira Ben Salem']));
  assert.equal(r.assessment,'PRELIMINARY_TEXT_AGREEMENT');
  assert.equal(r.mayPublish,false);assert.equal(r.ownershipVerified,false);
  assert.equal(r.identityVerified,false);assert.equal(r.requiresHumanReview,true);
});
test('nom/prenom separate identity fields are assembled without fuzzy matching',()=>{
  const r=compare(doc(['Propriétaire: Amira Ben Salem']),doc(['Nom: Ben Salem','Prénom: Amira']));
  assert.equal(r.assessment,'PRELIMINARY_TEXT_AGREEMENT');
});
test('Arabic exact text candidate may agree but remains unverified',()=>{
  const r=compare(doc(['المالك: محمد بن صالح'],'ocr-ar'),doc(['الاسم واللقب: محمد بن صالح'],'ocr-ar'));
  assert.equal(r.assessment,'PRELIMINARY_TEXT_AGREEMENT');assert.equal(r.ownershipVerified,false);
});
test('reordered name tokens must never appear as preliminary identity agreement',()=>{
  const report=compare(doc(['Propriétaire: Amira Ben Salem']),
    doc(['Nom et prénom: Salem Ben Amira']));
  assert.equal(report.assessment,'TEXT_DIFFERENCE_NEEDS_REVIEW');
  assert.ok(report.reasons.includes('NAME_TEXT_DIFFERS_OR_SCRIPT_MISMATCH'));
  assert.equal(report.ownershipVerified,false);
  assert.equal(report.identityVerified,false);
  assert.equal(report.mayPublish,false);
});
test('different names require human review, not an automatic identity-fraud conclusion',()=>{
  const r=compare(doc(['Propriétaire: Amira Ben Salem']),doc(['Nom et prénom: Amira Gharbi']));
  assert.equal(r.assessment,'TEXT_DIFFERENCE_NEEDS_REVIEW');assert.equal(r.mayPublish,false);
});
test('OCR uncertainty never turns into a green agreement',()=>{
  const r=compare(doc(['Propriétaire: Amira Ben Salem'],'ocr-fr',0.68),doc(['Nom et prénom: Amira Ben Salem']));
  assert.equal(r.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.ok(r.reasons.includes('LOW_OCR_CONFIDENCE'));
});
test('Arabic vs French names are never auto-transliterated into a false match',()=>{
  const r=compare(doc(['المالك: محمد بن صالح'],'ocr-ar'),doc(['Nom et prénom: Mohamed Ben Salah']));
  assert.equal(r.assessment,'TEXT_DIFFERENCE_NEEDS_REVIEW');
});
test('owner name absent in certificate does not silently infer it from the ID',()=>{
  const r=compare(doc(['Certificat de propriété','Superficie: 240 m2']),doc(['Nom et prénom: Amira Ben Salem']));
  assert.equal(r.assessment,'INSUFFICIENT_OR_AMBIGUOUS');assert.equal(r.certificateName,null);
});
test('French title with a separately labelled co-owner cannot agree with one matching CIN',()=>{
  const certificate=doc([
    'Propriétaire: Amira Ben Salem',
    'Copropriétaire: Kamel Ben Salem'
  ]);
  const id=doc(['Nom et prénom: Amira Ben Salem']);
  const result=compare(certificate,id);
  assert.equal(result.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.ok(result.reasons.includes('MULTIPLE_OWNERS_OR_SHARED_TITLE_NEEDS_REVIEW'));
  assert.equal(result.mayPublish,false);assert.equal(result.ownershipVerified,false);
});
test('Arabic undivided/shared title cannot agree with a matching identity name',()=>{
  const certificate=doc(['المالك: محمد بن صالح','ملاك على الشياع'],'ocr-ar');
  const id=doc(['الاسم واللقب: محمد بن صالح'],'ocr-ar');
  const result=compare(certificate,id);
  assert.equal(result.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.ok(result.reasons.includes('MULTIPLE_OWNERS_OR_SHARED_TITLE_NEEDS_REVIEW'));
  assert.equal(result.identityVerified,false);assert.equal(result.mayPublish,false);
});
test('ambiguous multiple owners and different names cannot auto-match',()=>{
  const r=compare(doc(['Propriétaire: Amira Ben Salem','Propriétaire: Kamel Ben Salem']),
    doc(['Nom et prénom: Amira Ben Salem']));
  assert.equal(r.assessment,'INSUFFICIENT_OR_AMBIGUOUS');assert.equal(r.certificateName,null);
});
test('conflicting 13th labelled owner cannot be hidden by candidate limits',()=>{
  const many=Array(12).fill('Propriétaire: Amira Ben Salem');
  many.push('Propriétaire: Amira Gharbi');
  const result=compare(doc(many),doc(['Nom et prénom: Amira Ben Salem']));
  assert.equal(result.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.equal(result.certificateName,null);
  assert.equal(result.mayPublish,false);
});
test('full CIN name conflicting with independent Nom and Prénom fails closed',()=>{
  const result=compare(doc(['Propriétaire: Amira Ben Salem']),
    doc(['Nom et prénom: Amira Ben Salem','Nom: Gharbi','Prénom: Amira']));
  assert.equal(result.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.equal(result.identityName,null);
});
test('partial split CIN name cannot silently corroborate the full name',()=>{
  const result=compare(doc(['Propriétaire: Amira Ben Salem']),
    doc(['Nom et prénom: Amira Ben Salem','Nom: Ben Salem']));
  assert.equal(result.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
});
test('consistent full name plus independent Nom and Prénom can still agree provisionally',()=>{
  const result=compare(doc(['Propriétaire: Amira Ben Salem']),
    doc(['Nom et prénom: Amira Ben Salem','Nom: Ben Salem','Prénom: Amira']));
  assert.equal(result.assessment,'PRELIMINARY_TEXT_AGREEMENT');
  assert.equal(result.ownershipVerified,false);
});
test('known missing certificate page blocks preliminary identity agreement',()=>{
  const cert=doc(['Propriétaire: Amira Ben Salem','Page 1 sur 2']);
  const id=doc(['Nom et prénom: Amira Ben Salem']);
  const result=compare(cert,id);
  assert.equal(result.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.ok(result.reasons.includes('CERTIFICATE_PAGES_MISSING'));
  assert.equal(result.mayPublish,false);
});
test('inconsistent printed certificate page count blocks agreement',()=>{
  const cert=doc(['Propriétaire: Amira Ben Salem','Page 3 sur 2']);
  const id=doc(['Nom et prénom: Amira Ben Salem']);
  const result=compare(cert,id);
  assert.equal(result.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.ok(result.reasons.includes('CERTIFICATE_PAGE_NUMBER_INCONSISTENT'));
});
test('consistent one-page printed title may still have preliminary text agreement',()=>{
  const cert=doc(['Propriétaire: Amira Ben Salem','Page 1 sur 1']);
  const id=doc(['Nom et prénom: Amira Ben Salem']);
  const result=compare(cert,id);
  assert.equal(result.assessment,'PRELIMINARY_TEXT_AGREEMENT');
  assert.equal(result.ownershipVerified,false);
});
test('blur warning on otherwise high-confidence matching names requires review',()=>{
  const title=doc(['Propriétaire: Amira Ben Salem']);
  const risky={...title,pages:title.pages.map(p=>({...p,qualityWarnings:['POSSIBLE_BLUR']}))};
  const result=compare(risky,doc(['Nom et prénom: Amira Ben Salem']));
  assert.equal(result.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
  assert.ok(result.reasons.includes('SCAN_QUALITY_NEEDS_REVIEW'));
  assert.equal(result.mayPublish,false);
});
test('reject hostile OCR payload metadata before any comparison',()=>{
  assert.throws(()=>compare(doc(['Propriétaire: Amira Ben Salem']),
    {format:'image',mayApproveOwnership:true,pages:[]}),/INVALID_OCR_PAYLOAD/);
});
test('unlabelled names never count as reliable evidence',()=>{
  const r=compare(doc(['Amira Ben Salem']),doc(['Amira Ben Salem']));
  assert.equal(r.assessment,'INSUFFICIENT_OR_AMBIGUOUS');
});
