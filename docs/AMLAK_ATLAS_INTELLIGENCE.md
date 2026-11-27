# Amlak Atlas Intelligence — Implementation contract

**Date:** 2026-11-23 · **Scope:** Amlak Android + reviewed Atlas outputs · **Status:** Incremental QA only.

This is an implementation companion, **not a competing master plan**:
- [Atlas source-of-truth & cadastral plan priority — Issue #20](https://github.com/levent-ay-platform/sell/issues/20)
- [Amlak product/security contract — Issue #21](https://github.com/levent-ay-platform/sell/issues/21)
- [Android implementation and CI — Draft PR #22](https://github.com/levent-ay-platform/sell/pull/22)

## Product promise

A Tunisian buyer describes a property in Tunisian Arabic, Standard Arabic, or French; Amlak shows interpretable, editable filters and **actual eligible property listings**. Additional approved Atlas/AFI/plan information is contextualized, never mislabeled as properties for sale. The client explains what it knows, what it only suggests, and what it cannot verify.

## Component boundaries

```text
 PRIVATE ATLAS                                    PUBLISHED PROPERTY PLATFORM
 immutable source archives                        eligible listing revisions
 operational catalogue & derived indexes          listing/discovery/review APIs
 unlinked candidates retained                        |
            | reviewed versioned export              |
            v                                        v
   ATLAS READ-ONLY GATEWAY                    DISCOVERY REPOSITORY
   scoped IDs, provenance, rights             exact consent/approx location
   location/geometry confidence               revocation + pagination
            |                                        |
            +-------------> AMLAK DISCOVERY DOMAIN <--+
                             typed multi-source results
                             bounded geospatial filters
                             no cross-source identity guesses
                                           |
                             INTENT + EVIDENCE EXPLAINER
                             deterministic filters first;
                             pluggable LLM later as tool caller
                                           |
                             ANDROID 2D MAP + SEARCH
                             locale AR/TN/FR, offline fallback
```

### Typed source contract

Every result has a **kind**, never merely a merged anonymous marker:

| kind | Meaning | Public presentation |
| --- | --- | --- |
| `PUBLISHED_LISTING` | Eligible seller listing revision | Property card, price, contact |
| `AFI_PLANNED_LOT` | Mapped industrial lot in a captured AFI plan | AFI planning card, snapshot date, *not a sale listing* |
| `ATLAS_PLANNING_ZONE` | Licensed, geometry-verified planning record | Planning layer, classification/date |
| `ATLAS_CADASTRAL_PLAN` | Reviewed document with source/version rights | Approved redacted document or plan-space view |
| `ATLAS_OFFICIAL_PARCEL` | Independently validated cadastral boundary | Solid official boundary only with publication consent |
| `OWNER_CANDIDATE` | User-submitted/traced approximate geometry | Clearly indicative dashed outline |

The public gateway exposes only reviewed/redacted/rightful **derived projections**; raw Atlas endpoints, source archives, private names/CIN and privileged join keys stay private. Retain every unlinked original/candidate in Atlas with scoped identifiers.

### Dynamic search pipeline

1. Normalize digits, Tunisian place aliases, accents and bilingual query terminology without overwriting originals.
2. Interpret only recognized constraints (place, property kind, price ceiling, area range, requested docs, industrial planning). Show preview and **explicit Apply**; preserve manual filters not mentioned.
3. Resolve Atlas place **locally** without inventing a backend place ID. Correct map camera/viewport; apply the same filter contract to sale map and list.
4. Industrial planning requests open the **separate AFI catalog** with locality prefilter. AFI availability is an independent, dated field and is not seller availability.
5. Return actual source data; treat unavailable source/API as unavailable, not as an empty or zero-covered region. Never fill a missing result with synthetic properties.
6. Later, with Atlas Gateway operational, return typed, evidence-scoped contextual links. An LLM may explain linked evidence, but never manufacture authoritative geospatial assertions.

### Operational Atlas P0 (read-only audit)

- The earlier legacy `control.sqlite` has 832 resource rows, 801 blob rows and 1,098 document-page rows. `atlas-tree-v1.sqlite` has 2,390 administrative units and 3,899 evidence-edge records. Legacy `spatial_feature` currently shows zero rows **only in that specific index**. These are not global Atlas coverage claims.
- The active Atlas repository and operational `data/control/catalogue.sqlite` exist separately from those legacy indexes, with additional map-index snapshots. Initial `sqlite3 mode=ro` opening the operational catalogue returned `unable to open database file` even as the owner. Diagnose SQLite WAL/locking/ACL and obtain a consistent owner-approved read-only snapshot before reporting operational record counts or building a gateway from it.
- Do **not** copy or publish full private production SQLite to the Android app, a public repository or a model prompt. Do not repair in place, run destructive migrations, or bypass private permissions.

### Android implementation increment

`SmartDiscoveryIntent.kt` is an intentionally **deterministic local parser**, not a claim that a model or a live Atlas federated API is deployed. It accepts bilingual property/place/area/budget requests and can prepare display labels; optional document request shows only that data is desired and **does not assert any documents exist**. A separate industrial flag routes AFI planning to its own screen.

### Acceptance gates

- **Parser:** Tunisian/FR diacritics, Arabic numerals, budget units, area bounds, unknown queries, no wrong source promotion and manual-filter preservation.
- **Discovery:** server eligibility/revocation enforced, filters identical between map/list/radar, no invalid public coordinates.
- **Atlas:** verify actual operational DB and snapshot version, geometry classification and rights before exposing any derived result; the unknown/unlinked collection must remain lossless.
- **UX:** Arabic RTL and French LTR on 360dp/412dp with 130% font, readable contrast, IME unobscured, Back navigation, 2D map tiles and list fallback, meaningful feedback respecting system settings.
- **Security:** protected original documents/CIN inaccessible to guest APIs, revocations clear public caching, no fixed pilot OTP exposed.
- **Delivery:** tests in GitHub CI, isolated emulator and real device acceptance; PR remains Draft until owner authorizes merge/release.

## Next implementation sequence

1. Pass CI and physically QA the deterministic AR/FR smart search and AFI locality browsing.
2. Audit the operational Atlas catalogue and reproduce feature/document counts from a consistent approved read-only snapshot; document version semantics.
3. Build a bounded, read-only **private-to-public reviewed export** for a single Nabeul/Kélibia slice with typed source records, provenance and rights.
4. Extend Amlak discovery with evidence links and source-specific results, then implement Land X-Ray on those vetted links.
5. Add swappable LLM tool orchestration and explanations behind a feature flag only after grounded deterministic tools and evaluation fixtures pass.

## P1 public Pilot feed reconciliation (2026-11-23, Draft only)

The deployed legacy Mac Pilot public route (`/api/public/listings`) and the proposed
Postgres V2 verified discovery (`/api/v1/discovery`) are **different services**.
Neither route is a substitute for the other's authorization rules. Android
public-feed QA uses the former read-only until the V2 route is really deployed.

**Server output contract in source** (`services/verification/qa/pilot_workflow.py`):

- Public allowlist: `id,title,region,description,area,price,kind,latitude,
  longitude,locationAccuracy,publishedAt,ownershipVerified,photos`. No private
  identity, phone, case/review IDs, encrypted evidence, seller notes, or source
  secrets. A public response is never proof of legal ownership.
- **Direct owner-publication only:** coordinates may be shared as a **coarse**
  2-decimal-degree approximate point after explicit owner publication, with
  Tunisia-bounded, finite input validation. Raw owner coordinates stay private.
  This is *not* a surveyed cadastral boundary or a verified parcel.
- **Admin-reviewed legacy submission:** no distinct public-point consent exists;
  even when the private review recorded coordinates, the public card is
  `AREA_ONLY`. Its original place fields remain untouched.
- Missing or unsupported `kind` is `UNKNOWN`, never assumed `LAND` from
  a title/region/OCR. Existing direct listings retain exactly their explicit
  type. `ownershipVerified` stays `false` for a mere Pilot publication.
- `status=PUBLISHED` only is returned; `PAUSED`, `DRAFT`, removed and
  rejected items must disappear. Same immutable listing ID is preserved during
  direct publish → later private document review → owner pause/resume.
- `/api/atlas/listings` uses the **same public filtered/rounded projection**;
  no separate exact-location leak or `LAND` fallback.
- `publishedAt` in the existing legacy service is currently sourced from
  `submissions.updated` and therefore can change on edit/verification.
  **Do not label this the historical first publication date** until a separate
  durable first-publication timestamp exists. This is a later migration gate.

**Acceptance evidence:** isolated Python + HTTP synthetic suite on
`.github/workflows/amlak-pilot-public-contract.yml` exercises direct/public
projection, same ID, pausing, moderation, private evidence, verified-badge
separation, out-of-country rejects, and missing-type behavior. Do **not**
enable the new source in production from a green test alone. Require staging
backup/rollback, owner approval, observable live `kind`/accuracy, no 0,0,
public list + radar + map parity, and post-withdrawal revocation.

**Status:** code/CI staging-ready only; production Mac server, SQLite and
Cloudflare/Huawei routing were **not touched**.
