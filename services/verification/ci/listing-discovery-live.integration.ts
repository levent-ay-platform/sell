/** Real public HTTP + PostGIS queries with synthetic fixtures in a disposable database. */
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {join} from 'node:path';
import {composeUnboundMalakFirstLoop} from '../src/first-loop-gateway.ts';
import type {SqlPool} from '../src/postgres-evidence-repository.ts';

const runner = process.env.RUNNER_TEMP;
if (!runner || process.env.MALAK_DISPOSABLE_PG !== 'yes') {
  throw new Error('DISPOSABLE_GITHUB_POSTGRES_ONLY');
}
const require = createRequire(join(runner, 'malak-pg', 'driver.cjs'));
const {Pool} = require('pg');
const database = new Pool({
  host: '127.0.0.1', port: 5432, user: 'sell_ci',
  password: process.env.PGPASSWORD, database: 'sell_ci',
  max: 1, connectionTimeoutMillis: 5000,
});

const fixtureFunction = `
  CREATE FUNCTION pg_temp.discovery_fixture(
    fixture_kind text, fixture_title text, fixture_lon float8, fixture_rooms smallint,
    fixture_place text, fixture_price numeric, fixture_time timestamptz,
    publish_listing boolean DEFAULT true
  ) RETURNS TABLE(listing_id uuid, case_id uuid, revision_id uuid, property_id uuid)
  LANGUAGE plpgsql AS $$
  DECLARE
    seller uuid; reviewer uuid; claim uuid; fixture_hash text;
  BEGIN
    INSERT INTO sell.parties(kind) VALUES ('PERSON') RETURNING id INTO seller;
    INSERT INTO sell.users DEFAULT VALUES RETURNING id INTO reviewer;
    INSERT INTO sell.properties(kind,public_point) VALUES (
      fixture_kind,CASE WHEN fixture_lon IS NOT NULL THEN ST_SetSRID(ST_MakePoint(fixture_lon,36.85),4326) END
    ) RETURNING id INTO property_id;
    INSERT INTO sell.property_revisions(property_id,revision_number,snapshot)
      VALUES (property_id,1,'{"privateEvidence":"DO_NOT_EXPOSE"}')
      RETURNING id,snapshot_sha256 INTO revision_id,fixture_hash;
    INSERT INTO sell.listing_discovery_details(property_revision_id,place_id,area_m2,rooms,description)
      VALUES (revision_id,fixture_place,123.456,fixture_rooms,'Synthetic public description');
    INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity)
      VALUES (property_id,seller,'SOLE_OWNER') RETURNING id INTO claim;
    INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,assigned_reviewer_user_id,expires_at)
      VALUES (revision_id,claim,reviewer,now()+interval '1 day') RETURNING id INTO case_id;
    INSERT INTO sell.listings(seller_party_id,title,price_tnd)
      VALUES (seller,fixture_title,fixture_price) RETURNING id INTO listing_id;
    INSERT INTO sell.listing_items(listing_id,property_id,property_revision_id,verification_case_id)
      VALUES (listing_id,property_id,revision_id,case_id);
    IF publish_listing THEN
      INSERT INTO sell.verification_decisions(case_id,reviewer_user_id,result,snapshot_sha256,reason_code)
        VALUES (case_id,reviewer,'APPROVED',fixture_hash,'SYNTHETIC_TEST');
      UPDATE sell.verification_cases SET status='VERIFIED' WHERE id=case_id;
      UPDATE sell.listings SET status='PUBLISHED',published_at=fixture_time WHERE id=listing_id;
    END IF;
    RETURN NEXT;
  END
  $$;`;

test('live discovery serves bilingual places, shared map/list filters, verified details and stable pages', async () => {
  const db = await database.connect();
  const handler = composeUnboundMalakFirstLoop({
    provider: {verifyBearer: async () => null},
    pool: {
      query: async (sql, values) => db.query(sql, values),
      connect: async () => {throw new Error('PRIVATE_ROUTES_NOT_USED_IN_THIS_TEST');},
    } as SqlPool,
    objects: {
      putPrivate: async () => {throw new Error('NO_PRIVATE_MEDIA_IO');},
      deletePrivate: async () => {throw new Error('NO_PRIVATE_MEDIA_IO');},
      readPrivate: async () => {throw new Error('NO_PRIVATE_MEDIA_IO');},
    },
    scanner: {scan: async () => {throw new Error('NO_PRIVATE_EVIDENCE_IO');}},
    keys: {
      generateDataKey: async () => {throw new Error('NO_PRIVATE_KEY_IO');},
      unwrapDataKey: async () => {throw new Error('NO_PRIVATE_KEY_IO');},
    },
    worker: {extract: async () => {throw new Error('NO_PRIVATE_OCR');}},
    rateLimitHmacSecret: Buffer.alloc(40, 3), policyVersion: 'synthetic-discovery-v1',
    discoveryV2: {
      enabled: true, mediaOrigin: 'https://images.example.test', allowRequest: async () => true,
    },
  });
  const server = createServer(handler).listen(0, '127.0.0.1');
  try {
    await once(server, 'listening');
    const address = server.address();
    assert.ok(address && typeof address !== 'string');
    const origin = 'http://127.0.0.1:' + address.port;
    const get = (path: string) => fetch(origin + path);
    const json = async (path: string) => {
      const response = await get(path);
      const body = await response.text();
      assert.equal(response.status, 200, body);
      assert.equal(response.headers.get('cache-control'), 'no-store');
      return JSON.parse(body);
    };
    await db.query('BEGIN');
    await db.query(fixtureFunction);
    await db.query(`
      INSERT INTO sell.discovery_places(id,source_version,name_ar,name_fr,center)
      VALUES ('qa-kelibia','synthetic-v1','قليبية','Kélibia',ST_SetSRID(ST_MakePoint(11.092,36.851),4326));
    `);
    await db.query(`
      INSERT INTO sell.discovery_place_aliases(place_id,alias) VALUES ('qa-kelibia','Kelibia Plage');
    `);
    const fixture = async (kind: string, title: string, lon: number | null, rooms: number | null,
      price: string, time: string, publish = true) => (
      await db.query(`
        SELECT * FROM pg_temp.discovery_fixture($1,$2,$3::float8,$4::smallint,'qa-kelibia',$5,$6,$7)
      `, [kind,title,lon,rooms,price,time,publish])
    ).rows[0];
    const point = await fixture('LAND','Synthetic map land',11.086,null,'100000.001','2026-01-01T01:01:01.000003Z');
    const neighbor = await fixture('LAND','Synthetic second land',11.087,null,'130000.001','2026-01-01T01:01:01.000002Z');
    const areaOnly = await fixture('APARTMENT','Synthetic list-only apartment',null,3,'201000.001','2026-01-01T01:01:01.000001Z');
    const outOfBounds = await fixture('HOUSE','Synthetic outside map',12.99,4,'92000','2026-01-01T01:01:01.000010Z');
    const pending = await fixture('LAND','Synthetic pending land',11.089,null,'18000','2026-01-01T01:01:01Z',false);
    const radar = 'bbox=11.07,36.83,11.11,36.87&radar=36.85,11.086,1';
    const radialList = await json('/api/v1/discovery/list?'+radar);
    const radialMap = await json('/api/v1/discovery/clusters?'+radar+'&zoom=14');
    assert.deepEqual(radialList.listings.map((card: {id: string}) => card.id), [point.listing_id,neighbor.listing_id]);
    assert.deepEqual(radialMap.listings,radialList.listings);
    assert.equal((await get('/api/v1/discovery/list?bbox=11.07,36.83,11.11,36.87&radar=36.85,11.086,21')).status,400);
    await db.query(`
      INSERT INTO sell.listing_public_photos(property_revision_id,approved_by,sha256,asset_path)
      SELECT property_revision_id,assigned_reviewer_user_id, repeat('a',64), 'qa/listing-discovery.jpg'
      FROM sell.verification_cases WHERE id=$1
    `, [point.case_id]);

    const frenchPlace = await json('/api/v1/places/suggest?lang=fr&q='+encodeURIComponent('KÉLI'));
    const arabicPlace = await json('/api/v1/places/suggest?lang=ar&q='+encodeURIComponent('قَلِيبِيَة'));
    const aliasPlace = await json('/api/v1/places/suggest?lang=ar&q=Kelibia%20Plage');
    assert.deepEqual(frenchPlace.places.map((place: {id: string; label: string}) => [place.id,place.label]),
      [['qa-kelibia','Kélibia']]);
    assert.deepEqual(arabicPlace.places, aliasPlace.places);
    assert.equal(arabicPlace.places[0].label,'قليبية');

    const bbox = '10.53,36.36,11.31,37.15';
    const query = 'bbox='+bbox+'&placeId=qa-kelibia';
    const list = await json('/api/v1/discovery/list?'+query);
    assert.deepEqual(list.listings.map((card: {id: string}) => card.id),
      [point.listing_id,neighbor.listing_id,areaOnly.listing_id]);
    assert.equal(list.listings[0].priceTnd,'100000.001');
    assert.equal(list.listings[0].photoUrl,'https://images.example.test/qa/listing-discovery.jpg');
    assert.equal(list.listings[2].locationAccuracy,'AREA_ONLY');
    assert.equal(list.listings[2].latitude,null);
    assert.equal(list.listings[2].longitude,null);
    assert.doesNotMatch(JSON.stringify(list),/DO_NOT_EXPOSE|privateEvidence|storage_key/);

    const zoomed = await json('/api/v1/discovery/clusters?'+query+'&zoom=14');
    assert.deepEqual(new Set(zoomed.listings.map((card: {id: string}) => card.id)),
      new Set([point.listing_id,neighbor.listing_id]));
    assert.deepEqual(zoomed.clusters,[]);
    const grouped = await json('/api/v1/discovery/clusters?'+query+'&zoom=9');
    assert.deepEqual(grouped.listings,[]);
    assert.deepEqual(grouped.clusters.map((cluster: {count: number}) => cluster.count),[2]);

    const filtered = query+'&kind=LAND&minArea=123.456&maxPrice=100000.001&lang=ar';
    const filteredList = await json('/api/v1/discovery/list?'+filtered);
    const filteredMap = await json('/api/v1/discovery/clusters?'+filtered+'&zoom=9');
    assert.deepEqual(filteredList.listings,filteredMap.listings);
    assert.equal(filteredList.listings[0].id,point.listing_id);
    assert.equal(filteredList.listings[0].region,'قليبية');
    const rooms = await json('/api/v1/discovery/list?'+query+'&kind=APARTMENT&minRooms=3');
    assert.equal(rooms.listings[0].id,areaOnly.listing_id);
    const narrower = await json('/api/v1/discovery/list?'+query+'&kind=APARTMENT&minRooms=4');
    assert.deepEqual(narrower.listings,[]);

    const direct = await json('/api/v1/listings/'+point.listing_id);
    assert.equal(direct.listing.id,point.listing_id);
    assert.equal((await get('/api/v1/listings/'+pending.listing_id)).status,404);
    await db.query("UPDATE sell.listings SET status='PAUSED' WHERE id=$1",[outOfBounds.listing_id]);
    assert.equal((await get('/api/v1/listings/'+outOfBounds.listing_id)).status,404);
    await db.query("UPDATE sell.verification_cases SET status='EXPIRED',expires_at=now()-interval '1 day' WHERE id=$1",[point.case_id]);
    assert.equal((await get('/api/v1/listings/'+point.listing_id)).status,404);
    await db.query("INSERT INTO sell.property_revisions(property_id,revision_number,snapshot) VALUES ($1,2,'{}')",[neighbor.property_id]);
    assert.equal((await get('/api/v1/listings/'+neighbor.listing_id)).status,404);
    const remaining = await json('/api/v1/discovery/clusters?'+query+'&zoom=14');
    assert.deepEqual(remaining,{clusters:[],listings:[]});

    await db.query(`
      SELECT listing_id FROM generate_series(1,42) n
      CROSS JOIN LATERAL pg_temp.discovery_fixture(
        'LAND','Synthetic page ' || n::text,11.086,NULL::smallint,'qa-kelibia',70000,
        '2026-01-01T01:01:01.000123Z'::timestamptz + n*interval '1 microsecond',true
      )
    `);
    const first = await json('/api/v1/discovery/list?'+query+'&kind=LAND');
    assert.equal(first.listings.length,40);
    assert.equal(first.listings[39].title,'Synthetic page 3');
    assert.ok(first.nextCursor);
    const second = await json('/api/v1/discovery/list?'+query+'&kind=LAND&cursor='+first.nextCursor);
    assert.deepEqual(second.listings.map((card: {title: string}) => card.title),['Synthetic page 2','Synthetic page 1']);
    assert.equal(second.nextCursor,null);

    const villa = await fixture('VILLA','Synthetic villa',11.086,5,'400000','2026-01-02T00:00:00Z');
    const factory = await fixture('FACTORY','Synthetic factory',11.089,null,'500000','2026-01-02T00:00:00Z');
    for (const [kind, listingId] of [['VILLA',villa.listing_id],['FACTORY',factory.listing_id]]) {
      const matchingList = await json('/api/v1/discovery/list?'+query+'&kind='+kind);
      const matchingMap = await json('/api/v1/discovery/clusters?'+query+'&zoom=14&kind='+kind);
      assert.deepEqual(matchingList.listings.map((card: {id: string; kind: string}) => [card.id,card.kind]),
        [[listingId,kind]]);
      assert.deepEqual(matchingMap.listings,matchingList.listings);
    }
  } finally {
    server.close();
    try { await db.query('ROLLBACK'); } finally { db.release(); await database.end(); }
  }
});
