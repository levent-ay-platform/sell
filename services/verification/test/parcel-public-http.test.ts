import {createHash} from 'node:crypto';
import {createServer} from 'node:http';
import {once} from 'node:events';
import test from 'node:test';
import assert from 'node:assert/strict';
import {createParcelHttpHandler} from '../src/parcel-http.ts';

const listingId = '10000000-0000-4000-8000-000000000001';
const documentId = '10000000-0000-4000-8000-000000000002';
const storageObjectId = '10000000-0000-4000-8000-000000000009';
const pdf = Buffer.from('%PDF-1.4\n%%EOF');
const sha = createHash('sha256').update(pdf).digest('hex');
const base = '/api/v1/listings/' + listingId;

test('approved document serving keeps internal identifiers private and honors revocation', async () => {
  let active = true;
  let revokeDuringRead = false;
  let reads = 0;
  let content: Uint8Array = pdf;
  const handler = createParcelHttpHandler({
    allowRequest: async () => true,
    authenticate: async () => null,
    repository: {
      listing: async () => active ? {
        boundaries: [],
        documents: [{id: documentId, kind: 'CADASTRAL_PLAN', mime: 'application/pdf'}],
      } : null,
      approvedDocument: async () => active ? {storageObjectId, mime: 'application/pdf', sha256: sha} : null,
      ownerCase: async () => { throw new Error('unused'); },
      saveOwnerGeometry: async () => { throw new Error('unused'); },
      recordDecision: async () => { throw new Error('unused'); },
    },
    readRendition: async (id, maxBytes) => {
      assert.equal(id, storageObjectId);
      assert.ok(maxBytes >= pdf.length);
      reads++;
      if (revokeDuringRead) active = false;
      return content;
    },
  });
  const server = createServer((req,res) => {
    void handler(req,res).then(handled => { if (!handled) res.writeHead(404).end(); });
  }).listen(0, '127.0.0.1');
  try {
    await once(server,'listening');
    const address = server.address();
    assert.ok(address && typeof address !== 'string');
    const get = (path: string) => fetch('http://127.0.0.1:' + address.port + path);
    const details = await get(base + '/parcel');
    const body = await details.text();
    assert.equal(details.status,200);
    assert.equal(details.headers.get('cache-control'),'no-store');
    assert.deepEqual(JSON.parse(body).documents, [{
      id: documentId, kind: 'CADASTRAL_PLAN', mime: 'application/pdf',
      url: base + '/documents/' + documentId,
    }]);
    assert.doesNotMatch(body, new RegExp(storageObjectId + '|' + sha));
    const file = await get(base + '/documents/' + documentId);
    assert.equal(file.status,200);
    assert.equal(file.headers.get('cache-control'),'no-store');
    assert.equal(file.headers.get('x-content-type-options'),'nosniff');
    assert.deepEqual(Buffer.from(await file.arrayBuffer()),pdf);
    content = Buffer.from('unverified content');
    assert.equal((await get(base + '/documents/' + documentId)).status,503);
    content = pdf;
    revokeDuringRead = true;
    const revoked = await get(base + '/documents/' + documentId);
    assert.equal(revoked.status,404);
    assert.doesNotMatch(await revoked.text(),/%PDF|storageObjectId/);
    assert.equal((await get(base + '/documents/' + documentId)).status,404);
    assert.equal(reads,3);
    assert.equal((await get(base + '/parcel')).status,404);
  } finally {
    server.close();
  }
});
