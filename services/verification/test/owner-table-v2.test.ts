import test from 'node:test';
import assert from 'node:assert/strict';
import {analyzeOwnerTable,parsePrintedOwnerOrdinal} from '../src/owner-table-v2.ts';
import type {OwnerTableCellObservation,OwnerCellField} from '../src/owner-table-v2.ts';

// Fictional sample table only. Never commit real owner identities or CINs.
const opts={declaredPages:2,receivedPages:[1,2],declaredOwnerCount:7,printedTotalParts:'1000'};
const cell=(ordinal:number,field:OwnerCellField,value:string|null,page=ordinal<=3?1:2,
    state:OwnerTableCellObservation['state']='EXTRACTED',confidence:number|null=0.99
  ):OwnerTableCellObservation=>({ordinal,field,value,page,state,
    confidence,lineIndex:ordinal,source:'ocr-ar'});
const syntheticRows=():OwnerTableCellObservation[]=>{
 const amounts=['230.333','350.112','245.055','50','51.500','32','41'];
 return amounts.flatMap((amount,i)=>[
   cell(i+1,'name','شخص افتراضي '+(i+1)),
   cell(i+1,'rightsParts',amount)
 ]);
};
test('CPF printed 1/n is owner ordinal and NOT a fractional ownership share',()=>{
 assert.equal(parsePrintedOwnerOrdinal('1/1'),1);
 assert.equal(parsePrintedOwnerOrdinal('١/٧'),7);
 assert.equal(parsePrintedOwnerOrdinal('1 / 25'),25);
 for(const value of ['3/7','1/0','500/1000','1/501','abc','1/1/4'])
   assert.equal(parsePrintedOwnerOrdinal(value),null);
});
test('seven cross-page fictional rights holders balance and remain unverified',()=>{
 const observations=[...syntheticRows(),
   cell(1,'cin','00123456'),
   cell(2,'cin',null,1,'MASKED'),
   cell(3,'cin',null,1,'NOT_IN_SOURCE'),
   cell(7,'birthDate',null,2,'NOT_IN_SOURCE'),
   cell(7,'filingReference','18/9999')];
 const a=analyzeOwnerTable(observations,opts);
 assert.equal(a.rows.length,7);
 assert.deepEqual(a.rows.map(r=>r.ordinal),[1,2,3,4,5,6,7]);
 assert.equal(a.rightsBalance.status,'BALANCED');
 assert.equal(a.rightsBalance.sumParts,'1000');
 assert.equal(a.readyForHumanReview,true);
 assert.equal(a.ownershipVerified,false);
 assert.equal(a.mayPublish,false);
 assert.equal(a.rows[0].fields.cin.value,'00123456');
 assert.equal(a.rows[1].fields.cin.status,'MASKED');
 assert.equal(a.rows[2].fields.cin.status,'NOT_IN_SOURCE');
 assert.equal(a.rows[4].fields.cin.status,'NOT_OBSERVED');
 assert.equal(a.rows[6].fields.birthDate.status,'NOT_IN_SOURCE');
 assert.deepEqual(a.rows[6].fields.filingReference.evidence.map(e=>e.page),[2]);
});
test('a duplicate reading of the same field on next page preserves provenance',()=>{
 const rows=syntheticRows();
 rows.push(cell(3,'rightsParts','245.055',2));
 const a=analyzeOwnerTable(rows,opts);
 assert.equal(a.rightsBalance.status,'BALANCED');
 assert.equal(a.rows[2].fields.rightsParts.evidence.length,2);
 assert.deepEqual(a.rows[2].sourcePages,[1,2]);
 assert.ok(!a.issues.includes('OWNER_ROW_CONFLICT'));
});
test('different names or rights share on repeated same owner index fail closed',()=>{
 const rows=syntheticRows();
 rows.push(cell(4,'name','شخص افتراضي مختلف',2));
 const a=analyzeOwnerTable(rows,opts);
 assert.equal(a.rows[3].fields.name.status,'CONFLICT');
 assert.ok(a.issues.includes('OWNER_ROW_CONFLICT'));
 assert.equal(a.readyForHumanReview,false);
});
test('one missing numbered owner fails even when the remaining shares sum to denominator',()=>{
 const rows=syntheticRows().filter(r=>r.ordinal!==6);
 const a=analyzeOwnerTable(rows,{...opts,printedTotalParts:'968'});
 assert.deepEqual(a.missingOwnerOrdinals,[6]);
 assert.ok(a.issues.includes('OWNER_ROW_MISSING'));
 assert.equal(a.readyForHumanReview,false);
});
test('missing second page prevents complete rights-table review',()=>{
 const a=analyzeOwnerTable(syntheticRows().filter(r=>r.page===1),
   {...opts,receivedPages:[1]});
 assert.deepEqual(a.missingPages,[2]);
 assert.ok(a.issues.includes('PAGE_SET_INCOMPLETE'));
 assert.equal(a.readyForHumanReview,false);
});
test('masked rights amount is incomplete, never interpreted as zero',()=>{
 const rows=syntheticRows().map(r=>r.ordinal===2&&r.field==='rightsParts'?
   {...r,state:'MASKED' as const,value:null}:r);
 const a=analyzeOwnerTable(rows,opts);
 assert.equal(a.rows[1].fields.rightsParts.status,'MASKED');
 assert.equal(a.rightsBalance.status,'INCOMPLETE');
 assert.ok(a.issues.includes('PARTS_BALANCE_NOT_CONFIRMED'));
});
test('low confidence on owners or shares never yields ready-to-review completeness',()=>{
 const rows=syntheticRows().map(r=>r.ordinal===5&&r.field==='rightsParts'?
   {...r,confidence:0.52}:r);
 const a=analyzeOwnerTable(rows,opts);
 assert.equal(a.rows[4].fields.rightsParts.status,'LOW_CONFIDENCE');
 assert.ok(a.issues.includes('OWNER_LOW_CONFIDENCE'));
 assert.equal(a.readyForHumanReview,false);
});
test('CIN is exact eight-digit string, preserving leading zeros',()=>{
 const a=analyzeOwnerTable([...syntheticRows(),cell(2,'cin','00001122')],opts);
 assert.equal(a.rows[1].fields.cin.value,'00001122');
 assert.ok(!a.issues.includes('OWNER_CIN_INVALID'));
 const b=analyzeOwnerTable([...syntheticRows(),cell(2,'cin','001122')],opts);
 assert.ok(b.issues.includes('OWNER_CIN_INVALID'));
 assert.equal(b.readyForHumanReview,false);
});
test('a share total one millipart lower is not silently rounded',()=>{
 const rows=syntheticRows().map(r=>r.ordinal===7&&r.field==='rightsParts'?
   {...r,value:'40.999'}:r);
 const a=analyzeOwnerTable(rows,opts);
 assert.equal(a.rightsBalance.status,'UNBALANCED');
 assert.equal(a.rightsBalance.sumParts,'999.999');
 assert.equal(a.readyForHumanReview,false);
});
test('invalid OCR evidence or misplaced source page is refused',()=>{
 const rows=syntheticRows();
 assert.throws(()=>analyzeOwnerTable([...rows,cell(1,'cin','12345678',3)],opts),
   /OWNER_CELL_PAGE_NOT_RECEIVED/);
 assert.throws(()=>analyzeOwnerTable([...rows,cell(1,'cin','12345678',1,'MASKED')],opts),
   /INVALID_OWNER_TABLE_CELL/);
 assert.throws(()=>analyzeOwnerTable([...rows,cell(1,'cin','████████')],opts),
   /INVALID_OWNER_TABLE_CELL/);
 assert.throws(()=>analyzeOwnerTable(rows,{...opts,receivedPages:[1,1]}),
   /DUPLICATE_OWNER_TABLE_PAGE/);
});
test('missing document total and owner count cannot claim complete table',()=>{
 const a=analyzeOwnerTable(syntheticRows(),{...opts,declaredOwnerCount:null,printedTotalParts:null});
 assert.equal(a.rightsBalance.status,'INVALID');
 assert.ok(a.issues.includes('OWNER_ROW_MISSING'));
 assert.ok(a.issues.includes('PARTS_BALANCE_NOT_CONFIRMED'));
 assert.equal(a.readyForHumanReview,false);
});
