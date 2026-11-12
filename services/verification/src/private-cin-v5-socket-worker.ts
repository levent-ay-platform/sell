/**
 * Trusted Huawei gateway -> private warm Paddle Arabic v5 worker.
 * AF_UNIX ONLY; no Docker socket, public port, subprocess or PII logs.
 * The authorized HTTP gateway must check consent/case before and after OCR.
 */
import {createConnection} from 'node:net';
import type {CinFields,CinV5Worker} from './private-cin-v5-service.ts';

const SOCKET='/run/malak-ocr/ocr-v5.sock';
const MAX_BYTES=6*1024*1024;
const MAX_REPLY=4096;

export interface HuaweiV5SocketConfig {
  /** One fixed private path shared between unprivileged gateway/worker UIDs. */
  readonly socketPath:typeof SOCKET;
  readonly timeoutMs?:number;
}

function isFields(data:unknown):data is CinFields&{ok:true} {
  if(!data || typeof data!=='object'||Array.isArray(data))return false;
  const fields=data as Record<string,unknown>;
  const allowed=new Set(['ok','surname','givenName','cinLast4','birthDate','confidencePercent']);
  return Object.keys(fields).length===6 &&
    Object.keys(fields).every(k=>allowed.has(k))&&
    fields.ok===true&&
    (fields.surname===null||typeof fields.surname==='string')&&
    (fields.givenName===null||typeof fields.givenName==='string')&&
    (fields.cinLast4===null||typeof fields.cinLast4==='string')&&
    (fields.birthDate===null||typeof fields.birthDate==='string')&&
    Number.isInteger(fields.confidencePercent)&&
    Number(fields.confidencePercent)>=0&&Number(fields.confidencePercent)<=100;
}
export function createHuaweiV5SocketWorker(config:HuaweiV5SocketConfig):CinV5Worker {
  if(!config||config.socketPath!==SOCKET||
     !Number.isInteger(config.timeoutMs??35000)||
     (config.timeoutMs??35000)<5000||(config.timeoutMs??35000)>45000)
    throw Error('HUAWEI_V5_SOCKET_CONFIG_INVALID');
  const timeoutMs=config.timeoutMs??35000;
  let busy=false; // one per authorized gateway process, reject instead of queue
  return {async readFront(bytes:Uint8Array,mime:'image/jpeg'|'image/png'):Promise<CinFields> {
    if(busy)throw Error('HUAWEI_V5_WORKER_BUSY');
    if(!(bytes instanceof Uint8Array)||bytes.byteLength<20||
       bytes.byteLength>MAX_BYTES||
       (mime!=='image/jpeg'&&mime!=='image/png'))
      throw Error('HUAWEI_V5_WORKER_UNAVAILABLE');
    busy=true;
    try {
      return await new Promise<CinFields>((resolve,reject)=>{
        const sock=createConnection({path:SOCKET});
        let done=false;
        let reply=Buffer.alloc(0);
        let expected=-1;
        const fail=()=>{
          if(done)return;
          done=true;sock.destroy();reply.fill(0);
          reject(Error('HUAWEI_V5_WORKER_UNAVAILABLE'));
        };
        sock.setTimeout(timeoutMs,fail);
        sock.once('error',fail);
        sock.once('close',()=>{if(!done)fail();});
        sock.on('data',(chunk:Buffer)=>{
          if(done)return;
          if(reply.length+chunk.length>MAX_REPLY+4){fail();return;}
          reply=Buffer.concat([reply,chunk]);
          if(reply.length>=4&&expected<0){
            expected=reply.readUInt32BE(0);
            if(expected<12||expected>MAX_REPLY){fail();return;}
          }
          if(expected>=0){
            if(reply.length>expected+4){fail();return;}
            if(reply.length===expected+4){
              let parsed:unknown;
              try{
                parsed=JSON.parse(reply.subarray(4).toString('utf8'));
              }catch{fail();return;}
              if(!isFields(parsed)){fail();return;}
              done=true;
              sock.destroy();reply.fill(0);
              const fields=parsed as CinFields;
              resolve({
                surname:fields.surname,givenName:fields.givenName,
                cinLast4:fields.cinLast4,birthDate:fields.birthDate,
                confidencePercent:fields.confidencePercent
              });
            }
          }
        });
        sock.once('connect',()=>{
          if(done)return;
          const header=Buffer.alloc(5);
          header.writeUInt32BE(bytes.byteLength,0);
          header[4]=mime==='image/jpeg'?1:2;
          sock.write(header);
          sock.end(bytes);
        });
      });
    }finally{busy=false;}
  }};
}
