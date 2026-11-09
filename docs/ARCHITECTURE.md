# Architecture baseline v0.1

## Strategy
Start as a modular monolith: one API deployment, one transactional PostgreSQL + PostGIS database, one isolated private object store, one Android application, and one staff web console. Keep modules independently testable; split services only with measured scale.

## Client
- Preferred: Kotlin + Jetpack Compose for Android APK/AAB.
- Globe: native Mapbox Android spike; evaluate pricing/tile coverage for Tunisia and compare with MapLibre Native. No engine lock-in until proof on a low-end Android device.
- Map is not the only navigation: accessible, indexed list search is first-class; no permanent 3D rendering in background, reduce detail while moving/low RAM.
- Feature modules: discover, listing-details, favorites, seller, verification, messages/contact, identity, settings.

## Backend
- TypeScript; Fastify preferred after contracts spike.
- Modules: auth, parties/authorization, properties, parcels, revisions, claims, verification, listings, search/geo, media, contact, moderation, auditing.
- Async work: document scanning/metadata checks and image resizing via bounded queue; not part of a long HTTP request.
- APIs: OpenAPI/JSON Schema as contract; no trust in client-computed state.
- Avoid distributed transactions in V1; use DB transactions + outbox for side effects.

## Database and geo
- PostgreSQL + PostGIS; parcel polygons are versioned evidence-backed geometry, not user-drawn legal truth.
- Split properties, units/parcels, revision snapshot, authorized party claim, case, decision and public listing.
- Use GiST for geospatial lookups, pagination and clustering; consider vector tiles only after profiling.
- Exact legal boundary, approximate public marker, and administrative geo layers must remain distinct.

## Verification trust model
- Eligibility is DERIVED in the server from immutable property revision + approved claim + unexpired verification case + decision + listing item scope.
- Any legal revision supersedes approval of older revision. Reviewer actions always audited.
- Public API reads a permission-safe projection or view, never unfiltered raw listings.
- Lock/transaction/idempotency for concurrent reviewer decisions; revoke on suspension/expiry.
- No public evidence URLs, OCR output, identity data, owner phone by default, or file-storage keys.

## Infrastructure
- One repository; independent production infrastructure and credentials.
- Local/pilot storage and runtime chosen only after data residency/security decisions; CI uses synthetic data only.
- CI: GitHub-hosted ubuntu-latest only, no Mac or self-hosted; read-only tokens, pinned dependencies/actions when introduced.
- Deployments require a distinct approved workflow and environment; never deploy from arbitrary pull requests.

## Open technical spikes
1. Native 3D globe on budget Android in Tunisia, memory/frame time/cold start/offline behavior.
2. Public availability and licensing of Tunisian geography and map tiles.
3. Schema and adversarial DB verification gates with multiple-property listing.
4. Document pipeline and storage-region design; threat model before adding real users.
5. App release signing, identity/keystore custody and reproducible Ubuntu build.
