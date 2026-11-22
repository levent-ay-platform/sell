import {createHash} from 'node:crypto';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {createRequire} from 'node:module';
import {join} from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import {composeUnboundMalakFirstLoop} from '../src/first-loop-gateway.ts';
import {importReviewedAtlasParcel,readAtlasContributionLayer} from '../src/atlas-parcel-exchange.ts';
import type {SqlClient,SqlPool} from '../src/postgres-evidence-repository.ts';

const runner = process.env.RUNNER_TEMP;
if (!runner || process.env.MALAK_DISPOSABLE_PG!=='yes') throw new Error('DISPOSABLE_GITHUB_POSTGRES_ONLY');
const {Pool}=createRequire(join(runner,'malak-pg','driver.cjs'))('pg');
const pool:SqlPool & {end():Promise<void>}=new Pool({
  host:'127.0.0.1',port:5432,user:'sell_ci',database:'sell_ci',
  password:process.env.PGPASSWORD,max:1,connectionTimeoutMillis:5000,
});
const newUser=async (db:SqlClient)=>(await db.query<{id:string}>('INSERT INTO sell.users DEFAULT VALUES RETURNING id')).rows[0].id;
const pdf=Buffer.from('%PDF-1.4\n1 0 obj\n<< >>\nendobj\n%%EOF');
const sha=createHash('sha256').update(pdf).digest('hex');

test('assigned review and consent gate local scans, geometry, redacted files and withdrawal',async()=>{
  const db=await pool.connect();
  const identities=new Map<string,string>();
  const ownerToken='test-owner-session-aaaaaaaaaaaaaa';
  const reviewerToken='test-reviewer-session-aaaaaaaaaaa';
  const outsiderToken='test-outsider-session-aaaaaaaaaaa';
  const atlasToken='R'.repeat(43);
  const pilotToken='P'.repeat(43);
  const storageObjectId='6c75dffd-cc60-454b-8544-862c77c56c4b';
  let reads=0;
  const unavailable=async ():Promise<never>=>{throw Error('NO_PRIVATE_EVIDENCE_IO');};
  const handler=composeUnboundMalakFirstLoop({
    provider:{
      async verifyBearer(bearer) {
        const userId=identities.get(bearer);
        const now=Date.now();
        return userId ? {userId,verifiedPhoneE164:'+21620000000',assurance:'VERIFIED_PHONE_OTP',
          verifiedAtUnixMs:now-1000,expiresAtUnixMs:now+60_000} : null;
      },
    },
    pool:{
      query:(sql,values)=>db.query(sql,values),
      // The fixture shares one disposable session. Preserve repository
      // transaction semantics using a savepoint inside the test transaction.
      connect:async()=>({
        query:async (sql,values)=>{
          switch(sql) {
            case 'BEGIN': return db.query('SAVEPOINT parcel_http_mutation');
            case 'COMMIT': return db.query('RELEASE SAVEPOINT parcel_http_mutation');
            case 'ROLLBACK':
              await db.query('ROLLBACK TO SAVEPOINT parcel_http_mutation');
              return db.query('RELEASE SAVEPOINT parcel_http_mutation');
            default: return db.query(sql,values);
          }
        },
        release(){},
      }),
    },
    objects:{putPrivate:unavailable,deletePrivate:unavailable,readPrivate:unavailable},
    scanner:{scan:unavailable},keys:{generateDataKey:unavailable,unwrapDataKey:unavailable},
    worker:{extract:unavailable},rateLimitHmacSecret:Buffer.alloc(40,3),policyVersion:'synthetic-parcel-v2',
    discoveryV2:{enabled:true,atlasReaderToken:atlasToken,pilotParcelToken:pilotToken,allowRequest:async()=>true,readParcelRendition:async(id)=>{
      reads++;
      assert.equal(id,storageObjectId);
      return pdf;
    }},
  });
  const server=createServer(handler).listen(0,'127.0.0.1');
  try {
    await once(server,'listening');
    await db.query('BEGIN');
    const address=server.address();
    assert.ok(address && typeof address!=='string');
    const call=(path:string,bearer?:string,body?:object)=>fetch(`http://127.0.0.1:${address.port}${path}`,{
      method:body?'POST':'GET',
      headers:{...(bearer?{authorization:'Bearer '+bearer}:{}),...(body?{'content-type':'application/json'}:{})},
      body:body?JSON.stringify(body):undefined,
    });
    const owner=await newUser(db),reviewer=await newUser(db),outsider=await newUser(db);
    identities.set(ownerToken,owner);
    identities.set(reviewerToken,reviewer);
    identities.set(outsiderToken,outsider);
    const {rows:[fixture]}=await db.query<{case_id:string;listing_id:string;plan_id:string;cin_id:string;title_id:string}>(`
      WITH seller AS (INSERT INTO sell.parties(kind) VALUES ('PERSON') RETURNING id),
      member AS (INSERT INTO sell.party_memberships SELECT id,$1::uuid,'OWNER' FROM seller RETURNING party_id),
      property AS (INSERT INTO sell.properties(kind) VALUES ('LAND') RETURNING id),
      revision AS (INSERT INTO sell.property_revisions(property_id,revision_number,snapshot)
       SELECT id,1,'{}' FROM property RETURNING id,property_id),
      claim AS (INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity)
       SELECT property.id,member.party_id,'SOLE_OWNER' FROM property CROSS JOIN member RETURNING id),
      ver AS (INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,assigned_reviewer_user_id,expires_at)
       SELECT revision.id,claim.id,$2::uuid,now()+interval '1 day' FROM revision CROSS JOIN claim RETURNING id),
      listing AS (INSERT INTO sell.listings(seller_party_id,title,price_tnd)
       SELECT id,'Synthetic reviewed parcel',45000 FROM seller RETURNING id),
      item AS (INSERT INTO sell.listing_items SELECT listing.id,revision.property_id,revision.id,ver.id
       FROM listing CROSS JOIN revision CROSS JOIN ver RETURNING listing_id),
      plan AS (INSERT INTO sell.evidence_files(storage_key,sha256,media_kind)
       VALUES ('private/synthetic-v2-plan',repeat('a',64),'application/pdf') RETURNING id),
      cin AS (INSERT INTO sell.evidence_files(storage_key,sha256,media_kind)
       VALUES ('private/synthetic-v2-identity',repeat('b',64),'image/jpeg') RETURNING id),
      title AS (INSERT INTO sell.evidence_files(storage_key,sha256,media_kind)
       VALUES ('private/synthetic-v2-title',repeat('c',64),'application/pdf') RETURNING id),
      ev AS (INSERT INTO sell.case_evidence SELECT ver.id,plan.id FROM ver CROSS JOIN plan
       UNION ALL SELECT ver.id,cin.id FROM ver CROSS JOIN cin
       UNION ALL SELECT ver.id,title.id FROM ver CROSS JOIN title)
      SELECT ver.id case_id,item.listing_id,plan.id plan_id,cin.id cin_id,title.id title_id
       FROM ver CROSS JOIN item CROSS JOIN plan CROSS JOIN cin CROSS JOIN title`,[owner,reviewer]);
    await db.query(`INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
      VALUES($1,$2,'GRANTED','synthetic-1')`,[fixture.case_id,owner]);
    await db.query(`
      INSERT INTO sell.evidence_upload_receipts(case_id,uploader_user_id,evidence_id,idempotency_key,sha256,wrapped_data_key,
       key_version,media_kind,file_size,evidence_kind,page_number)
      VALUES ($1,$2,$3,'synthetic_http_plan_test',repeat('a',64),repeat('a',40),'v1','application/pdf',100,'CADASTRAL_PLAN',NULL),
       ($1,$2,$4,'synthetic_http_cin_test',repeat('b',64),repeat('b',40),'v1','image/jpeg',100,'IDENTITY',1),
       ($1,$2,$5,'synthetic_http_title_test',repeat('c',64),repeat('c',40),'v1','application/pdf',100,'TITLE_EXTRACT',NULL)`,
      [fixture.case_id,owner,fixture.plan_id,fixture.cin_id,fixture.title_id]);
    const {rows:[doc]}=await db.query<{id:string}>(`
      INSERT INTO sell.parcel_document_versions(property_revision_id,case_id,source_evidence_id,original_sha256,rendition_sha256,
       rendition_mime,document_kind,storage_object_id)
      SELECT property_revision_id,id,$2,repeat('a',64),$3,'application/pdf','CADASTRAL_PLAN',$4
       FROM sell.verification_cases WHERE id=$1 RETURNING id`,[fixture.case_id,fixture.plan_id,sha,storageObjectId]);

    const ownerPath='/api/v1/owner/cases/'+fixture.case_id;
    const reviewPath='/api/v1/reviewer/cases/'+fixture.case_id;
    const geometryPath=ownerPath+'/parcel-geometries';
    const publicPath='/api/v1/listings/'+fixture.listing_id;
    const pdfPath=publicPath+'/documents/'+doc.id;
    const localPlan={
      method:'DOCUMENT_ONLY',sourceEvidenceId:fixture.plan_id,localPage:1,localUnit:'PAGE_POINT',
      titleReference:'000991',statedAreaM2:'1672.5',plotReference:'0042',vertices:[[20,10],[80,10],[80,50],[20,50],[20,10]],
      edges:[{ordinal:1,label:'A–B',lengthM:'32.50',sourcePage:1}],
    };
    assert.equal((await call(geometryPath)).status,401);
    assert.equal((await call(geometryPath,outsiderToken)).status,404);
    assert.equal((await call(publicPath+'/parcel')).status,404);
    assert.equal((await call(pdfPath)).status,404);
    assert.equal((await call(geometryPath,ownerToken,{...localPlan,sourceEvidenceId:fixture.cin_id})).status,400);
    assert.equal((await call(geometryPath,ownerToken,{...localPlan,vertices:[[0,0],[10,10],[0,10],[10,0]]})).status,400);
    const save=await call(geometryPath,ownerToken,localPlan);
    assert.equal(save.status,201);
    const localId=(await save.json()).id;
    const ownerDetails=await (await call(geometryPath,ownerToken)).json();
    assert.equal(ownerDetails.geometries[0].geometry,null);
    assert.equal(ownerDetails.geometries[0].localOutline.type,'Polygon');
    assert.equal(ownerDetails.geometries[0].sourceEvidenceId,fixture.plan_id);
    assert.equal(ownerDetails.geometries[0].titleReference,'000991');
    assert.equal(ownerDetails.geometries[0].statedAreaM2,'1672.500000');
    assert.equal(ownerDetails.geometries[0].localPage,1);
    assert.deepEqual(ownerDetails.geometries[0].edges,[{ordinal:1,label:'A–B',lengthM:'32.500000'}]);
    assert.deepEqual(ownerDetails.evidence.map((row:{kind:string})=>row.kind).sort(),['CADASTRAL_PLAN','TITLE_EXTRACT']);
    assert.equal((await call(reviewPath+'/parcel-geometries',ownerToken)).status,404);
    assert.equal((await call(reviewPath+'/parcel-geometries',reviewerToken)).status,200);
    assert.equal((await call(geometryPath,ownerToken,{method:'ATLAS_OFFICIAL_BOUNDARY',vertices:[]})).status,400);
    const geo=await call(geometryPath,ownerToken,{method:'OWNER_TRACED_ESTIMATE',
      vertices:[[10,36],[10.0002,36],[10.0002,36.0002],[10,36.0002]]});
    assert.equal(geo.status,201);
    const geoId=(await geo.json()).id;
    const reviewGeo=(id:string,token:string)=>call(reviewPath+'/parcel-geometries/'+id+'/review',token,{decision:'APPROVED'});
    const consentGeo=(id:string)=>call(geometryPath+'/'+id+'/consent',ownerToken,{decision:'GRANTED'});
    assert.equal((await reviewGeo(geoId,outsiderToken)).status,404);
    for (const id of [geoId,localId]) {
      assert.equal((await reviewGeo(id,reviewerToken)).status,201);
      assert.equal((await consentGeo(id)).status,201);
    }
    const reviewDoc=reviewPath+'/parcel-documents/'+doc.id+'/review';
    const consentDoc=ownerPath+'/parcel-documents/'+doc.id+'/consent';
    assert.equal((await call(reviewDoc,reviewerToken,{decision:'APPROVED',redactionChecked:false,rightsCleared:true})).status,400);
    assert.equal((await call(reviewDoc,reviewerToken,{decision:'APPROVED',redactionChecked:true,rightsCleared:true})).status,201);
    await db.query(`
      INSERT INTO sell.verification_decisions(case_id,reviewer_user_id,result,snapshot_sha256,reason_code)
      SELECT c.id,c.assigned_reviewer_user_id,'APPROVED',r.snapshot_sha256,'SYNTHETIC_OK'
      FROM sell.verification_cases c JOIN sell.property_revisions r ON r.id=c.property_revision_id WHERE c.id=$1`,[fixture.case_id]);
    await db.query("UPDATE sell.verification_cases SET status='VERIFIED' WHERE id=$1",[fixture.case_id]);
    await db.query("UPDATE sell.listings SET status='PUBLISHED' WHERE id=$1",[fixture.listing_id]);
    let listing=await (await call(publicPath+'/parcel')).json();
    assert.deepEqual(listing.documents,[]);
    assert.equal(listing.boundaries.length,1);
    assert.equal(listing.boundaries[0].id,geoId);
    assert.equal(listing.boundaries[0].method,'OWNER_TRACED_ESTIMATE');
    assert.equal(listing.verifiedParcel,false);
    assert.equal(listing.boundaries[0].geometry.type,'MultiPolygon');
    assert.equal(listing.boundaries[0].edges.length,4);
    assert.ok(Number(listing.boundaries[0].edges[0].lengthM)>15);
    assert.equal((await call(pdfPath)).status,404);
    assert.equal(reads,0);
    assert.equal((await call(consentDoc,ownerToken,{decision:'GRANTED'})).status,201);
    listing=await (await call(publicPath+'/parcel')).json();
    assert.deepEqual(listing.documents,[{id:doc.id,kind:'CADASTRAL_PLAN',mime:'application/pdf',url:pdfPath}]);
    assert.doesNotMatch(JSON.stringify(listing),new RegExp([storageObjectId,fixture.plan_id,fixture.cin_id,'synthetic-v2-plan'].join('|')));
    const file=await call(pdfPath);
    assert.equal(file.status,200);
    assert.deepEqual(Buffer.from(await file.arrayBuffer()),pdf);
    assert.equal(reads,1);
    assert.equal((await call(consentDoc,ownerToken,{decision:'REVOKED'})).status,201);
    assert.equal((await call(pdfPath)).status,404);
    assert.equal(reads,1);
    const atlasInput={
      namespace:'QA_ATLAS',sourceId:'qa-cadastre',layerId:'official',externalId:'lot-01',
      version:'qa-1',sha256:'e'.repeat(64),registryScope:'qa-registry',titleReference:'000991',
      plotReference:'QA01',localityKey:'qa-locality',classification:'OFFICIAL_CADASTRAL' as const,
      allowBoundaryRedistribution:true, geometry4326:{type:'Polygon' as const,
        coordinates:[[[10,36],[10.0002,36],[10.0002,36.0002],[10,36.0002],[10,36]]]},
    };
    const atlasId=await importReviewedAtlasParcel(db,atlasInput);
    assert.equal(await importReviewedAtlasParcel(db,atlasInput),atlasId);
    const match=await db.query<{atlas_record_id:string;spatial_status:string}>(`
      SELECT * FROM sell.match_atlas_parcel_candidates($1,$2,$3,$4,ST_SetSRID(ST_MakePoint(10.0001,36.0001),4326))
    `,[atlasInput.registryScope,atlasInput.titleReference,atlasInput.plotReference,atlasInput.localityKey]);
    assert.deepEqual(match.rows,[{atlas_record_id:atlasId,spatial_status:'SPATIALLY_COMPATIBLE'}]);
    const {rows:[official]}=await db.query<{id:string}>(`
      INSERT INTO sell.parcel_geometry_versions
       (parcel_id,property_id,property_revision_id,case_id,method,atlas_record_id,
        registry_scope,title_reference,plot_reference,locality_key,match_anchor)
      SELECT $1,i.property_id,i.property_revision_id,i.verification_case_id,'ATLAS_OFFICIAL_BOUNDARY',$2,
       $3,$4,$5,$6,ST_SetSRID(ST_MakePoint(10.0001,36.0001),4326)
      FROM sell.listing_items i WHERE i.listing_id=$7 RETURNING id`,
      [ownerDetails.parcels[0].id,atlasId,atlasInput.registryScope,atlasInput.titleReference,
        atlasInput.plotReference,atlasInput.localityKey,fixture.listing_id]);
    assert.equal((await reviewGeo(official.id,reviewerToken)).status,201);
    assert.equal((await (await call(publicPath+'/parcel')).json()).verifiedParcel,false);
    assert.equal((await consentGeo(official.id)).status,201);
    assert.equal((await (await call(publicPath+'/parcel')).json()).verifiedParcel,true);
    const bindPath='/api/v1/atlas/pilot-parcel';
    const matchPilot={listingId:fixture.listing_id, identityHashes:['b'.repeat(64)],
      documentHashes:['c'.repeat(64)], latitude:36.0001, longitude:10.0001,
      titleReference:'000991',plotReference:'QA01'};
    assert.equal((await call(bindPath,atlasToken,matchPilot)).status,401);
    assert.equal((await call(bindPath,pilotToken,{...matchPilot,identityHashes:['a'.repeat(64)]})).status,404);
    assert.equal((await call(bindPath,pilotToken,{...matchPilot,longitude:9})).status,404);
    assert.equal((await call(bindPath,pilotToken,{...matchPilot,plotReference:'QA02'})).status,404);
    const linked=await call(bindPath,pilotToken,matchPilot);
    assert.equal(linked.status,200);
    assert.equal((await linked.json()).verifiedParcel,true);

    assert.equal((await call(geometryPath+'/'+official.id+'/consent',ownerToken,{decision:'REVOKED'})).status,201);
    assert.equal((await call(bindPath,pilotToken,matchPilot)).status,404);
    assert.equal((await (await call(publicPath+'/parcel')).json()).verifiedParcel,false);

    await db.query("SELECT sell.enqueue_atlas_parcel_event($1,$2,'UPSERT')", [fixture.listing_id,geoId]);
    const contributions=await readAtlasContributionLayer(db);
    assert.equal(contributions.layerId,'MALAK_OWNER_SUBMITTED');
    assert.deepEqual(contributions.features.map(f=>f.properties),[
      {listingId:fixture.listing_id,boundaryId:geoId,method:'OWNER_TRACED_ESTIMATE'},
    ]);
    const layerPath='/api/v1/atlas/contributions';
    assert.equal((await call(layerPath,reviewerToken)).status,401);
    assert.deepEqual(await (await call(layerPath,atlasToken)).json(),contributions);
    assert.equal((await call(layerPath+'?anything=1',atlasToken)).status,400);
    await db.query("UPDATE sell.listings SET status='PAUSED' WHERE id=$1",[fixture.listing_id]);
    assert.equal((await call(publicPath+'/parcel')).status,404);
    assert.deepEqual((await readAtlasContributionLayer(db)).features,[]);
    assert.deepEqual((await (await call(layerPath,atlasToken)).json()).features,[]);
  } finally {
    server.close();
    try {await db.query('ROLLBACK');} finally {db.release();await pool.end();}
  }
});
