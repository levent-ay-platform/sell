import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {VaultTransitDataKeys} from '../src/vault-transit.ts';

const pair={caseId:randomUUID(),evidenceId:randomUUID()};
const clear=Buffer.alloc(32,19);
const ciphertext='vault:v3:'+Buffer.alloc(60,37).toString('base64');
async function withVault(handler:(path:string,body:any,token:string)=>{status?:number,body:any},run:(baseUrl:string)=>Promise<void>){
 const server=createServer(async(req,res)=>{
  const chunks:Buffer[]=[];for await(const c of req)chunks.push(c);
  let body:any;try{body=JSON.parse(Buffer.concat(chunks).toString());}catch{body={};}
  const r=handler(req.url||'',body,String(req.headers['x-vault-token']||''));
  res.writeHead(r.status||200,{'Content-Type':'application/json'});
  res.end(JSON.stringify(r.body));
 });
 server.listen(0,'127.0.0.1');await once(server,'listening');
 try{
  const addr=server.address();if(!addr||typeof addr==='string')throw Error('missing addr');
  await run(`http://127.0.0.1:${addr.port}/`);
 }finally{await new Promise<void>((ok,err)=>server.close(e=>e?err(e):ok()));}
}
const make=(baseUrl:string)=>new VaultTransitDataKeys({baseUrl,keyName:'malak-evidence',token:async()=> 'mock-token-ONLY_TEST'});
test('restricts non-TLS network targets, credentials, arbitrary paths and bad key names',()=>{
 const rejects=['http://8.8.8.8:8200/','http://example.org:8200/','https://a:b@example.org/',
    'https://example.org/path/','http://127.0.0.1:8200/?abc=1'];
 for(const baseUrl of rejects)assert.throws(()=>make(baseUrl),/PRIVATE_TLS_OR_LOOPBACK/);
 assert.throws(()=>new VaultTransitDataKeys({baseUrl:'http://127.0.0.1:8200/',keyName:'../other',token:async()=> 'some-token-1234'}),/CONFIG_INVALID/);
});
test('generates and unwraps derived context-bound 256-bit keys with strict response parsing',async()=>{
 const expectedContext=Buffer.from(`malak-evidence-v1:${pair.caseId}:${pair.evidenceId}`).toString('base64');
 const calls:string[]=[];
 await withVault((path,body,token)=>{
  assert.equal(token,'mock-token-ONLY_TEST');
  calls.push(path);
  assert.equal(body.context,expectedContext);
  if(path.includes('/datakey/plaintext/')){
   assert.equal(body.bits,256);
   return {body:{data:{ciphertext,plaintext:clear.toString('base64'),key_version:3}}};
  }
  if(path.includes('/decrypt/')){
   assert.equal(body.ciphertext,ciphertext);
   return {body:{data:{plaintext:clear.toString('base64')}}};
  }
  return {status:404,body:{errors:['unknown']}};
 },async(baseUrl)=>{
   const vault=make(baseUrl);
   const a=await vault.generateDataKey(pair);
   assert.equal(a.keyVersion,'v3');assert.equal(a.wrappedKey,ciphertext);
   assert.deepEqual(Buffer.from(a.plaintextKey),clear);
   a.plaintextKey.fill(0);
   const b=await vault.unwrapDataKey({...pair,wrappedKey:ciphertext,keyVersion:'v3'});
   assert.deepEqual(Buffer.from(b),clear);b.fill(0);
 });
 assert.deepEqual(calls,['/v1/transit/datakey/plaintext/malak-evidence','/v1/transit/decrypt/malak-evidence']);
});
test('cross-evidence context cannot recover a wrapped key',async()=>{
 await withVault((_path,body)=>({status:body.context===Buffer.from(`malak-evidence-v1:${pair.caseId}:${pair.evidenceId}`).toString('base64')?200:400,
    body:body.context.endsWith('===')?{data:{plaintext:clear.toString('base64')}}:{errors:['wrong context']}}),async(baseUrl)=>{
   const vault=make(baseUrl);
   await assert.rejects(()=>vault.unwrapDataKey({caseId:pair.caseId,evidenceId:randomUUID(),wrappedKey:ciphertext,keyVersion:'v3'}),/VAULT_TRANSIT_DENIED/);
 });
});
test('rejects tampered wrapped key versions and invalid input IDs before network',async()=>{
 const vault=make('http://127.0.0.1:1/');
 await assert.rejects(()=>vault.unwrapDataKey({...pair,wrappedKey:ciphertext,keyVersion:'v4'}),/VAULT_VERSION_MISMATCH/);
 await assert.rejects(()=>vault.generateDataKey({caseId:'not-a-uuid',evidenceId:pair.evidenceId}),/VAULT_SCOPE_INVALID/);
});
test('never returns a malformed data-key size or mismatched master-key version',async()=>{
 await withVault((_path)=>({body:{data:{ciphertext,plaintext:Buffer.alloc(16).toString('base64'),key_version:3}}}),async(baseUrl)=>{
  await assert.rejects(()=>make(baseUrl).generateDataKey(pair),/VAULT_KEY_RESPONSE_INVALID/);
 });
 await withVault((_path)=>({body:{data:{ciphertext,plaintext:clear.toString('base64'),key_version:4}}}),async(baseUrl)=>{
  await assert.rejects(()=>make(baseUrl).generateDataKey(pair),/VAULT_KEY_RESPONSE_INVALID/);
 });
});
test('unavailable vault, denied policies and malformed vault JSON fail closed without leaking response',async()=>{
 await assert.rejects(()=>make('http://127.0.0.1:1/').generateDataKey(pair),/VAULT_TRANSIT_UNAVAILABLE/);
 await withVault(()=>({status:403,body:{errors:['secret and personally identifiable information here']}}),async(baseUrl)=>{
  await assert.rejects(()=>make(baseUrl).generateDataKey(pair),/VAULT_TRANSIT_DENIED/);
 });
 await withVault(()=>({body:{data:{ciphertext:'vault:v1:broken-key',plaintext:'garbage'}}}),async(baseUrl)=>{
  await assert.rejects(()=>make(baseUrl).generateDataKey(pair),/VAULT_KEY_RESPONSE_INVALID/);
 });
});
