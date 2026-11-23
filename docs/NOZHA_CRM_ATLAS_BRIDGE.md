# Nozha → Amlak first real CRM project — initial bridge checkpoint (2026-11-23)

## Scope and existing source
- Source CRM: `levent-ay-platform/facebook-ads-crm`, tenant `casaimed-iaa4rp`, CRM property ID `3`, **CRM property code `561663` (NOT a land title number)**, title `Nozha`.
- Controlled CRM branch: `feat/nozha-amlak-public-card-20261123`, [Draft PR #986](https://github.com/levent-ay-platform/facebook-ads-crm/pull/986). Do not pull unreviewed CRM code into production.
- Baseline last verified data: CRM canonical Nozha closeout **2026-10-01**, 48 lots, 36 available, 12 blocked, 0 reserved/sold at that date. This is a **historical snapshot**, not current availability or prices. 6 customer-shareable photos, 2 source masterplans **none customer-shareable**, 43/48 local plan shapes, **0 lot GPS polygons** at that audit date. No official TF reference (`legalTitleRef` was null); model `individual_title` was a CRM statement, not legal ownership proof. Agricultural land classification is a CRM commercial field, **not a zoning permit**.
- Nozha `nozha-source-masterplan-v2` is a digitized geometrical reconstruction based on a WhatsApp source-plan image; its `inventoryAuthority=source_plan_only` and its georeference confidence `approximate` explicitly require visual acceptance. **Do not auto-publish this drawing or call it cadastral geometry.**
- Read-only `atlas-tree-v1.sqlite` Mac scan found no document/resource names containing `Nozha` in selected name/title/source/path columns at 2026-11-23; this is **not an exhaustive TF/plot search** and proves neither no official plan nor a TF connection. Official Atlas #20 is the source for later match provenance.

## Implemented in CRM Draft PR #986 (source-only)
- `server/modules/realEstate/nozhaAmlakPublicProjection.ts` accepts a Nozha-specific, tenant-scoped source snapshot with an independent manager-approved, expiring publication decision. It projects only project code/title, named advertiser **CasaImed Group (marketing agent, NOT legal owner)**, lot code/status/area/valid public ask, time-freshness, approved approximate project point and independent verification labels.
- `available` = green and for-sale ONLY while CRM snapshot is fresh; `sold` = red in the project plan but never an active sale target; `reserved` amber; `hold` amber/yellow; `blocked` neutral grey. Nozha local source plan and unapproved source file/lot GPS polygons never leave the projection.
- Reject missing/mismatched project/tenant/approval or expired publication grant. Stale snapshots cannot show actionable asking prices or available-for-inquiry flags. Never expose customer names, phone numbers, deal/transaction IDs, seller's exact point, title number or private documents.
- The CRM data completeness audit `scripts/audit-nozha-amlak-readonly.ts` is **SELECT-only** inside a read-only consistent MySQL transaction. It outputs status counts, price/area completeness and geometry coverage without identifying customers or sharing exact location/title reference.
- Tests and isolated GHA must pass on exact CRM PR SHA before connected work. **No public CRM API route exists yet and no live data export was approved**.

## Planned Amlak public-card contract
1. **Header:** Nozha, location accuracy and updated-at, publisher `CasaImed Group — marketed by`, project and lot code, approved photo(s).
2. **Inventory:** five CRM states and exact current lot code; mobile masterplan only when manager authorizes both publication and plan rights. Sold remains red but excluded from buying/radar. For no georeferenced approved lot boundary, show a neutral list/map-area label, NOT fabricated coordinates.
3. **Commercial section:** valid latest asking amount (pricing basis total/per-sqm/from), area, installments/deposit for the **selected lot**, per-lot source version; customer-shareable water/electricity source/status, no contradictory promise of legal construction rights.
4. **Atlas verification section:** `titleReference` if independently matched to an authorized official document; source ID/sha256/document issue date; `plotReference`, cadastral plan, document authority, local plan vs true WGS84 geometry distinction, PAU zone/stated vocation only if spatially verified. Latest independent reviewer decision and owner/public release consent must exist for public approved renditions.
5. **Three independent trust states**: inventory current (CRM); documents reviewed (Amlak/Atlas); legal owner & cadastral outline match (independently confirmed). **Never use a single green ownership badge when merely CRM truth is present.**

## Fresh read-only follow-up (2026-11-23)

- The isolated audit script ran against the authorized CRM connection inside the existing runtime using a consistent read-only transaction. The successful recheck at 10:40 UTC refreshed the commercial completeness audit. No raw Nozha rows, prices, parcel coordinates, title strings or private documents are included here.
- The MySQL driver returned string flags for computed geometry columns. The audit now counts only numeric or string 1, correcting false positives caused by treating string 0 as truthy. The corrected result found local plan geometry, zero lot geographic geometries and zero lot GPS centroids.
- The private Atlas catalogue's document filename/path and GeoPAU title search returned no matches for the project name variants or commercial reference. The name index returned two administrative-sector candidates. A name by itself cannot identify the project or its title. A reviewed official source/reference match and separate publication authorization are still required.
- Android preserves the CRM pricing basis and displays the unit in the lot dialog. The emulator test renders the buyer card using a synthetic payload, with a specific assertion for TND per square meter.

## Live blockers / acceptance
- The fresh read-only audit is complete. Per-project and per-document publication approval is still required independently of commercial completeness.
- Need to match source plan/document permissions and independently find official cadastral references; current project code is NOT a legal TF.
- Only after manager-approved publication decision: implement a separate read-only public API with strict TTL/authorization/revocation and consume it through Amlak map/list/Nozha card in QA. No false direct CRM DB access from an Android client.
- Keep Draft PR #22 separate; do not merge/deploy live CRM or Amlak without approval and backout plan.

## Near-real-time inventory QA

The CRM read-only worker `scripts/sync-nozha-amlak-readonly.ts` reads the committed Nozha inventory every three seconds and writes an atomic, project-scoped snapshot. It uses `NOZHA_AMLAK_READONLY_DATABASE_URL`, `NOZHA_AMLAK_APPROVAL_FILE`, and `NOZHA_AMLAK_FEED_FILE`. Run a single instance. The approval file is a verified manager decision in the format of `NozhaAmlakRelease`. The publisher and API must share the snapshot through a private directory, with the API having read-only access. The API reads `MALAK_NOZHA_FEED_FILE` and returns 404 for unpublished or expired grants and 503 after 20 seconds without a fresh read.

Android refreshes the open Explore view and Nozha details every four seconds once a card is available. A failed read hides the live price and inquiry action. The UI retains the open lot during inventory changes. Android uses a separately approved approximate project point in Explore and MapLibre. Radar includes the project only when that point is inside the circle and the current stock contains an actionable lot. It does not infer any individual lot position or apply lot price filters to a project point.

The CRM follow-up verified a project point and a populated legal-title reference in business truth. It also verified an active approximate masterplan alignment, with calibration adjustments. The CRM GIS screen transforms plan-space vertices at runtime with this alignment. The stored lot rows have no individual geographic polygons or centers. The approximate alignment and CRM legal reference can support an Atlas review. They do not establish an official crosswalk or permit public distribution of plan and title data.

## Enrolled Pilot subdivision overlay

The exporter can produce the approximate geographic subdivision for the enrolled Pilot using the CRM plan-space polygons, the bound Nozha blueprint and the persisted alignment. Enable it via `pilotDerivedPlanApproved` on the scoped release. `/api/pilot/projects/nozha` serves the overlay to enrolled account/device pairs. The public project response omits the overlay. Android draws status-colored clickable parcels and offers a zoom-to-plan action from the project card. Plot labels and status come from the same feed snapshot. Source scans and cadastral references remain private.

Activation requires a separately enrolled Pilot runtime, an explicit account/device whitelist and the CRM snapshot feed. The existing login-only gateway returned HTTP 404 for the private project route. Existing login service and data were left intact.
