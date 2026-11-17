/**
 * Synthetic ONLY: verify Node gateway -> private AF_UNIX Huawei v5 protocol.
 * No real CINs/photos, model files, public endpoints, tokens or storage.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer, type Server} from 'node:net';
import {unlink} from 'node:fs/promises';
import {createHuaweiV5SocketWorker} from '../src/private-cin-v5-socket-worker.ts';

const PATH='/run/malak-ocr/ocr-v5.sock' as const;
const synthetic=Uint8Array.from([255,216,255,224,...new Array(48).fill(42)]);
const fields={ok:true,surname:'المثال',givenName:'سالم',
  cinLast4:null,birthDate:null,confidencePercent:90};

async function withServer(
  response:(data:Uint8Array,mime:number)=>Record<string,unknown>,
  check:(read:ReturnType<typeof createHuaweiV5SocketWorker>)=>Promise<void>
){
  const server:Server=createServer({allowHalfOpen:true},client=>{
    let input=Buffer.alloc(0),need=-1;
    client.on('data',(data:Buffer)=>{
      input=Buffer.concat([input,data]);
      if(input.length>=5&&need<0){
        need=input.readUInt32BE(0);
        if(need>4096){client.destroy();return;}
      }
      if(need>=0&&input.length===need+5) {
        const payload=Buffer.from(JSON.stringify(response(input.subarray(5),input[4])),
          'utf8');
        const header=Buffer.alloc(4);
        header.writeUInt32BE(payload.length,0);
        client.write(header);
        client.end(payload);
        input.fill(0);
      }
    });
  });
  await new Promise<void>((resolve,reject)=>{
    server.once('error',reject);
    server.listen(PATH,()=>{server.off('error',reject);resolve();});
  });
  try{
    await check(createHuaweiV5SocketWorker({socketPath:PATH,timeoutMs:5000}));
  }finally{
    await new Promise<void>(resolve=>server.close(()=>resolve()));
    await unlink(PATH).catch(()=>{});
  }
}

test('private socket sends framed synthetic bytes and accepts bounded v5 fields',async()=>{
  await withServer((data,mime)=>{
    assert.equal(mime,1);
    assert.deepEqual(data,Buffer.from(synthetic));
    return fields;
  },async worker=>{
    const result=await worker.readFront(synthetic,'image/jpeg');
    assert.equal(result.surname,'المثال');
    assert.equal(result.givenName,'سالم');
    assert.equal(result.confidencePercent,90);
  });
});

test('private socket rejects malformed or unverified model responses',async()=>{
  await withServer(()=>({...fields,ok:false}),async worker=>{
    await assert.rejects(worker.readFront(synthetic,'image/jpeg'),
      /HUAWEI_V5_WORKER_UNAVAILABLE/);
  });
});

test('private socket denies any unexpected response fields',async()=>{
  await withServer(()=>({...fields,secret:'disallowed'}),async worker=>{
    await assert.rejects(worker.readFront(synthetic,'image/jpeg'),
      /HUAWEI_V5_WORKER_UNAVAILABLE/);
  });
});
