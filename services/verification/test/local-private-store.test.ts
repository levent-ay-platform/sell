import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,lstat,symlink,mkdir,chmod,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {openPrivateEvidenceStore} from '../src/local-private-store.ts';
const mkroot=async()=>mkdtemp(join(tmpdir(),'malak-private-'));
const key=()=>`private/evidence/${randomUUID()}`;
const sealed=()=>Buffer.concat([Buffer.from('MALAK-EV1\0'),Buffer.alloc(12+16+99,7)]);

test('ciphertext-only file storage is 0600, within 0700 private mount, and deletes safely',async()=>{
 const root=await mkroot();
 try {
  const store=await openPrivateEvidenceStore(root);
  const k=key(),uuid=k.split('/').at(-1)!,dest=join(root,'private/evidence',uuid+'.sealed');
  const data=sealed();await store.putPrivate(k,data);
  assert.deepEqual(await readFile(dest),data);
  assert.equal((await lstat(dest)).mode&0o777,0o600);
  assert.equal((await lstat(join(root,'private'))).mode&0o777,0o700);
  await store.deletePrivate(k);
  await assert.rejects(()=>lstat(dest),{code:'ENOENT'});
 }finally{await rm(root,{recursive:true,force:true});}
});
test('no plaintext, traversal, cross-case paths, nor overwrite are permitted',async()=>{
 const root=await mkroot();
 try{
  const store=await openPrivateEvidenceStore(root);const k=key();const d=sealed();
  for(const bad of ['../../etc/passwd','private/evidence/../../foo','other/evidence/'+randomUUID(),'private/evidence/demo',k+'.txt'])
    await assert.rejects(()=>store.putPrivate(bad,d),/INVALID_PRIVATE_OBJECT_KEY/);
  await assert.rejects(()=>store.putPrivate(key(),Buffer.from('raw owner identity')),/ONLY_SEALED/);
  await store.putPrivate(k,d);
  await assert.rejects(()=>store.putPrivate(k,d),{code:'EEXIST'});
  const dest=join(root,'private/evidence',k.split('/').at(-1)!+'.sealed');
  assert.deepEqual(await readFile(dest),d);
 }finally{await rm(root,{recursive:true,force:true});}
});
test('rejects symlink target on both write and delete',async()=>{
 const root=await mkroot();
 try{
  const store=await openPrivateEvidenceStore(root);
  const k=key();const dest=join(root,'private/evidence',k.split('/').at(-1)!+'.sealed');
  await symlink(join(root,'outside'),dest);
  await assert.rejects(()=>store.putPrivate(k,sealed()),{code:'EEXIST'});
  await assert.rejects(()=>store.deletePrivate(k),/PRIVATE_OBJECT_NOT_REGULAR/);
 }finally{await rm(root,{recursive:true,force:true});}
});
test('rejects unsafe root and symlink root before any read or write',async()=>{
 const root=await mkroot();
 try{
  await assert.rejects(()=>openPrivateEvidenceStore('relative/path'),/ABSOLUTE_ROOT/);
  await chmod(root,0o755);
  await assert.rejects(()=>openPrivateEvidenceStore(root),/PERMISSIONS_REQUIRED/);
  await chmod(root,0o700);
  const link=root+'-alias';
  await symlink(root,link);
  try{await assert.rejects(()=>openPrivateEvidenceStore(link),/PERMISSIONS_REQUIRED|SYMLINK/);}
  finally{await rm(link,{force:true});}
 }finally{await rm(root,{recursive:true,force:true});}
});
test('reopening a private mount refuses tampered public evidence directory',async()=>{
 const root=await mkroot();
 try{
  await openPrivateEvidenceStore(root);
  await chmod(join(root,'private/evidence'),0o755);
  await assert.rejects(()=>openPrivateEvidenceStore(root),/PERMISSIONS_REQUIRED/);
 }finally{await rm(root,{recursive:true,force:true});}
});
test('two concurrent writes of same key cannot silently replace ciphertext',async()=>{
 const root=await mkroot();
 try{
  const s=await openPrivateEvidenceStore(root);const k=key();
  const results=await Promise.allSettled([s.putPrivate(k,sealed()),s.putPrivate(k,sealed())]);
  assert.equal(results.filter(x=>x.status==='fulfilled').length,1);
  assert.equal(results.filter(x=>x.status==='rejected').length,1);
 }finally{await rm(root,{recursive:true,force:true});}
});
