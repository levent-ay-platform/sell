import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:net';
import {once} from 'node:events';
import {LocalClamdScanner} from '../src/clamav-scanner.ts';
async function withClamd(reply:string|undefined,run:(port:number,reads:()=>number)=>Promise<void>){
 let received=0;
 const sockets=new Set<import('node:net').Socket>();
 const server=createServer(socket=>{
  sockets.add(socket);socket.on('close',()=>sockets.delete(socket));
  let buffer=Buffer.alloc(0),header=false,completed=false;
  socket.on('data',data=>{
   buffer=Buffer.concat([buffer,data]);
   if(!header){if(buffer.length<10)return;assert.equal(buffer.subarray(0,10).toString(),'zINSTREAM\0');buffer=buffer.subarray(10);header=true;}
   while(buffer.length>=4){
    const size=buffer.readUInt32BE();if(buffer.length<4+size)return;
    buffer=buffer.subarray(4+size); if(size===0){completed=true; if(reply!==undefined)socket.end(reply+'\0');return;}
    received+=size;
   }
  });
 });
 server.listen(0,'127.0.0.1');await once(server,'listening');
 try{const address=server.address();if(typeof address!=='object'||!address)throw Error('no address');
  await run(address.port,()=>received);
 }finally{for(const socket of sockets)socket.destroy();await new Promise<void>((resolve,reject)=>server.close(err=>err?reject(err):resolve()));}
}
const doc=Buffer.from('%PDF-1.4\nFICTIONAL TEST - NOT A REAL DOCUMENT');
test('CLEAN only on exact positive clamd response after INSTREAM bytes arrive',async()=>{
 await withClamd('stream: OK',async(port,reads)=>{
  const scan=new LocalClamdScanner({host:'127.0.0.1',port});
  assert.equal(await scan.scan(doc,'application/pdf'),'CLEAN');assert.equal(reads(),doc.length);
 });
});
test('FOUND detects infected content and cannot be misread as clean',async()=>{
 await withClamd('stream: Eicar-Test-Signature FOUND',async(port)=>{
  assert.equal(await new LocalClamdScanner({host:'127.0.0.1',port}).scan(doc,'application/pdf'),'INFECTED');
 });
});
test('clamd ERROR and malformed status both fail closed',async()=>{
 for(const response of ['stream: INSTREAM size limit exceeded. ERROR','stream: UNKNOWN','not clamd OK']){
  await withClamd(response,async(port)=>{
   assert.equal(await new LocalClamdScanner({host:'127.0.0.1',port}).scan(doc,'application/pdf'),'UNKNOWN');
  });
 }
});
test('timeout fails closed, not clean',async()=>{
 await withClamd(undefined,async(port)=>{
  const scanner=new LocalClamdScanner({host:'127.0.0.1',port},{timeoutMs:500});
  assert.equal(await scanner.scan(doc,'application/pdf'),'UNKNOWN');
 });
});
test('network error fails closed, no bypass',async()=>{
 const scanner=new LocalClamdScanner({host:'127.0.0.1',port:1},{timeoutMs:500});
 assert.equal(await scanner.scan(doc,'application/pdf'),'UNKNOWN');
});
test('never sends evidence to remote clamd; invalid timeout rejected',()=>{
 assert.throws(()=>new LocalClamdScanner({host:'8.8.8.8',port:3310} as any),/LOOPBACK_ONLY/);
 assert.throws(()=>new LocalClamdScanner({host:'127.0.0.1',port:3310},{timeoutMs:0}),/TIMEOUT_INVALID/);
});
test('over-limit document cannot be scanned as clean',async()=>{
 const scanner=new LocalClamdScanner({host:'127.0.0.1',port:1});
 assert.equal(await scanner.scan(new Uint8Array(12*1024*1024+1),'application/pdf'),'UNKNOWN');
});
