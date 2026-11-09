# SELL product contract — Tunisia first

## Promise
People discover properties via an optional interactive globe/map or fast list. Only currently approved, in-scope listings are publicly discoverable. Buyer contacts seller or verified authorized representative directly; SELL is not a broker in V1.

## Roles
- Anonymous buyer: browse, filter, view public verification summary; no document access.
- Registered buyer: save searches, favorites, send rate-limited contact request; visibility of phone is consent-based.
- Seller: create private draft, submit identity/rights evidence, manage approved listing.
- Reviewer: review only assigned cases and read evidence via short-lived access; cannot change submitted source evidence.
- Admin: suspend listings, manage reviewer assignment and appeals, audit exceptional actions.
- Service: automated checks are advisory; no automatic legal ownership approval solely by AI/OCR.

## V1 flows
1. Anonymous browsing, country centered on Tunisia; globe gracefully falls back to 2D map/list.
2. Seller signs in by phone; creates draft with property type, country/administrative area, approximate map position, price, area, description, images.
3. Seller identifies their capacity: owner, co-owner, heir, authorized representative, company signatory.
4. Seller submits a verification claim, documents, legal/cadastral references and consent.
5. Reviewer accepts/rejects/requests information with recorded reasons. No public listing before acceptance.
6. Seller publishes approved snapshot; buyers see verification scope and review date, not deeds/identity.
7. Changes to legal identity, property rights or reviewed parcel boundary create a NEW revision and invalidate public verification. Price/description changes follow moderation without automatically rewriting ownership evidence.
8. Reporting, appeals, delisting and expiry work without erasing audit events.

## Property types
Land: residential/agricultural/commercial/other; buildings: apartment/villa/house/commercial; distinct units and plots.
One listing can have multiple verified property items; each item must be approved for the same authorized seller. No implicit right to sell jointly owned property.

## Local experience
Arabic and French, correct RTL/LTR, TND as NUMERIC(18,3), m²/hectare, E.164 +216 phone handling, administrative subdivisions from a licensed and versioned Tunisia dataset. Text is written for Tunisian users; do not translate legal status automatically.

## Non-goals for V1
Payments, escrow, receiving deposits, legal title transfer, guaranteeing ownership, automatic cadastral certification, uncontrolled document sharing, unrestricted messaging, worldwide data coverage at launch, microservices.

## Product invariants
- An unverified/expired/suspended listing is NEVER returned by a public search endpoint.
- A submitted review case references a specific immutable property revision and claim.
- Public map markers never imply legally verified polygon boundaries unless the parcel scope was reviewed.
- Identity/deed evidence is never public and never committed into this repository.
