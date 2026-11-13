import test from 'node:test';
import assert from 'node:assert/strict';
import {locateOwnerTableFromWords} from '../src/owner-table-layout-v2.ts';
import {analyzeOwnerTable} from '../src/owner-table-v2.ts';
import type {SpatialPage,SpatialWord} from '../src/owner-table-layout-v2.ts';

// 100% fabricated synthetic geometry and identities, never a real owner/CIN.
const word=(text:string,page:number,x:number,y:number,confidence=0.99):SpatialWord=>({
  text,page,confidence,bbox:{left:x-24,top:y-12,right:x+24,bottom:y+12}
});
const row=(n:number,page:number,y:number,part:string,person:string):SpatialWord[]=>[
  word('1/'+n,page,945,y),word(part,page,580,y+22),
  word('جزء',page,580,y+48),word(person,page,830,y+20),
  word('بنت',page,790,y+20),word('سالم',page,748,y+20)
];
const sample=():SpatialPage[]=>[
  {page:1,width:1000,height:1500,words:[
    word('موضوع',1,580,290),word('هوية',1,790,290),word('العدد',1,945,290),
    ...row(1,1,490,'300.125','جميلة'),
    ...row(2,1,770,'250.250','سناء'),
    ...row(3,1,1060,'100.125','أمينة')]},
  {page:2,width:1000,height:1500,words:[
    ...row(4,2,230,'100.000','جليلة'),
    ...row(5,2,520,'100.000','ليلى'),
    ...row(6,2,790,'75.000','سميرة'),
    ...row(7,2,1070,'74.500','نورا')]}
];
test('infer table columns from headers and join seven numbered rows across pages',()=>{
  const x=locateOwnerTableFromWords(sample());
  assert.deepEqual(x.ownerOrdinals,[1,2,3,4,5,6,7]);
  assert.deepEqual(x.findings,[]);
  assert.equal(x.cells.length,14);
  const a=analyzeOwnerTable(x.cells,{
    receivedPages:[1,2],declaredPages:2,
    declaredOwnerCount:7,printedTotalParts:'1000'
  });
  assert.equal(a.rightsBalance.status,'BALANCED');
  assert.equal(a.readyForHumanReview,true);
  assert.equal(a.ownershipVerified,false);
  assert.equal(a.mayPublish,false);
  assert.equal(x.mayVerifyOwnership,false);
  assert.equal(x.mayPublish,false);
});
test('missing first-page header abstains even if owner-looking numbers exist',()=>{
  const pages=sample();
  const x=locateOwnerTableFromWords([{...pages[0],
    words:pages[0].words.filter(w=>w.bbox.top>350)},pages[1]]);
  assert.deepEqual(x.ownerOrdinals,[]);
  assert.deepEqual(x.findings,['HEADER_NOT_FOUND']);
});
test('missing printed part-unit marker prevents invented share',()=>{
  const pages=sample();
  const first={...pages[0],words:pages[0].words.filter(w=>!(
    w.text==='جزء'&&w.bbox.top>510&&w.bbox.top<560))};
  const x=locateOwnerTableFromWords([first,pages[1]]);
  assert.ok(x.findings.includes('SHARE_NOT_LOCATED'));
  assert.equal(x.cells.some(c=>c.ordinal===1&&c.field==='rightsParts'),false);
});
test('competing share numbers are ambiguous instead of selecting highest confidence',()=>{
  const pages=sample();
  const first={...pages[0],words:[...pages[0].words,word('301.125',1,581,512)]};
  const x=locateOwnerTableFromWords([first,pages[1]]);
  assert.ok(x.findings.includes('SHARE_AMBIGUOUS'));
  assert.equal(x.cells.some(c=>c.ordinal===1&&c.field==='rightsParts'),false);
});
test('numeric identity token in a different column is never ownership part',()=>{
  const pages=sample();
  const x=locateOwnerTableFromWords([{...pages[0],
    words:[...pages[0].words,word('00001122',1,810,590)]},pages[1]]);
  assert.equal(x.cells.find(c=>c.ordinal===1&&c.field==='rightsParts')?.value,'300.125');
  assert.equal(x.cells.some(c=>c.value==='00001122'),false);
});
test('without page one, later page alone cannot establish headings',()=>{
  const x=locateOwnerTableFromWords([sample()[1]]);
  assert.deepEqual(x.findings,['NO_PAGE_1']);
  assert.equal(x.cells.length,0);
});
test('duplicate printed owner index fails closed',()=>{
  const pages=sample();
  const x=locateOwnerTableFromWords([pages[0],
    {...pages[1],words:[...pages[1].words,word('1/4',2,945,1280)]}]);
  assert.ok(x.findings.includes('DUPLICATE_OWNER_ORDINAL'));
  assert.equal(x.ownerOrdinals.filter(n=>n===4).length,1);
});
test('rejects invalid confidence, out-of-bounds geometry and duplicate pages',()=>{
  const pg=sample()[0];
  assert.throws(()=>locateOwnerTableFromWords([{...pg,words:[
    {...pg.words[0],confidence:1.5}
  ]}]),/INVALID_SPATIAL_WORD/);
  assert.throws(()=>locateOwnerTableFromWords([{...pg,words:[
    {...pg.words[0],bbox:{left:-1,top:0,right:12,bottom:20}}
  ]}]),/INVALID_SPATIAL_WORD/);
  assert.throws(()=>locateOwnerTableFromWords([pg,pg]),/INVALID_SPATIAL_PAGE/);
});


test('a CIN inside a specific numbered owner row requires a nearby explicit CIN label',()=>{
 const pages=sample();
 const first={...pages[0],words:[...pages[0].words,
  word('CIN',1,755,594),word('00001122',1,810,594)]};
 const x=locateOwnerTableFromWords([first,pages[1]]);
 assert.equal(x.cells.find(c=>c.ordinal===1&&c.field==='cin')?.value,'00001122');
 assert.equal(x.cells.some(c=>c.ordinal===2&&c.field==='cin'),false);
 assert.equal(x.cells.filter(c=>c.field==='cin').length,1);
});
test('unlabelled eight-digit title register cannot count as a row CIN',()=>{
 const pages=sample();
 const first={...pages[0],words:[...pages[0].words,
  word('00001122',1,810,594)]};
 const x=locateOwnerTableFromWords([first,pages[1]]);
 const cells=x.cells.filter(c=>c.field==='cin');
 assert.equal(cells.length,1);
 assert.equal(cells[0]?.value,null);
 assert.equal(cells[0]?.state,'UNREADABLE');
});
test('competing same-row labelled CIN digits produce no CIN cell',()=>{
 const pages=sample();
 const first={...pages[0],words:[...pages[0].words,
  word('CIN',1,755,594),word('00001122',1,810,594),
  word('00003344',1,830,610)]};
 const x=locateOwnerTableFromWords([first,pages[1]]);
 assert.ok(x.findings.includes('CIN_AMBIGUOUS'));
 assert.equal(x.cells.filter(c=>c.ordinal===1&&c.field==='cin').length,0);
});
