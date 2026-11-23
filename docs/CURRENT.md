# CURRENT — Amlak / Atlas Tunisia
_Last audited: 2026-11-23 (Europe/Istanbul). This is the navigation and checkpoint index, not a replacement for the authoritative issue records._

## Source-of-truth ownership (do not create competing master plans)

| Scope | Authoritative source | Rules |
| --- | --- | --- |
| Official Tunisian cadastral plans, land-title/parcel document inventory, Atlas acquisition, provenance, INS / PAU / GIS coverage | [ATLAS MASTER — Issue #20](https://github.com/levent-ay-platform/sell/issues/20) | **Official cadastral plans are the primary document goal.** PAU/INS layers are supporting datasets, not proof of legal parcel boundaries. UNKNOWN is not zero. Preserve raw evidence. |
| Consumer product experience, map-first 2D architecture, data/security/publication contract | [Product blueprint — Issue #21](https://github.com/levent-ay-platform/sell/issues/21) | Approved Tunisia 2D map-first with separately toggleable verified Atlas layers; sales-only, closed initial Nabeul/Kélibia launch; keep crimson/pearl palette. |
| Android implementation and test evidence | [Draft PR #22](https://github.com/levent-ay-platform/sell/pull/22) and its checks | Work in the PR branch until accepted; not merged and not deployed. |
| Single-property cadastral lookup | [Issue #19](https://github.com/levent-ay-platform/sell/issues/19) | Separate from aggregate Atlas coverage. |
| Older seller OCR flow | [Draft PR #14](https://github.com/levent-ay-platform/sell/pull/14) | Do not assume merged or deployed. |

**Approved public app name: Amlak.** Earlier code/paths use MALAK/Malak for historical compatibility. The existing Android package identifier `tn.malak.app` was deliberately not renamed to avoid data loss; technical slugs are not consumer branding.

## 2026-11-23 globe and buyer radar

The Android map opens at world scale with a rotating globe and a beacon at Tunisia. Radar opens the Tunisia overview. The enabled AFI and Atlas layers show documented lot counts by governorate, industrial sites, and individual lot shapes at progressively closer zooms. The packaged AFI snapshot contains 47 zones and 2,838 mapped lots across 23 governorates. The overview also includes Gafsa with zero lots in this snapshot. The badges count planning geometry. Sale listings continue to come from the listing API.

Account settings now include independent sound and haptic controls. The installed app uses short action, radar, adjustment and confirmation cues with Android system settings. Connected Pilot QA APK SHA-256 `9fc646878c50a8090651487e0d26d0735e0d027388fbfc376c5e3217185f4e0c`. Physical handset feedback remains to be evaluated.

## 2026-11-23 document area and handoff review

- The owner can keep the entered area, use the document area, or offer part of it. Both seller forms show the eligible area, document family and page. A direct draft restores its private review by ID. A gateway advertising `draftDocumentReview` can attach that review in the publication transaction. The area in the buyer card follows the owner’s choice. The live capability endpoint returned HTTP 404 on the read-only check.
- Android passed 87 unit tests. On the connected-build emulator the seller form displayed a fixture of 900 m² on page 2 beside an entered 275 m², then previewed each choice and a manually entered 180 m². The startup check passed. The separate handoff emulator showed Arabic Alerts, Explore and the signed-out publishing entry at 130% font size. Real owner scans and a physical handset still need acceptance.
- The admin password compatibility review reproduced the fixed vector independently and exercised stored hashes in both provider directions. Bootstrap now enforces the login length limit. Local QA passed 33 Python and 397 TypeScript tests. The protected Mac worktrees were inventoried read-only. The recovery directory requires a different OS account, so this pass did not independently verify its manifest.
- Connected Android QA APK SHA-256 `9ae0d2fa51fce92c86bf862b0b6e1fdcc0ec4f14cb6ee9f681b0023c4fdbbe5f`. The buyer and Atlas publication contract remains in [Issue #21](https://github.com/levent-ay-platform/sell/issues/21).

## 2026-11-22 safety and product decision (after Codex quota)

- **Approved product contract is authoritative in [Issue #21](https://github.com/levent-ay-platform/sell/issues/21):** owner can publish an **unverified** listing in the explicitly authorized closed Pilot first, then complete verification/documents on the **same immutable listing ID**. Verification status is independent of the listing's publication status. "PUBLISHED_UNVERIFIED" is a **presentation concept**, not a new SQL enum: current Pilot stores `status=PUBLISHED` with separate `verificationStatus`. Existing V2 verified-publication guard remains intact; do not bypass it.
- Publication does **not** prove ownership or authorize public CIN/unredacted property evidence. Public cadastral boundaries/approved redacted document renditions require reviewer decision, provenance and independent geometry/document publication consent. Unlocated plan drawings remain *plan-space estimates*, not cadastral geometry. Atlas accepted contributions remain a separate source-labelled layer; see [Issue #20](https://github.com/levent-ay-platform/sell/issues/20).
- **Read-only live gateway recheck from LEVENT-MAC:** `GET https://malak-api.ayasofia.shop/api/public/listings` returned **HTTP 200**, while `GET /api/public/capabilities` returned **HTTP 404** (valid TLS). This does **not** prove that direct Pilot publishing is live. **Do not enable production direct-publishing or fixed shared OTP `1234` publicly.**
- **Preservation verified on Mac:** original Amlak Codex worktree `07e20a7` still has 83 status entries; Atlas `c22dec8` has 8; both backup manifests/patches and untracked ZIPs passed SHA-256 checks at `/Users/levent/Projects/amlak pro/.runtime/recovery/20261122-amlak-codex-quota-recovery-v1/`. New isolated `/Users/levent/.codex/worktrees/amlak-gpt6-safe-continuation-20261122` is clean at `2ae11af`; do not mix, reset, or auto-merge dirty sources. This documentation change may move the PR branch HEAD, but the implementation source remains `2ae11af` until another code patch is verified.
- **QA caveat:** GitHub Android/Foundation/Verification workflows passed for `2ae11af`, and matching ZIP/APK hashes were checked locally. The latest QA APK could **not** update the older QA package due `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; a new disposable QA AVD was created but did not complete its first Android boot and was powered down. No physical-phone acceptance, map tile appearance or end-to-end live publication can be claimed on that basis. Separate emulator `emulator-5564` and existing `emulator-5566` data were retained.
- **Next gates:** (1) prove real closed-Pilot capability and true phone/device authentication in a non-production deployment, (2) re-run privacy/revocation/parity tests with proper local dependencies or CI, (3) QA source-identical APK on disposable ready emulator/physical phone at AR RTL/FR LTR and large text, (4) then seek explicit rollout approval. No deploy, production data changes, or merge as part of this checkpoint.

## Verified 2026-11-22 Pilot checkpoint

- The bilingual map radar filters public listings within 1–20 km and shares the selection with the list. The map includes a route to AFI industrial subdivisions, labeled as planning reference data.
- Direct listing publication requires the gateway capability and private enrollment for closed Pilot accounts. The live gateway currently returns HTTP 404 for the capability endpoint. The app uses the existing review flow against that gateway.
- Emulator QA passed 83 unit tests and five instrumentation checks. A separate isolated end-to-end run published a test listing and found it in buyer radar results. The gateway and OCR contract suites passed 23 tests. The parcel document endpoint passed privacy and revocation checks on disposable data.
- Local Pilot APK SHA-256: `805d93be33ef707d2df597c912b448d159994ce66e561771138078dae344d310`. Physical Android QA and a real listing with a reviewed public document remain open. See [Draft PR #22](https://github.com/levent-ay-platform/sell/pull/22) for review and CI.

## Verified 2026-11-20 checkpoint

- **Amlak 2.1 tested implementation commit:** `a2bed087c095b32cb4ba21137963661238ef9284` (map load timeout/fallback, touch targets, branding documentation). [Hosted Android QA workflow 44814338491](https://github.com/levent-ay-platform/sell/actions/runs/44814338491) completed successfully; its map-first APK is 41,965,065 bytes with SHA-256 `70911d4de9d987c0719bf537d3c3542e013accd7eb204127c7b0fed3697917aa`. The preceding Amlak 2.0 QA baseline is `a869e8369c5254133ba5f3181b40f21b1bd7450f`.
- [Hosted Android workflow run 44807351079](https://github.com/levent-ay-platform/sell/actions/runs/44807351079): completed successfully for that QA baseline. Build artifact `app-debug.apk` is 41,962,333 bytes with SHA-256 `2ecd7dc5849fc2740c44229c8bef7055c9558718dd716ceb16a186572b050b4a`.
- **Amlak 2.1 QA installation and failure fallback VERIFIED (2026-11-20):** The new SHA-verified APK (41,965,065 bytes, SHA-256 `70911d4de9d987c0719bf537d3c3542e013accd7eb204127c7b0fed3697917aa`) installed successfully in the dedicated `Amlak2QA_20261120` emulator and launched `tn.malak.app/.MainActivity` (PID observed). The previous debug-key variant on this **disposable guest-only QA AVD** had incompatible signing, so only that QA installation was uninstalled and replaced; original emulator app data was never deleted. After >20 seconds with unavailable map tiles, real UI inspection confirmed French fallback text `Carte indisponible. La liste reste accessible.`, a `Réessayer` action, and visible property list cards. **Retry interaction was also VERIFIED:** pressing `Réessayer` returned to the loading map, then after 23 seconds displayed the same accessible list/failure state again. **Remaining open:** full page-by-page visual acceptance, 130% font-size, RTL regression on the *new* artifact, physical-device testing, and actual map tile rendering. The older 2.0 APK had already passed French map/list/alerts/account and Arabic settings/Back/RTL screenshot checks.
- **Mac worktree reconciliation completed (2026-11-20):** isolated PR checkout `/Users/levent/.codex/worktrees/malak-v2-map-parcel-foundation/amlak pro` was clean and successfully fast-forwarded from `a182a80` to `a3ab6f2` (documentation HEAD at sync). Primary `/Users/levent/Projects/amlak pro` remains on `codex/malak-pilot-complete` / `60b4e37` with untracked `docs/data-platform/` and `tools/` deliberately untouched. Always re-check live HEAD/status before later work.
- Archived source data remains under `/Users/levent/MALAK_TUNISIA_DATA/` (`raw/`, `normalized/`, `derived/`, `reports/`, `private-snapshots/`, SQLite). Files and schemas were not moved or published.
- New local **non-destructive** inventory: `/Users/levent/MALAK_TUNISIA_DATA/reports/housekeeping/2026-11-20/source-inventory.json`.
- Locally verified QA builds: `/Users/levent/Projects/amlak pro/.runtime/qa/Amlak-2.0/2026-11-20/` (older) and `/Users/levent/Projects/amlak pro/.runtime/qa/Amlak-2.1/2026-11-20/` (new APK, ZIP and UI XML evidence). Existing original APK in Downloads retained; `.runtime` is Git-ignored.

## Next safe operator actions

1. On Mac: inspect **both** `git status` and HEAD and compare to the current remote PR head before syncing. Fast-forward only on a clean working tree; do not touch the base worktree's untracked Atlas work.
2. Keep immutable archives in `MALAK_TUNISIA_DATA`; write generated outputs to versioned `reports/` paths with provenance, hashes and real counts. Do not promote older reported counts into newly verified facts.
3. Continue Amlak 2.1 QA: verify Retry and actual MapLibre tiles/network, inspect all AR/FR screens and input/back/navigation at 130% font size, then test a physical Android phone. Installation/build proof is not final visual acceptance.
4. For Atlas data findings and real cadastral-plan coverage, update **Issue #20**; for decisions update **Issue #21**; for implementation/build report to **PR #22**, linking this index.
5. No merges, public publishing, production/database migrations, credential changes, or archive deletions without explicit acceptance.

## Codex handoff

For anyone resuming the Android/QA work, read [CODEX-HANDOFF-2026-11-21.md](CODEX-HANDOFF-2026-11-21.md) **before making changes**. It explicitly lists patches, verified tests, remaining blockers, the current APK, paths and safe Git/Atlas rules. Codex does not automatically inherit this conversation; it must read the repository handoff.

## Workspace directions

See [WORKSPACE-LAYOUT.md](WORKSPACE-LAYOUT.md) for the difference between original archives, repo code, local QA output and the separate control-plane bridge. AI Agent CTR lives in `levent-ay-platform/ai-agent-ctr` [Issue #69](https://github.com/levent-ay-platform/ai-agent-ctr/issues/69) with canonical device `LEVENT-MAC`; control status is not inferred from Desktop Commander inventory.
