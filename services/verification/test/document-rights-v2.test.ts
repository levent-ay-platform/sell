import test from 'node:test';
import assert from 'node:assert/strict';
import {balanceOwnershipParts,compareDocumentParcelScope} from '../src/document-rights-v2.ts';

const r=(n:number,parts:string|null,state:'EXTRACTED'|'MASKED'|'NOT_IN_SOURCE'|'UNREADABLE'='EXTRACTED')=>
  ({rowNumber:n,parts,state});
test('seven fabricated owners balance exactly using decimal milliparts',()=>{
 const a=balanceOwnershipParts([
  r(1,'230.333'),r(2,'350.112'),r(3,'245,055'),r(4,'50.000'),
  r(5,'51.500'),r(6,'32.000'),r(7,'41.000')],'1000');
 assert.equal(a.status,'BALANCED');
 assert.equal(a.sumParts,'1000');
 assert.equal(a.legalOwnershipConfirmed,false);
});
test('one missing owner on another page cannot be treated as balanced',()=>{
 const a=balanceOwnershipParts([r(1,'600'),r(2,null,'UNREADABLE')],'600');
 assert.equal(a.status,'INCOMPLETE');
 assert.deepEqual(a.missingRows,[2]);
});
test('a masked printed field is not a parser failure and cannot be silently filled',()=>{
 const a=balanceOwnershipParts([r(1,null,'MASKED')],'1000');
 assert.equal(a.status,'INCOMPLETE');
 assert.deepEqual(a.missingRows,[1]);
});
test('decimal arithmetic catches even a one-millipart discrepancy',()=>{
 const a=balanceOwnershipParts([r(1,'500.001'),r(2,'499.998')],'1000');
 assert.equal(a.status,'UNBALANCED');
 assert.equal(a.sumParts,'999.999');
});
test('numeric parsing preserves Arabic digits and rejects unclear separators',()=>{
 assert.equal(balanceOwnershipParts([r(1,'٣٣٣,٣٣٣'),r(2,'٦٦٦,٦٦٧')],'١٠٠٠').status,'BALANCED');
 assert.equal(balanceOwnershipParts([r(1,'1,200,000')],'1200000').status,'INVALID');
});
test('duplicate owner row, missing denominator or absent owners fail closed',()=>{
 assert.equal(balanceOwnershipParts([r(1,'1'),r(1,'1')],'2').status,'INVALID');
 assert.equal(balanceOwnershipParts([r(1,'100')],null).status,'INVALID');
 assert.equal(balanceOwnershipParts([],'100').status,'INVALID');
});
test('two plots under one parent title are NEVER assumed identical',()=>{
 const base={registerNumber:'TITLE-FAKE-1',registryOffice:'Nabeul Fictional',plotNumber:'81'};
 assert.equal(compareDocumentParcelScope(base,{...base,plotNumber:'82'}),'DIFFERENT_PLOT');
 assert.equal(compareDocumentParcelScope(base,{...base,plotNumber:'81'}),'SAME_PLOT');
 assert.equal(compareDocumentParcelScope(base,{...base,registerNumber:'TITLE-FAKE-2'}),'DIFFERENT_REGISTER');
 assert.equal(compareDocumentParcelScope(base,{...base,plotNumber:null}),'INSUFFICIENT_REFERENCE');
});
