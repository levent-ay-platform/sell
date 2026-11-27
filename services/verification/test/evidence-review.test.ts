import test from 'node:test';
import assert from 'node:assert/strict';
import {createCipheriv, createHash, randomBytes,randomUUID} from 'node:crypto';
import {mkdtemp,rm,chmod,unlink,symlink} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {inspectReviewerEvidence} from '../src/evidence-review.ts';
import {openPrivateEvidenceStore} from '../src/local-private-store.ts';
import type {AuthorizedEvidence,PrivateEvidenceInspectionDependencies} from '../src/evidence-review.ts';

function fixture() {
  const caseId=randomUUID(),evidenceId=randomUUID(),reviewerId=randomUUID(),stranger=randomUUID();
  const key=Uint8Array.from(randomBytes(32));
  const file=Buffer.from('%PDF-1.7\nA COMPLETELY FICTIONAL OWNERSHIP CERTIFICATE\n%%EOF');
  const iv=randomBytes(12);
  // Correctly build payload separately so tag is followed by ciphertext.
  const c2=createCipheriv('aes-256-gcm',key,iv);
  c2.setAAD(Buffer.from(`malak-evidence-v1:${caseId}:${evidenceId}`));
  const body=Buffer.concat([c2.update(file),c2.final()]);
  const sealed=Buffer.concat([Buffer.from('MALAK-EV1\0'),iv,c2.getAuthTag(),body]);
  const row:AuthorizedEvidence={caseId,evidenceId,objectKey:`private/evidence/${randomUUID()}`,
    wrappedKey:'vault:v1:syntheticxxxxxxxxxxxxxxxxxxxxxxxxx=',keyVersion:'v1',
    fileSize:file.length,digest:createHash('sha256').update(file).digest('hex'),kind:'OWNERSHIP_CERTIFICATE'};
  return {caseId,evidenceId,reviewerId,stranger,key,file,sealed,row};
}
async function withHarness(run:(h:Awaited<ReturnType<typeof harness>>)=>Promise<void>) {
  const h=await harness();try{await run(h);}finally{await rm(h.root,{recursive:true,force:true});}
}
async function harness() {
  const f=fixture();const root=await mkdtemp(join(tmpdir(),'malak-private-review-'));
  const store=await openPrivateEvidenceStore(root);await store.putPrivate(f.row.objectKey,f.sealed);
  const observations={audits:0,opened:0,inspect:0,zeroized:false};
  let authorization=true,failAudit=false, corrupt=false,metadata=f.row;
  const deps:PrivateEvidenceInspectionDependencies={
    auth:{authenticate:async tok=>tok==='reviewer-session'?{userId:f.reviewerId}:null},
    ledger:{authorizeAndAudit:async(caseId,evidenceId,actor)=>{
      if(!authorization||caseId!==f.caseId||evidenceId!==f.evidenceId||actor!==f.reviewerId)return null;
      if(failAudit)throw Error('synthetic audit outage');
      observations.audits++;return metadata;
    }},
    objects:{readPrivate:async k=>{observations.opened++;const sealed=await store.readPrivate(k);if(corrupt)sealed[sealed.length-1]^=0xff;return sealed;}},
    keys:{unwrapDataKey:async(scope)=>{
      if(scope.caseId!==f.caseId||scope.evidenceId!==f.evidenceId)throw Error('scope invalid');
      return Uint8Array.from(f.key);
    }},
    inspect:async bytes=>{observations.inspect++;assert.deepEqual(Buffer.from(bytes),f.file);}
  };
  return {f,root,store,deps,observations,deny:()=>{authorization=false;},badAudit:()=>{failAudit=true;},tamper:()=>{corrupt=true;},changeMetadata:(r:AuthorizedEvidence)=>{metadata=r;}};
}
const req=(f:ReturnType<typeof fixture>)=>({bearerToken:'reviewer-session',caseId:f.caseId,evidenceId:f.evidenceId});

test('private assigned reviewer successfully inspects authenticated ciphertext without response bytes',async()=>{
  await withHarness(async h=>{
    const out=await inspectReviewerEvidence(req(h.f),h.deps);
    assert.deepEqual(out,{ok:true,inspected:true,mayPublish:false,ownershipVerified:false});
    assert.deepEqual(h.observations,{audits:1,opened:1,inspect:1,zeroized:false});
    assert.equal(JSON.stringify(out).includes('FICTIONAL'),false);
  });
});
test('invalid session never reads, unwraps or audits evidence',async()=>{
  await withHarness(async h=>{
    const out=await inspectReviewerEvidence({...req(h.f),bearerToken:'stolen'},h.deps);
    assert.equal(out.ok,false);assert.equal(h.observations.opened,0);
    assert.equal(h.observations.audits,0);
  });
});
test('unassigned reviewer and wrong case/evidence are denied before filesystem access',async()=>{
  await withHarness(async h=>{
    h.deny();
    for(const r of [req(h.f),{...req(h.f),caseId:h.f.stranger},{...req(h.f),evidenceId:h.f.stranger}]){
      assert.equal((await inspectReviewerEvidence(r,h.deps)).ok,false);
    }
    assert.equal(h.observations.opened,0);
  });
});
test('failure to commit mandatory access audit means no decryption',async()=>{
  await withHarness(async h=>{
    h.badAudit();
    const out=await inspectReviewerEvidence(req(h.f),h.deps);
    assert.equal(out.ok,false);assert.equal(h.observations.opened,0);
  });
});
test('GCM ciphertext mutation never reaches private inspector',async()=>{
  await withHarness(async h=>{
    h.tamper();const out=await inspectReviewerEvidence(req(h.f),h.deps);
    assert.equal(out.ok,false);assert.equal(h.observations.inspect,0);
  });
});
test('authenticated-but-wrong SHA256 or original file size fails closed',async()=>{
  await withHarness(async h=>{
    h.changeMetadata({...h.f.row,digest:'f'.repeat(64)});
    assert.equal((await inspectReviewerEvidence(req(h.f),h.deps)).ok,false);
    h.changeMetadata({...h.f.row,fileSize:h.f.file.length+10});
    assert.equal((await inspectReviewerEvidence(req(h.f),h.deps)).ok,false);
    assert.equal(h.observations.inspect,0);
  });
});
test('mismatched case/evidence row is refused even after ledger says yes',async()=>{
  await withHarness(async h=>{
    h.changeMetadata({...h.f.row,caseId:h.f.stranger});
    const out=await inspectReviewerEvidence(req(h.f),h.deps);
    assert.equal(out.ok,false);assert.equal(h.observations.opened,0);
  });
});
test('private reader rejects world-readable stored evidence',async()=>{
  await withHarness(async h=>{
    const dest=join(h.root,'private/evidence',h.f.row.objectKey.split('/').at(-1)!+'.sealed');
    await chmod(dest,0o644);
    assert.equal((await inspectReviewerEvidence(req(h.f),h.deps)).ok,false);
    assert.equal(h.observations.inspect,0);
  });
});
test('private inspector failure fails closed and no PII enters response',async()=>{
  await withHarness(async h=>{
    const deps={...h.deps,inspect:async()=>{throw Error('FICTIONAL CONTENT MUST NOT LEAK');}};
    const result=await inspectReviewerEvidence(req(h.f),deps);
    assert.equal(result.ok,false);assert.equal(JSON.stringify(result).includes('FICTIONAL'),false);
  });
});

test('private read never follows a symlink replacing a sealed document',async()=>{
  await withHarness(async h=>{
    const file=join(h.root,'private/evidence',h.f.row.objectKey.split('/').at(-1)!+'.sealed');
    await unlink(file);
    await symlink(join(h.root,'some-other-file'),file);
    assert.equal((await inspectReviewerEvidence(req(h.f),h.deps)).ok,false);
    assert.equal(h.observations.inspect,0);
  });
});
test('after inspection, the trusted callback buffer and returned data key are zeroized',async()=>{
  await withHarness(async h=>{
    let retainedPlaintext:Uint8Array|undefined;
    const transientKey=Uint8Array.from(h.f.key);
    const deps={...h.deps,keys:{unwrapDataKey:async()=>transientKey},
      inspect:async(bytes:Uint8Array)=>{retainedPlaintext=bytes;assert.deepEqual(Buffer.from(bytes),h.f.file);}};
    assert.equal((await inspectReviewerEvidence(req(h.f),deps)).ok,true);
    assert.ok(retainedPlaintext?.every(v=>v===0));
    assert.ok(transientKey.every(v=>v===0));
  });
});
