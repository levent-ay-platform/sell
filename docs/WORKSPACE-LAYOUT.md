# Workspace layout — Amlak & Atlas Tunisia

The structure below is a **logical layout and verified path inventory**, not instructions to move or delete existing sources. Read [CURRENT.md](CURRENT.md) first, then use the linked authoritative issues.

| Place | Purpose | Handling |
| --- | --- | --- |
| `/Users/levent/Projects/amlak pro/` | Primary Mac checkout on the historical pilot branch; contains local Atlas acquisition work | Keep original `tools/` and `docs/data-platform/` untracked changes intact. Never `git clean`, `reset --hard` or overwrite. |
| `/Users/levent/.codex/worktrees/malak-v2-map-parcel-foundation/amlak pro/` | Isolated draft PR #22 Android/map/parcel implementation | Fast-forward only if clean, after checking GitHub branch head. |
| `/Users/levent/MALAK_TUNISIA_DATA/raw/` | Source originals / acquired data | Preserve; no destructive relocation, no publishing private originals. |
| `/Users/levent/MALAK_TUNISIA_DATA/normalized/` | Normalization/intermediate data | Preserve source links and identifiers. |
| `/Users/levent/MALAK_TUNISIA_DATA/derived/` | Materialized derived databases / geometry | Retain originals and provenance; derived is not proof of official cadastre. |
| `/Users/levent/MALAK_TUNISIA_DATA/private-snapshots/` | Existing protected backups | Do not delete, expose or automatically re-copy. |
| `/Users/levent/MALAK_TUNISIA_DATA/reports/` | Source inventories / reproducible reports | New 2026-11-20 inventory at `housekeeping/2026-11-20/source-inventory.json`. |
| `/Users/levent/Projects/amlak pro/.runtime/qa/Amlak-2.0/2026-11-20/` | Local verified QA APK, manifest and install notes | Ignored by Git. Contains copy, **not** a release; do not commit APK. |
| `/Users/levent/Downloads/` | Existing original downloaded APK/ZIP and user files | Keep intact; remote agent may lack directory-list permission. |
| `docs/CURRENT.md` (PR branch) | One cross-scope status index | Use Issue #20 for Atlas truth, Issue #21 for product decisions, PR #22 for implementation evidence. |

## Naming

- **Amlak** = customer-facing Android name. **Malak / MALAK** = legacy paths, Kotlin classes, Android package, older documentation; rename only with a migration plan.
- `sell` = existing GitHub repository slug, retained to keep history and URLs.
- `tn.malak.app` = existing Android package identifier, retained for installation/data continuity.
- Do not describe synthetic fixture listings, unverified GeoPAU geometries, or unrelated PAU PDFs as official georeferenced cadastral parcels.

## Safe operating discipline

1. Snapshot Git branch/HEAD, porcelain status, and current PR SHA before changing files.
2. Back up only via approved mechanisms. SQLite originals and private snapshots are not ordinary project files; do not commit or relocate them.
3. Label data: `DISCOVERED`, `DOWNLOADED_VERIFIED`, `GEOMETRY_VERIFIED`, `OFFICIAL_CADASTRAL_PLAN_VERIFIED`, or `UNKNOWN` as supported by actual evidence. Never promote claims across categories.
4. Name generated reports by date/run; include source and checksum when available. Distinguish QA build success, install proof, visual inspection and production release as separate gates.
5. Keep PRs draft until acceptance. No Atlas archive writes from Android QA. Don't use the GitHub issue command bridge as a project document repository.

## Decision/report routing

- Atlas and official plans: [Issue #20](https://github.com/levent-ay-platform/sell/issues/20).
- App contract and brand direction: [Issue #21](https://github.com/levent-ay-platform/sell/issues/21).
- Native code and CI: [PR #22](https://github.com/levent-ay-platform/sell/pull/22).
- Current index: [CURRENT.md](CURRENT.md).
