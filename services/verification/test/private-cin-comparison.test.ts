import test from 'node:test';
import assert from 'node:assert/strict';
import {parseOcrWorkerOutput} from '../src/reader.ts';
import {compareCertificateIdentityCinText as compare} from '../src/private-cin-comparison.ts';
function doc(lines:string[],confidence:number|null=0.96){
  return parseOcrWorkerOutput({format:'image',mayApproveOwnership:false,
    pages:[{page:1,method:'ocr',lines:lines.map(text=>({text,page:1,
      source:'ocr-fr',confidence}))}]});
}
test('strict labelled numbers match, but cannot verify a card or disclose CIN digits',()=>{
 const a=compare(doc(['N° CIN: 12345678']),doc(['Numéro CIN: 12345678']));
 assert.equal(a.status,'SAME_NUMBER_UNVERIFIED');assert.equal(a.mayVerifyIdentity,false);
 assert.equal(a.certificateEvidence?.page,1);assert.equal(a.identityEvidence?.confidencePercent,96);
 assert.equal(JSON.stringify(a).includes('12345678'),false);
});
test('Arabic numerals and bilingual labels normalize strictly',()=>{
 const a=compare(doc(['رقم بطاقة التعريف الوطنية: ١٢٣٤٥٦٧٨']),
   doc(['CIN : 12345678']));
 assert.equal(a.status,'SAME_NUMBER_UNVERIFIED');
});
test('a mismatched labelled number cannot be reported as matching',()=>{
 const a=compare(doc(['CIN : 12345678']),doc(['CIN: 87654321']));
 assert.equal(a.status,'NUMBER_DIFFERS_NEEDS_REVIEW');
});
test('title registry numbers and random unlabelled eight digits are not CIN',()=>{
 const a=compare(doc(['Titre foncier: 12345678','12345678']),doc(['CIN: 12345678']));
 assert.equal(a.status,'NOT_COMPARABLE');
});
test('conflicting values or low-confidence text fail closed',()=>{
 assert.equal(compare(doc(['CIN: 12345678','CIN: 87654321']),doc(['CIN: 12345678'])).status,
   'AMBIGUOUS_OR_LOW_CONFIDENCE');
 assert.equal(compare(doc(['CIN: 12345678'],0.5),doc(['CIN: 12345678'])).status,
   'AMBIGUOUS_OR_LOW_CONFIDENCE');
});
test('no CIN on either document means not comparable, not matching',()=>{
 assert.equal(compare(doc(['Propriétaire: Amira Ben Salem']),
   doc(['Nom et prénom: Amira Ben Salem'])).status,'NOT_COMPARABLE');
});
