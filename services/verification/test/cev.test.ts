import test from 'node:test';
import assert from 'node:assert/strict';
import {verifyTunTrustCev,TUNTRUST_CEV_HTTPS_URL} from '../src/cev.ts';
const token='DC0400EXAMPLE_SYNTHETIC_SIGNED_QR_FOR_TEST_ONLY';
const json=(x:unknown,status=200)=>new Response(JSON.stringify(x),
  {status,headers:{'content-type':'application/json'}});
test('sends only signed QR text via HTTPS POST',async()=>{
 let request:{url:string;init:RequestInit}|undefined;
 const result=await verifyTunTrustCev(token,{trustedIssuerNames:['Test CPF'],fetchImpl:async(url,init)=>{
   request={url:String(url),init:init!};
   return json({result:false,verifCertif:false,errorId:'502'});
 }});
 assert.equal(request?.url,TUNTRUST_CEV_HTTPS_URL);
 assert.equal(request?.init.method,'POST');
 assert.deepEqual(JSON.parse(String(request?.init.body)),{value:token});
 assert.deepEqual(result,{status:'INVALID_OR_UNTRUSTED',code:'502'});
});
test('short/bad QR is rejected without HTTP call',async()=>{
 let called=false;
 const r=await verifyTunTrustCev('NOT_A_QR',{trustedIssuerNames:[],fetchImpl:async()=>{
   called=true;throw Error('must not call');
 }});
 assert.equal(called,false);
 assert.deepEqual(r,{status:'INVALID_OR_UNTRUSTED',code:'INVALID_INPUT'});
});
test('valid-looking signature from unrelated issuer fails closed',async()=>{
 const r=await verifyTunTrustCev(token,{trustedIssuerNames:[],fetchImpl:async()=>json({
   result:true,verifCertif:true,signer:{value:'Unknown'},details:[]
 })});
 assert.deepEqual(r,{status:'INVALID_OR_UNTRUSTED',code:'UNRECOGNIZED_ISSUER'});
});
test('signed QR contents are returned only for document matching, never ownership',async()=>{
 const r=await verifyTunTrustCev(token,{trustedIssuerNames:['Demo CPF'],fetchImpl:async()=>json({
   result:true,verifCertif:true,signer:{value:'Demo CPF'},dateSign:{value:'2026-11-10'},
   document:{value:'TEST'},details:[{id:'F1',label:'title',value:'FAKE-000'}]
 })});
 assert.equal(r.status,'SIGNATURE_VALID_CONTENT_NOT_YET_MATCHED');
 if(r.status==='SIGNATURE_VALID_CONTENT_NOT_YET_MATCHED') {
   assert.equal(r.signedFields[0]?.value,'FAKE-000');
   assert.equal(r.issuer,'Demo CPF');
 }
});
test('503 service returns no approval',async()=>{
 const r=await verifyTunTrustCev(token,{trustedIssuerNames:[],fetchImpl:async()=>
   new Response('<h1>down</h1>',{status:503,headers:{'content-type':'text/html'}})});
 assert.equal(r.status,'SERVICE_UNAVAILABLE');
});
test('valid-looking response without details is unrecognized',async()=>{
 const r=await verifyTunTrustCev(token,{trustedIssuerNames:['Demo CPF'],fetchImpl:async()=>json({
   result:true,verifCertif:true,signer:{value:'Demo CPF'},details:{field:'not an array'}
 })});
 assert.equal(r.status,'RESPONSE_UNRECOGNIZED');
});
test('timeouts/transport errors fail closed',async()=>{
 const r=await verifyTunTrustCev(token,{trustedIssuerNames:[],fetchImpl:async()=>{
  throw Error('network');
 }});
 assert.equal(r.status,'SERVICE_UNAVAILABLE');
});
test('real synthetic rejection schema from verified production probe is parsed',async()=>{
 const r=await verifyTunTrustCev(token,{trustedIssuerNames:[],fetchImpl:async()=>json({
   result:false,verifCertif:false,verifCertifOr:false,errorId:'502',
   error:'version 2d doc invalide _M'
 })});
 assert.deepEqual(r,{status:'INVALID_OR_UNTRUSTED',code:'502'});
});
