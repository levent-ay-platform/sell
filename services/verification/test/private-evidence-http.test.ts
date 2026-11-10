import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer,request} from 'node:http';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {createPrivateEvidenceHttpHandler} from '../src/private-evidence-http.ts';
import type {PrivateEvidenceHttpDeps,DenialAuditRecord} from '../src/private-evidence-http.ts';

const ID=randomUUID(), EVIDENCE=randomUUID();
const TOKEN='a'.repeat(35);
const pdf=Buffer.from('%PDF-1.4\nPURELY SYNTHETIC OWNER DOCUMENT\n%%EOF');
const url=`/internal/v1/evidence/${ID}/upload`;
const inspectUrl=`/internal/v1/review/${ID}/${EVIDENCE}/inspect`;
function setup(){
  const events:DenialAuditRecord[]=[];
  const stats={auth:0,upload:0,inspect:0,limit:0};
  let limited=false,down=false,unauthorized=false, auditDown=false;
  let exposed:Uint8Array|undefined;
  const deps:PrivateEvidenceHttpDeps={
    auth:{authenticate:async token=>{stats.auth++;return token===TOKEN&&!unauthorized?{userId:ID}:null;}},
    limits:{consume:async()=>{stats.limit++;if(down)throw Error('disposable limiter offline');return !limited;}},
    audit:{recordDenied:async item=>{if(auditDown)throw Error('audit offline');events.push(item);}},
    upload:async body=>{stats.upload++;exposed=body.bytes;
      if(body.caseId!==ID||body.pageNumber!==2||body.evidenceKind!=='OWNERSHIP_CERTIFICATE')return {ok:false,code:'FILE_REJECTED',mayPublish:false,ownershipVerified:false};
      return {ok:true,uploaded:true,evidenceId:EVIDENCE,mayPublish:false,ownershipVerified:false};},
    inspect:async body=>{stats.inspect++;if(body.caseId!==ID||body.evidenceId!==EVIDENCE)return {ok:false,code:'ACCESS_DENIED',mayPublish:false,ownershipVerified:false};
      return {ok:true,inspected:true,mayPublish:false,ownershipVerified:false};},
  };
  return {deps,events,stats,get exposed(){return exposed;},setLimited:(v:boolean)=>limited=v,
    setDown:(v:boolean)=>down=v,setUnauthorized:(v:boolean)=>unauthorized=v,setAuditDown:(v:boolean)=>auditDown=v};
}
async function withHttp(run:(port:number, h:ReturnType<typeof setup>)=>Promise<void>){
  const h=setup();const server=createServer(createPrivateEvidenceHttpHandler(h.deps));
  server.listen(0,'127.0.0.1');await once(server,'listening');
  const address=server.address();if(!address||typeof address==='string')throw Error('no port');
  try{await run(address.port,h);}finally{server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()));}
}
function send(port:number,options:{url?:string;method?:string;headers?:Record<string,string|string[]>;body?:Buffer}={}){
  const body=options.body;
  const headers:Record<string,string|string[]>={
    Authorization:`Bearer ${TOKEN}`,
    ...(body?{'Content-Type':'application/octet-stream','Content-Length':String(body.length),
      'X-Idempotency-Key':'synthetic__valid__01','X-Evidence-Kind':'OWNERSHIP_CERTIFICATE',
      'X-Page-Number':'2','X-Claimed-Mime':'application/pdf'}:{}),
    ...options.headers
  };
  return new Promise<{status:number;headers:Record<string,unknown>;body:any}>((resolve,reject)=>{
    const req=request({host:'127.0.0.1',port,method:options.method??'POST',path:options.url??url,headers},res=>{
      const chunks:Buffer[]=[];
      res.on('data',x=>chunks.push(x));res.on('end',()=>{
        const data=Buffer.concat(chunks).toString('utf8');
        try{resolve({status:res.statusCode??0,headers:res.headers,body:JSON.parse(data)});}catch(err){reject(err);}
      });
    });
    req.setTimeout(1500,()=>req.destroy(new Error('test socket timeout')));
    req.on('error',reject);
    if(body)req.write(body);
    req.end();
  });
}

test('bounded authenticated upload returns opaque evidence ID and zeroizes received buffer',async()=>{
  await withHttp(async(port,h)=>{
    const result=await send(port,{body:pdf});
    assert.equal(result.status,200);assert.deepEqual(result.body,{ok:true,evidenceId:EVIDENCE,uploaded:true,mayPublish:false,ownershipVerified:false});
    assert.equal(h.stats.upload,1);assert.equal(h.events.length,0);
    assert.equal(result.headers['cache-control'],'no-store');
    assert.equal(result.headers['x-content-type-options'],'nosniff');
    assert.ok(h.exposed?.every(b=>b===0));
    assert.ok(!JSON.stringify(result).includes('SYNTHETIC OWNER DOCUMENT'));
  });
});
test('review inspection is status-only; never returns OCR, document or storage path',async()=>{
  await withHttp(async(port,h)=>{
    const result=await send(port,{url:inspectUrl});
    assert.equal(result.status,200);
    assert.deepEqual(result.body,{ok:true,inspected:true,mayPublish:false,ownershipVerified:false});
    assert.equal(h.stats.inspect,1);assert.equal(h.stats.upload,0);
  });
});
test('invalid credentials are rejected and denial audit records no token or raw URI',async()=>{
  await withHttp(async(port,h)=>{
    const result=await send(port,{body:pdf,headers:{Authorization:'Bearer '+ 'x'.repeat(30)}});
    assert.equal(result.status,401);assert.equal(h.stats.upload,0);
    assert.equal(h.events.length,1);assert.equal(h.events[0].actorUserId,null);
    assert.ok(!JSON.stringify(h.events).includes('x'.repeat(30)));
    assert.ok(!JSON.stringify(h.events).includes(ID));
  });
});
test('non-POST and path traversal/query are denied before core operations',async()=>{
  await withHttp(async(port,h)=>{
    const a=await send(port,{method:'GET',url});assert.equal(a.status,404);
    const b=await send(port,{url:url+'?debug=1'});assert.equal(b.status,404);
    assert.equal(h.stats.upload,0);assert.equal(h.events.length,2);
    assert.equal(h.events[0].route,'UPLOAD');assert.equal(h.events[1].route,'UNKNOWN');
  });
});
test('cookie/origin and untrusted forwarding metadata never bypass bearer-only policy',async()=>{
  await withHttp(async(port,h)=>{
    for(const headers of [{Origin:'https://malicious.example'},{Cookie:'sid=abc'}, {'X-Forwarded-Proto':'https'}]){
      const out=await send(port,{body:pdf,headers});assert.equal(out.status,400);
    }
    assert.equal(h.stats.upload,0);assert.equal(h.events.length,3);
  });
});
test('large content-length is rejected before accepting body or calling scanner',async()=>{
  await withHttp(async(port,h)=>{
    const result=await send(port,{headers:{'Content-Type':'application/octet-stream','Content-Length':String(12*1024*1024+1),
      'X-Idempotency-Key':'synthetic__valid__01','X-Evidence-Kind':'OWNERSHIP_CERTIFICATE','X-Page-Number':'2','X-Claimed-Mime':'application/pdf'}});
    assert.equal(result.status,413);assert.equal(h.stats.upload,0);assert.equal(h.events.at(-1)?.reason,'FILE_REJECTED');
  });
});
test('ambiguous metadata or missing page cannot reach private evidence core',async()=>{
  await withHttp(async(port,h)=>{
    const a=await send(port,{body:pdf,headers:{'X-Page-Number':'0'}});assert.equal(a.status,400);
    const b=await send(port,{body:pdf,headers:{'Content-Type':'application/json'}});assert.equal(b.status,415);
    const c=await send(port,{body:pdf,headers:{Authorization:[`Bearer ${TOKEN}`,`Bearer ${TOKEN}`]}});
    assert.equal(c.status,400);
    assert.equal(h.stats.upload,0);
  });
});
test('injected atomic distributed limiter blocks repeated requests before core work',async()=>{
  await withHttp(async(port,h)=>{
    h.setLimited(true);const res=await send(port,{body:pdf});
    assert.equal(res.status,429);assert.equal(h.stats.auth,0);assert.equal(h.stats.upload,0);
    assert.equal(h.events.at(-1)?.reason,'RATE_LIMITED');
  });
});
test('limiter or denial-audit failures return unavailable and never call upload',async()=>{
  await withHttp(async(port,h)=>{
    h.setDown(true);let r=await send(port,{body:pdf});assert.equal(r.status,503);assert.equal(h.stats.upload,0);
    h.setDown(false);h.setAuditDown(true);
    r=await send(port,{body:pdf,headers:{Authorization:'not-a-token'}});
    assert.equal(r.status,503);assert.equal(h.stats.upload,0);
  });
});
test('core access denial is redacted in response but recorded in audit',async()=>{
  await withHttp(async(port,h)=>{
    const r=await send(port,{url:`/internal/v1/review/${ID}/${randomUUID()}/inspect`});
    assert.equal(r.status,403);assert.deepEqual(r.body,{ok:false,code:'REQUEST_DENIED'});
    assert.equal(h.events[0]?.actorUserId,ID);
    assert.equal(h.events[0]?.reason,'FORBIDDEN');
    assert.equal(h.stats.inspect,1);
  });
});
