import test from 'node:test';
import assert from 'node:assert/strict';
import {readUntrustedOcrDocument} from '../src/reader.ts';
import {presentPrivateCpfReading} from '../src/ocr-presentation.ts';
import {preparePrivatePlanPreview} from '../src/parcel-document-foundation.ts';

test('authorized OCR readout preserves parcel identifiers and source-backed Arabic and French side lengths',()=>{
  const read=readUntrustedOcrDocument({format:'pdf',mayApproveOwnership:false,pages:[
    {page:1,method:'pdf-text',lines:[
      {text:'Certificat de propriété',page:1,source:'pdf-text',confidence:null},
      {text:'Titre foncier : 000991',page:1,source:'pdf-text',confidence:null},
      {text:'Numéro de parcelle : 0042',page:1,source:'pdf-text',confidence:null},
      {text:'Superficie : 150 m²',page:1,source:'pdf-text',confidence:null},
      {text:'Longueur du côté A–B : 12,50 m',page:1,source:'pdf-text',confidence:null}
    ]},
    {page:2,method:'ocr',lines:[
      {text:'طول الضلع ب-ج : ٨٥٠ سم',page:2,source:'ocr-ar',confidence:0.85},
      {text:'ج-د : ٣٠ م',page:2,source:'ocr-ar',confidence:0.95},
      {text:'المساحة 150 م²',page:2,source:'ocr-ar',confidence:0.99},
      {text:'CIN : 00112233',page:2,source:'ocr-ar',confidence:0.99}
    ]}
  ]});
  const dto=presentPrivateCpfReading(read,null);
  assert.equal(dto.familyFacts.find(f=>f.id==='registerReference')?.value,'000991');
  assert.equal(dto.familyFacts.find(f=>f.id==='registeredPlotNumber')?.value,'0042');
  assert.equal(dto.familyFacts.find(f=>f.id==='registeredAreaM2')?.value,'150');
  assert.deepEqual(dto.parcelEdgeMeasurements.map(m=>[
    m.from,m.to,m.lengthM,m.evidence.page,m.evidence.source,m.status
  ]),[
    ['A','B','12.5',1,'pdf-text','CANDIDATE'],
    ['ب','ج','8.5',2,'ocr-ar','LOW_CONFIDENCE'],
    ['ج','د','30',2,'ocr-ar','CANDIDATE']
  ]);
  assert.equal(dto.mayPublish,false);
});

test('a scan with dimensions only does not produce any geographic ring',()=>{
  const preview=preparePrivatePlanPreview({
    dimensions:[],
    localOutline:null
  });
  assert.deepEqual(preview,{kind:'DOCUMENT_ONLY',outline:null,dimensions:[]});
  assert.ok(!('geometry' in preview));
});

test('an explicit local outline keeps page units and has no geographic placement',()=>{
  const preview=preparePrivatePlanPreview({
    dimensions:[],
    localOutline:{unit:'PAGE_POINT',page:3,ring:[[20,20],[40,20],[40,50],[20,50],[20,20]]}
  });
  assert.deepEqual(preview,{kind:'DOCUMENT_ONLY',dimensions:[],
    outline:{unit:'PAGE_POINT',page:3,ring:[[20,20],[40,20],[40,50],[20,50],[20,20]]}});
  assert.ok(!('geometry' in preview));
});

test('open, self-crossing, retraced and zero-area plan rings are rejected',()=>{
  for(const ring of [
    [[0,0],[4,0],[4,4],[0,4]],
    [[0,0],[4,4],[4,0],[0,4],[0,0]],
    [[0,0],[4,0],[3,0],[4,4],[0,4],[0,0]],
    [[0,0],[2,0],[4,0],[0,0]]
  ] as const){
    assert.throws(()=>preparePrivatePlanPreview({
      dimensions:[],localOutline:{unit:'PIXEL',page:1,ring}
    }),/INVALID_LOCAL_PLAN_OUTLINE/);
  }
});
