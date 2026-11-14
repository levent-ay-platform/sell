import test from 'node:test';
import assert from 'node:assert/strict';
import {exclusivePrivateOcrWorker} from '../src/ocr-capacity-gate.ts';
import type {SellerOcrRequest} from '../src/seller-ocr-service.ts';

const bytes=new Uint8Array([1,2,3]);
const mime='application/pdf' as SellerOcrRequest['mime'];
test('never schedules two OCR processes concurrently; second request is rejected before allocation',async()=>{
 let active=0,maxActive=0,release:()=>void=()=>{};
 const block=new Promise<void>(r=>{release=r;});
 const worker=exclusivePrivateOcrWorker({
   extract:async()=>{
     active++;maxActive=Math.max(maxActive,active);
     try{await block;return {ok:true};}finally{active--;}
   }
 });
 const first=worker.extract(bytes,mime);
 await assert.rejects(worker.extract(bytes,mime),/PRIVATE_OCR_BUSY/);
 assert.equal(active,1);
 release();
 assert.deepEqual(await first,{ok:true});
 assert.deepEqual(await worker.extract(bytes,mime),{ok:true});
 assert.equal(maxActive,1);
});
test('worker rejection releases the only permit for safe retry',async()=>{
 let failures=0;
 const worker=exclusivePrivateOcrWorker({
   extract:async()=>{if(failures++===0)throw Error('SYNTHETIC_OCR_ERROR');return 'recovered';}
 });
 await assert.rejects(worker.extract(bytes,mime),/SYNTHETIC_OCR_ERROR/);
 assert.equal(await worker.extract(bytes,mime),'recovered');
});
test('worker must be configured before use',()=>{
 assert.throws(()=>exclusivePrivateOcrWorker(null as any),/OCR_WORKER_REQUIRED/);
});
