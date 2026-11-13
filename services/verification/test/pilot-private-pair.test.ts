import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {previewPilotPrivatePair,PilotPairDenied} from '../src/pilot-private-pair.ts';

const document=Buffer.from('%PDF-1.4\nSYNTHETIC ONLY\n%%EOF');
const front=Buffer.concat([Buffer.from([255,216,255,224]),Buffer.alloc(64,77)]);
const token='synthetic-shared-pilot-session-but-server-issued-long';
const actor=randomUUID();
const data={bearerToken:token,
 documents:[{bytes:document,mime:'application/pdf' as const}],
 front:{bytes:front,mime:'image/jpeg' as const}};
function setup(){
 let scans=0,reads=0,blocked=false,active=true,actorId=actor,infected=false;
 const deps={
  auth:{authenticate:async (t:string)=>active&&t===token?
   {userId:actorId,pilotUnverified:true as const}:null},
  scanner:{scan:async()=>{scans++;return (infected?'INFECTED':'CLEAN') as 'INFECTED'|'CLEAN';}},
  worker:{extract:async(_bytes:Uint8Array,mime:string)=>{
   reads++;
   const image=mime!=='application/pdf';
   const lines=image?[
    'Nom et prénom: Leila Ben Salem','CIN: 00001122'
   ]:[
    'عقد بيع','المشتري: Leila Ben Salem','البائع: Karim Ben Ahmed',
    'القطعة المبيعة رقم: 82','المساحة المبيعة: 185.25 م²',
    'رقم الرسم العقاري: 555941'
   ];
   return {format:image?'image':'pdf',mayApproveOwnership:false,
    pages:[{page:1,method:image?'ocr':'pdf-text',
     lines:lines.map(text=>({page:1,text,confidence:image?0.99:null,
      source:image?'ocr-fr':'pdf-text'}))}]};
  }}
 };
 return {deps,get scans(){return scans},get reads(){return reads},
  revoke:()=>{active=false},switchActor:()=>{actorId=randomUUID()},
  infect:()=>{infected=true}};
}
test('same pilot bearer scans ONLY submitted new files and matches buyer, never seller',async()=>{
 const h=setup();
 const result=await previewPilotPrivatePair(data,h.deps);
 assert.equal(result.detectedKind,'SALE_DEED');
 assert.equal(result.assessment,'NAME_ONLY_REVIEW');
 assert.equal(result.matchedRole,'BUYER');
 assert.equal(result.documentCinStatus,'NOT_PRINTED');
 assert.equal(result.matchedRightsHolder,null);
 assert.equal(result.ownershipVerified,false);
 assert.equal(result.identityVerified,false);
 assert.equal(result.mayPublish,false);
 assert.equal(result.requiresHumanReview,true);
 assert.equal(result.propertyFacts.find(f=>f.key==='soldPlotNumber')?.value,'82');
 const json=JSON.stringify(result);
 for(const sensitive of ['Leila','Karim','00001122',token])
  assert.equal(json.includes(sensitive),false);
 assert.equal(h.scans,2);
 assert.equal(h.reads,2);
});
test('unknown, verified-owner, or revoked tokens cannot get raw document fields',async()=>{
 const h=setup();
 await assert.rejects(previewPilotPrivatePair({...data,bearerToken:'wrong-token-12345678901234567890'},
  h.deps),PilotPairDenied);
 assert.equal(h.scans,0);
 await assert.rejects(previewPilotPrivatePair(data,{...h.deps,
  auth:{authenticate:async()=>({userId:actor})}}),PilotPairDenied);
 assert.equal(h.scans,0);
 h.revoke();
 await assert.rejects(previewPilotPrivatePair(data,h.deps),PilotPairDenied);
 assert.equal(h.scans,0);
});
test('malware rejection happens before OCR and does not return any names',async()=>{
 const h=setup();h.infect();
 await assert.rejects(previewPilotPrivatePair(data,h.deps),PilotPairDenied);
 assert.equal(h.scans,1);
 assert.equal(h.reads,0);
});
test('switching identity while OCR runs prevents returning comparison',async()=>{
 const h=setup();let switched=false;
 const result={...h.deps,worker:{extract:async (...args:Parameters<typeof h.deps.worker.extract>)=>{
  const output=await h.deps.worker.extract(...args);
  if(!switched){switched=true;h.switchActor();}
  return output;
 }}};
 await assert.rejects(previewPilotPrivatePair(data,result),PilotPairDenied);
});
test('malformed, unsupported, oversized, and empty documents fail before OCR',async()=>{
 const h=setup();
 for(const invalid of [
  {...data,documents:[]},
  {...data,documents:[{bytes:document,mime:'image/jpeg' as const}]},
  {...data,front:{bytes:document,mime:'application/pdf' as const}},
  {...data,documents:[{bytes:Buffer.alloc(5),mime:'application/pdf' as const}]},
  {...data,documents:[{bytes:document,mime:'application/pdf' as const},
                       {bytes:document,mime:'application/pdf' as const}]},
 ]){
  await assert.rejects(previewPilotPrivatePair(invalid,h.deps),PilotPairDenied);
 }
 assert.equal(h.scans,0);
 assert.equal(h.reads,0);
});
