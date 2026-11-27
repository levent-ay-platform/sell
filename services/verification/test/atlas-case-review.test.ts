import {createServer} from 'node:http';
import {once} from 'node:events';
import test from 'node:test';
import assert from 'node:assert/strict';
import {createParcelHttpHandler} from '../src/parcel-http.ts';
import {createParcelRepository} from '../src/parcel-repository.ts';
const caseId='10000000-0000-4000-8000-000000000001';
const geometryId='10000000-0000-4000-8000-000000000002';
const reviewer='10000000-0000-4000-8000-000000000003';
const outsider='10000000-0000-4000-8000-000000000004';
test('case-bound reviewer Atlas endpoint returns only count allowlist and rejects arbitrary reference queries',async()=>{
  let calls=0;
  const repo=createParcelRepository({
    connect:async()=>{throw Error('NO_MUTATION')},
    query:async(sql,values)=>{
      calls++;
      assert.match(sql,/assigned_reviewer_user_id/);
      assert.match(sql,/evidence_consent_events/);
      assert.match(sql,/expires_at>now/);
      assert.match(sql,/g\.method='DOCUMENT_ONLY'/);
      assert.match(sql,/LIMIT 51/);
      assert.equal(values?.[0],caseId);
      assert.equal(values?.[2],geometryId);
      assert.equal(values?.[3],'N');
      assert.equal(values?.[4],'K');
      return {rows:values?.[1]===reviewer?[{candidateCount:1,officialSourceCount:0,withGeometryCount:1,
        sourceSecret:'PRIVATE_SOURCE'}]:[]};
    }
  });
  const handler=createParcelHttpHandler({repository:repo,allowRequest:async()=>true,
    authenticate:async token=>token.startsWith('reviewer')?{userId:reviewer}:token.startsWith('outsider')?{userId:outsider}:
      token.startsWith('pilot')?{userId:reviewer,pilotUnverified:true}:null});
  const server=createServer((req,res)=>void handler(req,res).then(ok=>{if(!ok)res.writeHead(404).end()})).listen(0,'127.0.0.1');
  try{
    await once(server,'listening');
    const a=server.address();assert.ok(a&&typeof a!=='string');
    const url='http://127.0.0.1:'+a.port+'/api/v1/reviewer/cases/'+caseId+'/atlas-candidates';
    const body={geometryId,registryScope:'N',localityKey:'K'};
    const call=(token?,b=body)=>fetch(url,{method:'POST',headers:{'content-type':'application/json',
      ...(token?{authorization:'Bearer '+token.repeat(10)}:{})},body:JSON.stringify(b)});
    assert.equal((await call()).status,401);
    assert.equal((await call('pilot')).status,401);
    assert.equal(calls,0);
    assert.equal((await call('reviewer',{...body,titleReference:'unbound'})).status,400);
    assert.equal(calls,0);
    assert.equal((await call('outsider')).status,404);
    const r=await call('reviewer');
    assert.equal(r.status,200);assert.equal(r.headers.get('cache-control'),'no-store');
    assert.deepEqual(await r.json(),{candidateCount:1,officialSourceCount:0,withGeometryCount:1,truncated:false,
      ownershipVerified:false,mayPublish:false});
    assert.equal(calls,2);
    assert.equal((await fetch(url)).status,405);
  }finally{server.close()}
});
