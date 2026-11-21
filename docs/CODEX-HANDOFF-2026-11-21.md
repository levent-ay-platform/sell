# Codex handoff — Amlak 2.1 QA (2026-11-21)

**Read this before starting or resuming Amlak Android work.** Do not assume Codex automatically inherited ChatGPT tool state or uncommitted Mac files. First read [CURRENT.md](CURRENT.md), then [Issue #21](https://github.com/levent-ay-platform/sell/issues/21), [Draft PR #22](https://github.com/levent-ay-platform/sell/pull/22), and [ATLAS MASTER Issue #20](https://github.com/levent-ay-platform/sell/issues/20) for the separate cadastral mission.

## Changes actually committed

- Android code patch **`32a98f02860d45e86a0927d9843d1d6ba173348e`**: `ExploreScreen.kt` has an 18-second bounded wait for MapLibre style readiness, visible progress, user-accessible list fallback, Retry routed back into map mode, and larger toolbar/list-switch touch targets.
- Design docs `a2bed087c095b32cb4ba21137963661238ef9284`: brand consistently **Amlak / أملاك**, not Malak; legacy Android application ID `tn.malak.app` intentionally unchanged for compatibility.
- Last **implementation CI-tested** commit: `a2bed087c095b32cb4ba21137963661238ef9284`. [Android hosted workflow 44814338491](https://github.com/levent-ay-platform/sell/actions/runs/44814338491) **SUCCESS**. The current PR head may be later documentation-only commits; distinguish these from an independently rebuilt executable.
- Map-first QA APK: **41,965,065 bytes**, SHA-256 `70911d4de9d987c0719bf537d3c3542e013accd7eb204127c7b0fed3697917aa`, GitHub artifact ID `14667524829`. Local Mac QA `/Users/levent/Projects/amlak pro/.runtime/qa/Amlak-2.1/2026-11-20/Amlak-2.1-QA.apk`.
- Local Mac repo worktree: `/Users/levent/.codex/worktrees/malak-v2-map-parcel-foundation/amlak pro`. The separate base checkout `/Users/levent/Projects/amlak pro` contains **untracked** `docs/data-platform/` and `tools/` — never clean/reset/overwrite them. Archive `/Users/levent/MALAK_TUNISIA_DATA/` must remain untouched.

## What was observed live

- Dedicated, disposable Android AVD `Amlak2QA_20261120` (`emulator-5566`) fully booted, accepted the Amlak 2.1 APK after replacing an **incompatible-signature QA package on that isolated AVD only**, and launched `tn.malak.app/.MainActivity`.
- The map itself did **not** visually render tiles in the first QA observation. On the new APK, after >20s, UI XML showed the actual French fallback **“Carte indisponible. La liste reste accessible.”**, **“Réessayer”**, and multiple labeled synthetic listing cards. Tapping Retry re-entered map loading and returned to the fallback/list after 23s. This is a confirmed **graceful failure behavior**, not proof map tile networking is fixed.
- Previous Amlak 2.0 APK (not necessarily the latest binary) had French map/list/alerts/account screenshots and Arabic settings/RTL/system Back inspected on emulator. UI source static checks `scripts/check_amlak_ui_contrast.py` and `scripts/check_malak_brand.py` passed on Mac at code commit `a2bed08`.
- **NOT YET ACCEPTED:** full latest-APK Arabic/FR screen coverage, 130% font-scale layout, keyboard/input behavior, physical device, and actual MapLibre base-map tiles. Do not claim full visual approval or live public listing data.

## Exact next actions for Codex

1. Check GitHub remote PR head vs both local worktrees: `git status --porcelain`, `git rev-parse HEAD`. Fast-forward **only** clean PR worktree, do not rebase/reset/clean or touch Atlas/production.
2. Diagnose missing map tiles and provider/network/SSL/MapLibre render logs; preserve the existing verified failure-to-list UX. Use real screenshots and evidence, not a simulated success flag.
3. Test actual latest Android APK on the disposable QA AVD in Arabic RTL and French LTR across map, list, detail, seller, PDF, alerts, account/settings and system Back, including 130% font, offline, IME keyboard overlap, and 360/412dp widths. Record pass/fail per screen and do not fabricate backend workflows.
4. Preserve privacy/security: seller ID documents private; cadastral-plan provenance belongs to [Issue #20](https://github.com/levent-ay-platform/sell/issues/20). PAU zones and user-drawn outlines cannot be presented as legal cadastral boundaries.
5. Update [CURRENT.md](CURRENT.md) and PR #22 with exact commit, tests, APK hash, screenshots, and remaining blockers. **Draft PR only. Do not merge or deploy without explicit approval.**

No task in this handoff authorizes production publishing, unverified official-plan claims, destructive data cleanup or unrelated CRM changes.
