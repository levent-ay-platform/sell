import test from 'node:test';
import assert from 'node:assert/strict';
import {parseOcrWorkerOutput,readUntrustedOcrDocument} from '../src/reader.ts';

const document={
  format:'pdf',mayApproveOwnership:false,
  pages:[
    {page:1,method:'pdf-text',lines:[
       {text:'Certificat de propriété',page:1,confidence:null,source:'pdf-text'},
       {text:'Titre foncier: 987654',page:1,confidence:null,source:'pdf-text'},
       {text:'Superficie: 843 m2',page:1,confidence:null,source:'pdf-text'},
       {text:'Page 1 sur 2',page:1,confidence:null,source:'pdf-text'}]},
    {page:2,method:'ocr',lines:[
       {text:'شهادة ملكية',page:2,confidence:0.92,source:'ocr-ar'},
       {text:'صفحة 2 من 2',page:2,confidence:0.93,source:'ocr-ar'}]}
  ]
} as const;
test('mixed OCR/native pages preserve page, confidence and never approve',()=>{
  const r=readUntrustedOcrDocument(document);
  assert.equal(r.extraction.documentKind,'CPF_OWNERSHIP_CERTIFICATE');
  assert.equal(r.extraction.fields.registryReference.value,'987654');
  assert.equal(r.extraction.fields.areaSquareMetres.value,843);
  assert.deepEqual(r.extraction.missingPages,[]);
  assert.equal(r.mayPublish,false);
  assert.equal(r.requiresIndependentOwnershipReview,true);
  assert.deepEqual(r.extractionMethods,['pdf-text','ocr']);
});
test('one of two pages remains explicitly missing',()=>{
  const r=readUntrustedOcrDocument({...document,pages:[document.pages[0]]});
  assert.deepEqual(r.extraction.missingPages,[2]);
  assert.equal(r.mayPublish,false);
});
test('OCR worker cannot claim auto-approval',()=>{
  assert.throws(()=>readUntrustedOcrDocument({...document,mayApproveOwnership:true}),/INVALID_OCR_PAYLOAD/);
});
test('reject page gaps or duplicates before interpreting metadata',()=>{
  assert.throws(()=>parseOcrWorkerOutput({...document,pages:[document.pages[1]]}),/OCR_PAGE_GAP/);
  assert.throws(()=>parseOcrWorkerOutput({...document,pages:[document.pages[0],document.pages[0]]}),/INVALID_OCR_PAGE/);
});
test('reject worker line page mismatch and invalid confidence',()=>{
  const line={...document.pages[0].lines[0],page:2};
  assert.throws(()=>parseOcrWorkerOutput({...document,pages:[{...document.pages[0],lines:[line]}]}),/INVALID_OCR_LINE/);
  const bad={...document.pages[1].lines[0],confidence:2};
  assert.throws(()=>parseOcrWorkerOutput({...document,pages:[document.pages[0],{...document.pages[1],lines:[bad]}]}),/INVALID_OCR_LINE/);
});
test('reject OCR lines pretending to have a signed source',()=>{
  const fake={...document.pages[1].lines[0],source:'pdf-text'};
  assert.throws(()=>parseOcrWorkerOutput({...document,pages:[document.pages[0],{...document.pages[1],lines:[fake]}]}),/INVALID_OCR_LINE/);
});
test('OCR Arabic bidi page order is recoverable as untrusted candidate',()=>{
  const r=readUntrustedOcrDocument({format:'image',mayApproveOwnership:false,pages:[
    {page:1,method:'ocr',lines:[
      {text:'2 صفحة 1 من',page:1,confidence:0.88,source:'ocr-ar'}
    ]}]});
  assert.equal(r.extraction.fields.documentPage.value,1);
  assert.equal(r.extraction.fields.documentPageCount.value,2);
  assert.deepEqual(r.extraction.missingPages,[2]);
  assert.equal(r.mayPublish,false);
});


test('private OCR field evidence keeps pixel box for source highlighting',()=>{
  const synthetic={format:'image',mayApproveOwnership:false,pages:[{
    page:1,method:'ocr',lines:[
      {text:'شهادة ملكية',page:1,confidence:0.98,source:'ocr-ar',
       bbox:{left:15,top:10,right:220,bottom:45}},
      {text:'معرف الرسم العقاري: 123789',page:1,confidence:0.98,source:'ocr-ar',
       bbox:{left:110,top:160,right:550,bottom:204}}
    ]
  }]};
  const result=readUntrustedOcrDocument(synthetic);
  assert.equal(result.extraction.fields.registryReference.value,'123789');
  assert.deepEqual(result.extraction.fields.registryReference.evidence[0].bbox,
    {left:110,top:160,right:550,bottom:204});
  assert.equal(result.mayPublish,false);
});
test('malformed or non-raster OCR bounding boxes fail closed',()=>{
  const common={format:'image',mayApproveOwnership:false};
  const entry=(bbox:unknown)=>({page:1,method:'ocr',lines:[{
    text:'شهادة ملكية',page:1,confidence:0.9,source:'ocr-ar',bbox
  }]});
  for(const bounds of [
    {left:10,top:10,right:10,bottom:30},
    {left:-3,top:10,right:30,bottom:40},
    {left:0.2,top:10,right:30,bottom:40},
    {left:0,top:10,right:Infinity,bottom:40},
    'fake-box'
  ]){
    assert.throws(()=>parseOcrWorkerOutput({...common,pages:[entry(bounds)]}),/INVALID_OCR_LINE/);
  }
  const native={...document,pages:[{...document.pages[0],lines:[
    {...document.pages[0].lines[0],bbox:{left:1,top:1,right:2,bottom:2}}
  ]}]};
  assert.throws(()=>parseOcrWorkerOutput(native),/INVALID_OCR_LINE/);
});
