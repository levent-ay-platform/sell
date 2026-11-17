/**
 * Trusted private worker adapter: bounded Python/Poppler/Tesseract OCR.
 * NOT a public execution service. Use only in a dedicated low-privilege
 * sandbox/container, with /tmp on an isolated private tmpfs, never CRM's FS.
 * All output contains potential PII: never print stdout, stderr or paths.
 */
import {spawn} from 'node:child_process';
import {constants} from 'node:fs';
import {lstat,mkdtemp,open,realpath,rm} from 'node:fs/promises';
import {isAbsolute,join,resolve} from 'node:path';
import type {PrivateOcrWorker, SellerOcrRequest} from './seller-ocr-service.ts';
import {exclusivePrivateOcrWorker} from './ocr-capacity-gate.ts';

const MAX_BYTES=12*1024*1024, MAX_JSON=400*1024;
export interface PrivateOcrWorkerConfig {
  readonly tmpRoot:string;
  readonly scriptPath:string;
  readonly pythonPath?:string;
  readonly timeoutMs?:number;
}
async function verifiedRoot(root:string):Promise<string> {
  if(!isAbsolute(root))throw Error('OCR_PRIVATE_ROOT_REQUIRED');
  const abs=resolve(root), st=await lstat(abs);
  if(!st.isDirectory() || st.isSymbolicLink() || (st.mode & 0o077)!==0 ||
      (process.getuid && st.uid!==process.getuid()) || await realpath(abs)!==abs)
    throw Error('OCR_PRIVATE_ROOT_UNSAFE');
  return abs;
}
async function executeOcr(python:string,script:string,path:string,cwd:string,deadline:number):Promise<unknown> {
  const child=spawn(python,['-E','-s',script,path],{
    cwd, detached:process.platform!=='win32',stdio:['ignore','pipe','pipe'],
    env:{PATH:'/usr/bin:/bin',LANG:'C.UTF-8',LC_ALL:'C.UTF-8',PYTHONDONTWRITEBYTECODE:'1'},
  });
  const stdout:Buffer[]=[];let count=0,failed=false,timedOut=false;
  const kill=()=>{
    if(!child.pid)return;
    try {if(process.platform!=='win32')process.kill(-child.pid,'SIGKILL');
      else child.kill('SIGKILL');}catch{/* process may have exited */}
  };
  const timer=setTimeout(()=>{timedOut=true;kill();},deadline);
  child.stdout?.on('data',(part:Buffer)=>{
    count+=part.length;
    if(count>MAX_JSON){failed=true;part.fill(0);kill();}
    else stdout.push(part);
  });
  // Drain but never capture/reprint Python stderr (may contain private paths).
  child.stderr?.resume();
  const exit=await new Promise<number>((fulfill,reject)=>{
    child.once('error',reject);
    child.once('close',code=>fulfill(code??-1));
  }).catch(()=>-1).finally(()=>clearTimeout(timer));
  try {
    if(timedOut||failed||exit!==0||count<10)throw Error('PRIVATE_OCR_UNAVAILABLE');
    const data=Buffer.concat(stdout);
    try {return JSON.parse(data.toString('utf8'));}
    catch {throw Error('PRIVATE_OCR_INVALID_OUTPUT');}
    finally {data.fill(0);}
  } finally {for(const part of stdout)part.fill(0);}
}
/** Python's ingest.py is a trusted, fixed server-configured absolute path. */
export function createPrivatePythonOcrWorker(config:PrivateOcrWorkerConfig):PrivateOcrWorker {
  if(!config||!isAbsolute(config.scriptPath)||
      !isAbsolute(config.pythonPath??'/usr/bin/python3') || (config.pythonPath??'').includes('\0')||
      !Number.isInteger(config.timeoutMs??45000)|| (config.timeoutMs??45000)<5000 ||
      (config.timeoutMs??45000)>60000)throw Error('OCR_WORKER_CONFIG_INVALID');
  return exclusivePrivateOcrWorker({async extract(bytes:Uint8Array,mime:SellerOcrRequest['mime']):Promise<unknown> {
    if(!(bytes instanceof Uint8Array)||bytes.length<20||bytes.length>MAX_BYTES||
      !['application/pdf','image/png','image/jpeg','image/tiff'].includes(mime))
      throw Error('OCR_INVALID_INPUT');
    const root=await verifiedRoot(config.tmpRoot);
    const folder=await mkdtemp(join(root,'case-'));
    const path=join(folder,'evidence.private');
    try {
      const handle=await open(path,constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW|constants.O_WRONLY,0o600);
      try {await handle.writeFile(bytes);}finally{await handle.close();}
      return await executeOcr(config.pythonPath??'/usr/bin/python3',config.scriptPath,
        path,folder,config.timeoutMs??45000);
    } finally {await rm(folder,{recursive:true,force:true});}
  }});
}
