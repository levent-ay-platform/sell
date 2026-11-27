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

test('private reader attaches spatial owner-table candidates only to complete OCR pages',()=>{
 const word=(text:string,page:number,x:number,y:number)=>({
   text,page,confidence:.98,
   bbox:{left:x-20,top:y-10,right:x+20,bottom:y+10}
 });
 const words=[
   word('موضوع',1,580,260),word('هوية',1,800,260),
   word('العدد',1,945,260),
   word('1/1',1,945,450),
   word('500',1,580,480),word('جزء',1,580,505),
   word('آمنة',1,833,480),word('بنت',1,793,480),
   word('سالم',1,753,480),
   word('1/2',1,945,700),
   word('500',1,580,730),word('جزء',1,580,755),
   word('فاطمة',1,833,730),word('بنت',1,793,730),
   word('خالد',1,753,730)
 ];
 const input={format:'image',mayApproveOwnership:false,pages:[{
   page:1,method:'ocr',pageImageWidth:1000,pageImageHeight:1500,
   spatialWordsTruncated:false,ocrWords:words,
   lines:[{page:1,source:'ocr-ar',confidence:.99,text:'شهادة ملكية'},
     {page:1,source:'ocr-ar',confidence:.99,text:'معرف الرسم العقاري: 987321'},
     {page:1,source:'ocr-ar',confidence:.99,text:'صفحة 1 من 1'}]
 }]};
 const r=readUntrustedOcrDocument(input);
 assert.equal(r.ownerTableLayout?.ownerOrdinals.length,2);
 assert.equal(r.ownerTableLayout?.cells.length,4);
 assert.equal(r.ownerTableLayout?.mayPublish,false);
 assert.equal(r.mayPublish,false);
 const missing=readUntrustedOcrDocument({...input,pages:[
   {...input.pages[0],spatialWordsTruncated:true}]});
 assert.equal(missing.ownerTableLayout,null);
});
test('missing or malformed page geometry refuses spatial owner lookup',()=>{
 const source={format:'image',mayApproveOwnership:false,
   pages:[{page:1,method:'ocr',pageImageWidth:1000,pageImageHeight:1500,
     spatialWordsTruncated:false,ocrWords:[{
       page:1,text:'شهادة',confidence:.8,
       bbox:{left:4,top:3,right:20,bottom:28}}],
     lines:[{page:1,source:'ocr-ar',confidence:.8,text:'شهادة ملكية'}]}]};
 assert.equal(readUntrustedOcrDocument(source).ownerTableLayout?.findings[0],
   'HEADER_NOT_FOUND');
 const bad={...source,pages:[{...source.pages[0],ocrWords:[{
    ...source.pages[0].ocrWords[0],
    bbox:{left:900,top:120,right:1100,bottom:140}
 }]}]};
 assert.throws(()=>parseOcrWorkerOutput(bad),/INVALID_SPATIAL_OCR_WORD/);
 const incomplete={...source,pages:[{...source.pages[0],ocrWords:[]}]};
 assert.equal(readUntrustedOcrDocument(incomplete).ownerTableLayout,null);
});


test('private OCR reader returns type-specific sale candidates, never the parent area',()=>{
 const payload={format:'image',mayApproveOwnership:false,pages:[{
   page:1,method:'ocr',lines:[
     {page:1,source:'ocr-ar',confidence:.99,text:'عقد بيع'},
     {page:1,source:'ocr-ar',confidence:.99,text:'الرسم العقاري الأم: 999932'},
     {page:1,source:'ocr-ar',confidence:.99,text:'المساحة المبيعة: 185.25 م²'},
     {page:1,source:'ocr-ar',confidence:.99,text:'المساحة الكلية: 42910 م²'}
   ]
 }]};
 const r=readUntrustedOcrDocument(payload);
 assert.equal(r.documentFamilyCandidate.kind,'SALE_DEED');
 assert.equal(r.privateFamilyFields.kind,'SALE_DEED');
 assert.equal(r.privateFamilyFields.facts.find(f=>f.key==='soldAreaM2')?.value,'185.25');
 assert.equal(r.privateFamilyFields.facts.find(f=>f.key==='registeredAreaM2'),undefined);
 assert.equal(r.privateFamilyFields.mayAutofillUnreviewed,false);
 assert.equal(r.privateFamilyFields.ownershipVerified,false);
 assert.equal(r.privateFamilyFields.mayPublish,false);
 assert.equal(r.mayPublish,false);
});
test('financial receipt never becomes transferrable or verified title',()=>{
 const payload={format:'image',mayApproveOwnership:false,pages:[{
   page:1,method:'ocr',lines:[
     {page:1,source:'ocr-fr',confidence:.96,text:'QUITTANCE N° X0099'},
     {page:1,source:'ocr-fr',confidence:.96,text:'MONTANT: 50,000'}
   ]
 }]};
 const r=readUntrustedOcrDocument(payload);
 assert.equal(r.documentFamilyCandidate.kind,'FINANCIAL_RECEIPT');
 assert.equal(r.privateFamilyFields.kind,'FINANCIAL_RECEIPT');
 assert.ok(r.privateFamilyFields.issues.includes('SUPPORTING_RECEIPT_NOT_TITLE'));
 assert.equal(r.privateFamilyFields.mayPublish,false);
 assert.equal(r.privateFamilyFields.facts.some(f=>f.key==='registerReference'),false);
});


test('service-request fee receipt is supporting review evidence, not title approval',()=>{
 const result=readUntrustedOcrDocument({
   format:'image',mayApproveOwnership:false,pages:[{
     page:1,method:'ocr',lines:[
       {page:1,text:'وصل تسلم مطلب خدمة واستخلاص معاليم',source:'ocr-ar',confidence:.97},
       {page:1,text:'العدد: 44551',source:'ocr-ar',confidence:.96},
       {page:1,text:'التاريخ: 2022/05/13',source:'ocr-ar',confidence:.96},
       {page:1,text:'معرف الرسم العقاري: 998177 نابل',source:'ocr-ar',confidence:.94}
     ]
   }]
 });
 assert.equal(result.documentFamilyCandidate.kind,'PROPERTY_SERVICE_RECEIPT');
 assert.equal(result.privateFamilyFields.facts.find(f=>f.key==='serviceRequestNumber')?.value,'44551');
 assert.equal(result.privateEvidenceReadiness.documentSupportLevel,1);
 assert.equal(result.privateEvidenceReadiness.intakeDecision,'ACCEPT_FOR_PRIVATE_REVIEW');
 assert.equal(result.privateEvidenceReadiness.officialOwnershipVerified,false);
 assert.equal(result.privateEvidenceReadiness.displayAsVerifiedSeller,false);
 assert.equal(result.mayPublish,false);
});

test('parcel scans preserve only bounded page-local evidence',()=>{
  const ring=[[10,10],[90,12],[80,150],[10,10]];
  const scan={page:2,width:800,height:600,status:'CANDIDATE',outline:{unit:'PIXEL',page:2,ring},
    geographicLocation:[10,36],geometryVerified:true};
  const input={...document,pages:[document.pages[0],{...document.pages[1],parcelPlanScan:scan}]};
  const r=readUntrustedOcrDocument(input);
  assert.deepEqual(r.parcelPlanScans,[{page:2,width:800,height:600,status:'CANDIDATE',
    outline:{unit:'PIXEL',page:2,ring}}]);
  assert.equal(r.mayPublish,false);
  for (const candidate of [
    {...scan,page:1},
    {...scan,outline:{...scan.outline,unit:'WGS84'}},
    {...scan,outline:{...scan.outline,ring:[[10,10],[900,12],[80,150],[10,10]]}},
    {...scan,status:'AMBIGUOUS'},
  ]) {
    assert.throws(()=>readUntrustedOcrDocument({...document,pages:[
      document.pages[0],{...document.pages[1],parcelPlanScan:candidate}
    ]}),/INVALID_PARCEL_PLAN_SCAN/);
  }
});

test('plan-only OCR reads explicit title, lot and printed area without assigning ownership',()=>{
  const lines=['Extrait du Lotissement Sahloul 4','Titre foncier: 098765','LOT EHC 50',
    'Surface : 1 672 m²','A-B: 42,50 m'].map(text=>({text,page:1,confidence:.98,source:'ocr-fr'}));
  const page={page:1,method:'ocr',lines,
    parcelPlanScan:{page:1,width:800,height:600,status:'NO_OUTLINE',outline:null}};
  const read=readUntrustedOcrDocument({format:'image',mayApproveOwnership:false,pages:[page]});
  const values=Object.fromEntries(read.parcelPlanFacts.map(item=>[item.key,item.value]));
  assert.deepEqual(values,{titleReference:'098765',plotReference:'EHC 50',printedAreaM2:'1672'});
  assert.equal(read.parcelEdgeMeasurements[0].lengthM,'42.5');
  const extra={text:'LOT EHC 51',page:1,confidence:.98,source:'ocr-fr'};
  const ambiguous=readUntrustedOcrDocument({format:'image',mayApproveOwnership:false,
    pages:[{...page,lines:[...lines,extra]}]});
  assert.equal(ambiguous.parcelPlanFacts.find(item=>item.key==='plotReference')?.status,'CONFLICT');
  assert.equal(ambiguous.parcelPlanFacts.find(item=>item.key==='plotReference')?.value,null);
  assert.equal(ambiguous.mayPublish,false);
});

test('Arabic plan labels preserve leading zeroes and normalize printed decimal digits',()=>{
  const lines=['مخطط تقسيم','رقم الرسم العقاري: ٠٩٨٧٦٥','رقم القطعة: ٠٤٢','المساحة: ٨٠٠٫٢٥ م²']
    .map(text=>({text,page:1,confidence:.96,source:'ocr-ar'}));
  const read=readUntrustedOcrDocument({format:'image',mayApproveOwnership:false,pages:[{
    page:1,method:'ocr',lines,
    parcelPlanScan:{page:1,width:800,height:600,status:'NO_OUTLINE',outline:null}
  }]});
  assert.deepEqual(read.parcelPlanFacts.map(item=>item.value),['098765','042','800.25']);
});
