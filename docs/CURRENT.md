# CURRENT — Amlak / Atlas Tunisia
_Last audited: 2026-11-20 (Europe/Istanbul). This is the navigation and checkpoint index, not a replacement for the authoritative issue records._

## Source-of-truth ownership (do not create competing master plans)

| Scope | Authoritative source | Rules |
| --- | --- | --- |
| Official Tunisian cadastral plans, land-title/parcel document inventory, Atlas acquisition, provenance, INS / PAU / GIS coverage | [ATLAS MASTER — Issue #20](https://github.com/levent-ay-platform/sell/issues/20) | **Official cadastral plans are the primary document goal.** PAU/INS layers are supporting datasets, not proof of legal parcel boundaries. UNKNOWN is not zero. Preserve raw evidence. |
| Consumer product experience, map-first 2D architecture, data/security/publication contract | [Product blueprint — Issue #21](https://github.com/levent-ay-platform/sell/issues/21) | Approved Tunisia 2D map-first with separately toggleable verified Atlas layers; sales-only, closed initial Nabeul/Kélibia launch; keep crimson/pearl palette. |
| Android implementation and test evidence | [Draft PR #22](https://github.com/levent-ay-platform/sell/pull/22) and its checks | Work in the PR branch until accepted; not merged and not deployed. |
| Single-property cadastral lookup | [Issue #19](https://github.com/levent-ay-platform/sell/issues/19) | Separate from aggregate Atlas coverage. |
| Older seller OCR flow | [Draft PR #14](https://github.com/levent-ay-platform/sell/pull/14) | Do not assume merged or deployed. |

**Approved public app name: Amlak.** Earlier code/paths use MALAK/Malak for historical compatibility. The existing Android package identifier `tn.malak.app` was deliberately not renamed to avoid data loss; technical slugs are not consumer branding.

## Verified 2026-11-20 checkpoint

- **Amlak 2.1 tested implementation commit:** `a2bed087c095b32cb4ba21137963661238ef9284` (map load timeout/fallback, touch targets, branding documentation). [Hosted Android QA workflow 44814338491](https://github.com/levent-ay-platform/sell/actions/runs/44814338491) completed successfully; its map-first APK is 41,965,065 bytes with SHA-256 `70911d4de9d987c0719bf537d3c3542e013accd7eb204127c7b0fed3697917aa`. The preceding Amlak 2.0 QA baseline is `a869e8369c5254133ba5f3181b40f21b1bd7450f`.
- [Hosted Android workflow run 44807351079](https://github.com/levent-ay-platform/sell/actions/runs/44807351079): completed successfully for that QA baseline. Build artifact `app-debug.apk` is 41,962,333 bytes with SHA-256 `2ecd7dc5849fc2740c44229c8bef7055c9558718dd716ceb16a186572b050b4a`.
- **Emulator installation now proven for Amlak 2.0 baseline:** `Amlak2QA_20261120` completed boot and accepted the SHA-verified APK, the main Activity launched, and the app process was observed. French map/list/alerts/account and Arabic settings/Back/RTL were exercised with locally captured UI XML and screenshots. The MapLibre map background was visually blank at first check, leading to the Amlak 2.1 fallback patch. **New 2.1 artifact installation verification and physical-device / full screen / 130% font-scale approval are separate gates; not claimed by build success.** The original emulator's app data was not deleted.
- **Mac worktree reconciliation completed (2026-11-20):** isolated PR checkout `/Users/levent/.codex/worktrees/malak-v2-map-parcel-foundation/amlak pro` was clean and successfully fast-forwarded from `a182a80` to `a3ab6f2` (documentation HEAD at sync). Primary `/Users/levent/Projects/amlak pro` remains on `codex/malak-pilot-complete` / `60b4e37` with untracked `docs/data-platform/` and `tools/` deliberately untouched. Always re-check live HEAD/status before later work.
- Archived source data remains under `/Users/levent/MALAK_TUNISIA_DATA/` (`raw/`, `normalized/`, `derived/`, `reports/`, `private-snapshots/`, SQLite). Files and schemas were not moved or published.
- New local **non-destructive** inventory: `/Users/levent/MALAK_TUNISIA_DATA/reports/housekeeping/2026-11-20/source-inventory.json`.
- Locally verified QA copy + manifests: `/Users/levent/Projects/amlak pro/.runtime/qa/Amlak-2.0/2026-11-20/`. Source ZIP/APK in Downloads retained. `.runtime` is Git-ignored.

## Next safe operator actions

1. On Mac: inspect **both** `git status` and HEAD and compare to the current remote PR head before syncing. Fast-forward only on a clean working tree; do not touch the base worktree's untracked Atlas work.
2. Keep immutable archives in `MALAK_TUNISIA_DATA`; write generated outputs to versioned `reports/` paths with provenance, hashes and real counts. Do not promote older reported counts into newly verified facts.
3. Install the Amlak 2.1 APK on the isolated, disposable QA emulator; verify the timeout/list fallback and visual screenshots, test every AR/FR page at regular and 130% font scale, and then physical-device QA. Passing build is not visual acceptance.
4. For Atlas data findings and real cadastral-plan coverage, update **Issue #20**; for decisions update **Issue #21**; for implementation/build report to **PR #22**, linking this index.
5. No merges, public publishing, production/database migrations, credential changes, or archive deletions without explicit acceptance.

## Workspace directions

See [WORKSPACE-LAYOUT.md](WORKSPACE-LAYOUT.md) for the difference between original archives, repo code, local QA output and the separate control-plane bridge. AI Agent CTR lives in `levent-ay-platform/ai-agent-ctr` [Issue #69](https://github.com/levent-ay-platform/ai-agent-ctr/issues/69) with canonical device `LEVENT-MAC`; control status is not inferred from Desktop Commander inventory.
