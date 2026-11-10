import test from 'node:test';
import assert from 'node:assert/strict';
import {readUntrustedOcrDocument} from '../src/reader.ts';
import {presentPrivateCpfReading} from '../src/ocr-presentation.ts';
const input={format:'pdf',mayApproveOwnership:false,pages:[
  {page:1,method:'ocr',lines:[
    {page:1,source:'ocr-ar',confidence:.96,text:'شهادة ملكية'},
    {page:1,source:'ocr-ar',confidence:.96,text:'المساحة: 240 متر مربع'},
    {page:1,source:'ocr-ar',confidence:.96,text:'صفحة 1 من 2'},
    {page:1,source:'ocr-fr',confidence:.93,text:'titre foncier: 123456'},
  ]},
]};
function read(){return readUntrustedOcrDocument(input);}
test('OCR candidate becomes a private field card with page and confidence, NEVER an ownership badge',()=>{
  const v=presentPrivateCpfReading(read(),'240');
  const area=v.facts.find(x=>x.id==='areaSquareMetres')!;
  assert.equal(area.value,'240');
  assert.equal(area.page,1);
  assert.equal(area.confidencePercent,96);
  assert.equal(area.status,'CANDIDATE');
  assert.deepEqual(v.missingPages,[2]);
  assert.equal(v.declaredAreaComparison,'SAME_NUMBER_UNVERIFIED');
  assert.equal(v.ownerIdentityMatch,'NOT_STARTED');
  assert.equal(v.officialCpfOwnershipCheck,'NOT_STARTED');
  assert.equal(v.mayPublish,false);
  assert.equal(v.ownershipVerified,false);
  assert.equal(v.access,'AUTHORIZED_OWNER_OR_REVIEWER_ONLY');
});
test('mismatched declared area is a REVIEW finding, not ownership failure',()=>{
  const v=presentPrivateCpfReading(read(),245);
  assert.equal(v.declaredAreaComparison,'DIFFERENT_NUMBER_REVIEW');
  assert.equal(v.ownershipVerified,false);
});
test('no declared area or uncertain OCR yields no fabricated match',()=>{
  const v=presentPrivateCpfReading(read(),'');
  assert.equal(v.declaredAreaComparison,'NOT_COMPARABLE');
  assert.equal(v.facts.find(x=>x.id==='ownershipShareUnits')?.value,null);
  assert.equal(v.facts.find(x=>x.id==='certificateIssuedAt')?.value,null);
});
test('invalid or auto-approved OCR payload rejected',()=>{
  assert.throws(()=>presentPrivateCpfReading({...read(),mayPublish:true} as any,'240'),/UNTRUSTED_OCR_READING/);
});
