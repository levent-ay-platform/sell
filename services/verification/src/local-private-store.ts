/**
 * Private ciphertext-only, filesystem-backed evidence store for a dedicated
 * Malak service account. NOT a public/static file handler. Real encryption and
 * authorization happen BEFORE putPrivate; server config mounts root privately.
 */
import {constants} from 'node:fs';
import {lstat, mkdir, open, realpath, unlink} from 'node:fs/promises';
import {isAbsolute, join, resolve} from 'node:path';
import type {PrivateObjectStore} from './evidence-upload.ts';

const KEY=/^private\/evidence\/([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/i;
const MAGIC=Buffer.from('MALAK-EV1\0','ascii');
const MAX_ENCRYPTED=12*1024*1024+4096;

/** Server-only ciphertext access; do NOT expose this in public endpoints. */
export interface PrivateCiphertextReader {
  readPrivate(objectKey:string):Promise<Uint8Array>;
}

async function secureDirectory(path:string):Promise<void> {
  const st=await lstat(path);
  if(!st.isDirectory() || st.isSymbolicLink() || (st.mode & 0o077)!==0)
    throw new Error('PRIVATE_STORE_PERMISSIONS_REQUIRED');
  if(await realpath(path)!==resolve(path))
    throw new Error('PRIVATE_STORE_SYMLINK_PATH_DENIED');
}

/**
 * An EXISTING absolute 0700 root is required; callers must provision it on a
 * private mount outside the web root, with a non-privileged dedicated user.
 */
export async function openPrivateEvidenceStore(root:string):Promise<PrivateObjectStore & PrivateCiphertextReader> {
  if(typeof root!=='string'||!isAbsolute(root)||root.includes('\0'))
    throw new Error('PRIVATE_STORE_ABSOLUTE_ROOT_REQUIRED');
  const canonical=resolve(root);
  await secureDirectory(canonical);
  const one=join(canonical,'private');
  const dir=join(one,'evidence');
  for(const p of [one,dir]) {
    try {await mkdir(p,{mode:0o700});}
    catch(err) {if((err as NodeJS.ErrnoException).code!=='EEXIST')throw err;}
    await secureDirectory(p);
  }
  const pathFor=(key:string):string=>{
    const m=KEY.exec(key);
    if(!m)throw new Error('INVALID_PRIVATE_OBJECT_KEY');
    return join(dir,`${m[1]!.toLowerCase()}.sealed`);
  };
  return {
    async readPrivate(key) {
      const path=pathFor(key);
      await secureDirectory(dir);
      // O_NONBLOCK protects against attacker-created FIFOs on supported Linux.
      const handle=await open(path,constants.O_RDONLY|constants.O_NOFOLLOW|constants.O_NONBLOCK);
      try {
        const st=await handle.stat();
        if(!st.isFile() || (st.mode & 0o077)!==0 ||
           st.size<MAGIC.length+12+16+1 || st.size>MAX_ENCRYPTED)
          throw new Error('PRIVATE_CIPHERTEXT_FILE_INVALID');
        const bytes=await handle.readFile();
        if(bytes.length!==st.size || !bytes.subarray(0,MAGIC.length).equals(MAGIC)) {
          bytes.fill(0);
          throw new Error('PRIVATE_CIPHERTEXT_FILE_INVALID');
        }
        return bytes;
      } finally {await handle.close();}
    },
    async putPrivate(key,bytes) {
      const path=pathFor(key);
      if(!(bytes instanceof Uint8Array) || bytes.byteLength<MAGIC.byteLength+12+16 ||
        bytes.byteLength>MAX_ENCRYPTED ||
        !Buffer.from(bytes.subarray(0,MAGIC.length)).equals(MAGIC))
        throw new Error('ONLY_SEALED_EVIDENCE_ALLOWED');
      await secureDirectory(dir); // fail closed on a replaced directory
      const flags=constants.O_CREAT|constants.O_EXCL|constants.O_WRONLY|constants.O_NOFOLLOW;
      const handle=await open(path,flags,0o600);
      try {
        await handle.writeFile(bytes);
        await handle.sync();
      } catch (err) {
        await handle.close();
        try {await unlink(path);}catch{/* private orphan must be swept by privileged owner */}
        throw err;
      }
      await handle.close();
    },
    async deletePrivate(key) {
      const path=pathFor(key);
      await secureDirectory(dir);
      // Refuse replacing an object with a directory or symlink.
      let st;
      try {st=await lstat(path);}catch(err) {
        if((err as NodeJS.ErrnoException).code==='ENOENT')return;
        throw err;
      }
      if(!st.isFile()||st.isSymbolicLink())throw new Error('PRIVATE_OBJECT_NOT_REGULAR');
      await unlink(path);
    }
  };
}
