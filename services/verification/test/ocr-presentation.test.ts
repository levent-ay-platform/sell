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


test('authorized private field card carries the same bounded OCR rectangle',()=>{
 const withRect={...input,pages:[{...input.pages[0],lines:input.pages[0].lines.map(line=>
   line.text.includes('المساحة')?{...line,bbox:{left:33,top:42,right:200,bottom:68}}:line)}]};
 const read=readUntrustedOcrDocument(withRect);
 const view=presentPrivateCpfReading(read,'240');
 const area=view.facts.find(x=>x.id==='areaSquareMetres')!;
 assert.deepEqual(area.bbox,{left:33,top:42,right:200,bottom:68});
 assert.equal(view.access,'AUTHORIZED_OWNER_OR_REVIEWER_ONLY');
 assert.equal(view.ownershipVerified,false);
 assert.equal(view.mayPublish,false);
});


test('private review gets labelled family facts with evidence but no automatic verification',()=>{
 const sample={format:'image',mayApproveOwnership:false,pages:[{
   page:1,method:'ocr',lines:[
     {page:1,text:'عقد بيع',source:'ocr-ar',confidence:.98},
     {page:1,text:'المساحة المبيعة: 185.25 م²',source:'ocr-ar',confidence:.98,
       bbox:{left:15,top:115,right:280,bottom:143}},
     {page:1,text:'البائع: شخص افتراضي',source:'ocr-ar',confidence:.96}
   ]
 }]};
 const r=presentPrivateCpfReading(readUntrustedOcrDocument(sample),null);
 assert.equal(r.familyKind,'SALE_DEED');
 assert.equal(r.familyFacts.find(f=>f.id==='soldAreaM2')?.value,'185.25');
 assert.deepEqual(r.familyFacts.find(f=>f.id==='soldAreaM2')?.bbox,
   {left:15,top:115,right:280,bottom:143});
 assert.equal(r.documentSupportLevel,3);
 assert.equal(r.ownershipVerified,false);
 assert.equal(r.mayPublish,false);
});
