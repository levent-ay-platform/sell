/**
 * ClamAV clamd INSTREAM adapter; never writes source documents to disk.
 * Only loopback TCP or a local socket may be configured; never log scan data.
 * UNAVAILABLE/ERROR must return UNKNOWN, which upload denies.
 */
import {createConnection, isIP} from 'node:net';
import type {MediaType} from './capture.ts';
import type {MalwareScanner} from './evidence-upload.ts';

const MAX_BYTES=12*1024*1024;
const CHUNK=64*1024;
export type ClamdAddress={path:string}|{host:'127.0.0.1'|'::1';port:number};

export class LocalClamdScanner implements MalwareScanner {
  private readonly address:ClamdAddress;
  private readonly timeoutMs:number;
  constructor(address:ClamdAddress,{timeoutMs=7500}:{timeoutMs?:number}={}) {
    if('path' in address) {
      if(typeof address.path!=='string'||!address.path.startsWith('/')||address.path.includes('\0'))
        throw new Error('CLAMD_LOCAL_SOCKET_REQUIRED');
    } else if (!((address.host==='127.0.0.1'||address.host==='::1')&&
        isIP(address.host)>0&&Number.isInteger(address.port)&&address.port>0&&address.port<=65535)) {
      throw new Error('CLAMD_LOOPBACK_ONLY');
    }
    if(!Number.isInteger(timeoutMs)||timeoutMs<500||timeoutMs>60000)
      throw new Error('CLAMD_TIMEOUT_INVALID');
    this.address=address;this.timeoutMs=timeoutMs;
  }
  async scan(bytes:Uint8Array,_mime:MediaType):Promise<'CLEAN'|'INFECTED'|'UNKNOWN'> {
    if(!(bytes instanceof Uint8Array)||bytes.byteLength<1||bytes.byteLength>MAX_BYTES)
      return 'UNKNOWN';
    const address=this.address;
    return new Promise(resolve=>{
      let settled=false,reply=Buffer.alloc(0),offset=0;
      const socket='path' in address?
        createConnection({path:address.path}):
        createConnection({host:address.host,port:address.port});
      const timer=setTimeout(()=>finish('UNKNOWN'),this.timeoutMs);
      const finish=(status:'CLEAN'|'INFECTED'|'UNKNOWN')=>{
        if(settled)return;
        settled=true;clearTimeout(timer);socket.destroy();resolve(status);
      };
      socket.setTimeout(this.timeoutMs);
      socket.on('timeout',()=>finish('UNKNOWN'));
      socket.on('error',()=>finish('UNKNOWN'));
      socket.on('close',()=>finish('UNKNOWN'));
      socket.on('data',chunk=>{
        if(settled)return;
        reply=Buffer.concat([reply,chunk]);
        if(reply.length>1024)return finish('UNKNOWN');
        const end=reply.findIndex(v=>v===0||v===10);
        if(end<0)return;
        const message=reply.subarray(0,end).toString('utf8').trim();
        if(/^stream: OK$/.test(message))finish('CLEAN');
        else if(/^stream: .{1,900} FOUND$/.test(message))finish('INFECTED');
        else finish('UNKNOWN');
      });
      const pump=()=>{
        if(settled)return;
        while(offset<bytes.length) {
          const size=Math.min(CHUNK,bytes.length-offset);
          const hdr=Buffer.alloc(4);hdr.writeUInt32BE(size);
          offset+=size;
          const wroteHeader=socket.write(hdr);
          const wroteData=socket.write(bytes.subarray(offset-size,offset));
          if(!wroteHeader || !wroteData) {
            socket.once('drain',pump);return;
          }
        }
        socket.write(Buffer.alloc(4)); // clamd responds while socket stays open
      };
      socket.on('connect',()=>{
        if(!socket.write(Buffer.from('zINSTREAM\0')))socket.once('drain',pump);
        else pump();
      });
    });
  }
}
