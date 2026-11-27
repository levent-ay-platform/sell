import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {authenticatorFromVerifiedOwnerProvider,composeUnboundMalakFirstLoop} from
  '../src/first-loop-gateway.ts';

const instant=Date.UTC(2026,10,11,16,0,0);
const now=()=>instant;
const token='synthetic-approved-owner-bearer-token-for-unit-tests';
const claim=()=>({
  userId:randomUUID(),
  verifiedPhoneE164:'+21629500000',
  assurance:'VERIFIED_PHONE_OTP' as const,
  verifiedAtUnixMs:instant-60000,expiresAtUnixMs:instant+60000
});
test('a genuinely asserted short-lived phone session maps to only its opaque user ID',async()=>{
  const example=claim();
  let seenToken:string|null=null;
  const auth=authenticatorFromVerifiedOwnerProvider({
    verifyBearer:async t=>{seenToken=t;return example;}
  },now);
  assert.deepEqual(await auth.authenticate(token),{userId:example.userId});
  assert.equal(seenToken,token);
  assert.equal((await auth.authenticate(token))?.pilotUnverified,undefined);
  assert.equal(Object.keys((await auth.authenticate(token))!).join(','),'userId');
});
test('fail closed on missing, bad, expired or too-long provider evidence',async()=>{
  const valid=claim();
  const bad:unknown[]=[
    null, {...valid,userId:'not-a-uuid'},
    {...valid,verifiedPhoneE164:'+21600000000'},
    {...valid,verifiedPhoneE164:'+3355000000'},
    {...valid,assurance:'PILOT_UNVERIFIED'},
    {...valid,assurance:'UNVERIFIED_SHARED_CODE_1234'},
    {...valid,verifiedAtUnixMs:instant+1},
    {...valid,expiresAtUnixMs:instant},
    {...valid,expiresAtUnixMs:instant+13*3600000},
    {...valid,verifiedAtUnixMs:instant-13*3600000,expiresAtUnixMs:instant+1},
    {...valid,expiresAtUnixMs:NaN},
    {...valid,verifiedAtUnixMs:'yesterday'},
    {...valid,verifiedPhoneE164:'٢١٦٢٩٥٠٠٠٠٠'}
  ];
  for(const item of bad){
    const auth=authenticatorFromVerifiedOwnerProvider({
      verifyBearer:async()=>item as ReturnType<typeof claim>
    },now);
    assert.equal(await auth.authenticate(token),null,JSON.stringify(item));
  }
  assert.equal(await authenticatorFromVerifiedOwnerProvider({
    verifyBearer:async()=>valid
  },now).authenticate('short'),null);
  assert.equal(await authenticatorFromVerifiedOwnerProvider({
    verifyBearer:async()=>{throw Error('UPSTREAM_UNAVAILABLE');}
  },now).authenticate(token),null);
});
test('gateway refuses missing verified provider and has no network binding',()=>{
  const deps:any={
    provider:null,
    pool:{connect:async()=>{throw Error('NO_PRODUCTION_DB');},
      query:async()=>{throw Error('NO_PRODUCTION_DB');}},
    objects:{putPrivate:async()=>{},readPrivate:async()=>new Uint8Array(),
      deletePrivate:async()=>{}},
    scanner:{scan:async()=>{throw Error('NO_CLAMAV');}},
    keys:{generateDataKey:async()=>{throw Error('NO_KMS');},
      unwrapDataKey:async()=>{throw Error('NO_KMS');}},
    worker:{extract:async()=>{throw Error('NO_OCR');}},
    rateLimitHmacSecret:Buffer.alloc(40,3),
    policyVersion:'malak-consent-v1',
    now
  };
  assert.throws(()=>composeUnboundMalakFirstLoop(deps),/VERIFIED_OWNER_PROVIDER_REQUIRED/);
  deps.provider={verifyBearer:async()=>null};
  const handler=composeUnboundMalakFirstLoop(deps);
  assert.equal(typeof handler,'function');
  assert.equal('listen' in handler,false);
  assert.equal('address' in handler,false);
});
