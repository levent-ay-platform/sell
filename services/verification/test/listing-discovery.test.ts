import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {
  createListingDiscoveryHttpHandler, PostgresListingDiscoveryRepository,
} from '../src/listing-discovery.ts';
import type {SqlPool} from '../src/postgres-evidence-repository.ts';

const listingId = '00000000-0000-4000-8000-000000000003';
const bbox = '10.53,36.36,11.31,37.15';

test('disabled discovery leaves the existing router in control', async () => {
  const handler = createListingDiscoveryHttpHandler({enabled: false});
  const server = createServer(async (req, res) => {
    if (!await handler(req, res)) {
      res.writeHead(404).end('existing router');
    }
  }).listen(0, '127.0.0.1');
  try {
    await once(server, 'listening');
    const address = server.address();
    assert.ok(address && typeof address !== 'string');
    const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/discovery?bbox=${bbox}`);
    assert.equal(response.status, 404);
    assert.equal(await response.text(), 'existing router');
  } finally {
    server.close();
  }
});

test('discovery validates filters, rejects invalid detail IDs and rate-limits before querying', async () => {
  const queries: string[] = [];
  const pool = {
    async query(sql: string) {
      queries.push(sql);
      return {rows: []};
    },
  } as SqlPool;
  let permit = true;
  const handler = createListingDiscoveryHttpHandler({
    enabled: true, repository: new PostgresListingDiscoveryRepository(pool),
    allowRequest: async () => permit,
  });
  const server = createServer(async (req, res) => {
    if (!await handler(req, res)) res.writeHead(404).end();
  }).listen(0, '127.0.0.1');
  try {
    await once(server, 'listening');
    const address = server.address();
    assert.ok(address && typeof address !== 'string');
    const get = (path: string) => fetch(`http://127.0.0.1:${address.port}${path}`);
    for (const query of [
      'bbox=9,35,8,37', 'bbox=-180,-80,180,80', `bbox=${bbox}&bbox=${bbox}`,
      `bbox=${bbox}&maxPrice=1e3`, `bbox=${bbox}&kind=OTHER`,
      `bbox=${bbox}&minPrice=100.003&maxPrice=100.002`,
      `bbox=${bbox}&cursor=invalid`, `bbox=${bbox}&ownerId=private`,
    ]) {
      assert.equal((await get(`/api/v1/discovery/list?${query}`)).status, 400, query);
    }
    assert.equal((await get(`/api/v1/discovery/clusters?bbox=${bbox}&zoom=23`)).status, 400);
    assert.equal((await get('/api/v1/listings/not-a-listing-id')).status, 404);
    assert.equal(queries.length, 0);

    permit = false;
    assert.equal((await get(`/api/v1/discovery?bbox=${bbox}`)).status, 429);
    assert.equal(queries.length, 0);

    permit = true;
    const result = await get(`/api/v1/discovery?bbox=${bbox}&minPrice=100.002&maxPrice=100.003`);
    assert.equal(result.status, 200);
    assert.equal(result.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await result.json(), {listings: [], nextCursor: null});
    assert.equal((await get(`/api/v1/listings/${listingId}`)).status, 404);
    assert.equal(queries.length, 2);
  } finally {
    server.close();
  }
});
