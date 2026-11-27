import test from 'node:test';
import assert from 'node:assert/strict';
import {auditPrintedPagination,printedPageNumbers} from '../src/cpf-pagination.ts';
import type {RecognizedLine} from '../src/smart-fields.ts';
function p(page:number,text:string,confidence:number|null=.99,source:RecognizedLine['source']='ocr-ar'){
 return {page,lines:[{page,text,confidence,source}]};
}
test('one parser handles explicit Arabic, Persian digits, bidi and French pages',()=>{
 for(const s of ['صفحة ١ من ٢','صفحه: ۱ / ۲','2 صفحة 1 من','Page 1 sur 2','Page 1 de 2','Page 1 of 2'])
   assert.deepEqual(printedPageNumbers(s),[{page:1,total:2}]);
 assert.deepEqual(printedPageNumbers('1/2'),[]);
});
test('trusted declared missing physical pages are distinct from unreadable footers',()=>{
 assert.deepEqual(auditPrintedPagination([p(1,'صفحة 1 من 2')]),
  {status:'MISSING_PAGES',expectedPages:2,receivedPages:1,missingPages:[2]});
 assert.deepEqual(auditPrintedPagination([p(1,'صفحة 1 من 2'),p(2,'unreadable')]),
  {status:'UNREADABLE',expectedPages:2,receivedPages:2,missingPages:[]});
 assert.deepEqual(auditPrintedPagination([p(1,'صفحة 1 من 8',.5)]),
  {status:'UNREADABLE',expectedPages:null,receivedPages:1,missingPages:[]});
});
test('low confidence, inconsistent or reordered footers cannot establish completeness',()=>{
 assert.equal(auditPrintedPagination([p(1,'صفحة 1 من 1',.5)]).status,'UNREADABLE');
 assert.equal(auditPrintedPagination([p(1,'صفحة 1 من 1',null)]).status,'UNREADABLE');
 assert.equal(auditPrintedPagination([p(1,'صفحة 1 من 1',null,'pdf-text')]).status,'COMPLETE');
 assert.equal(auditPrintedPagination([p(1,'صفحة 1 من 2 صفحة 1 من 3')]).status,'CONFLICT');
 assert.equal(auditPrintedPagination([p(1,'Page 2 sur 2'),p(2,'Page 1 sur 2')]).status,'CONFLICT');
 assert.equal(auditPrintedPagination([p(1,'صفحة 1 من 2'),p(2,'Page 2 sur 2')]).status,'COMPLETE');
});
