# Amlak | أملاك — Tunisia map-first property platform

> **QA RELEASE BLOCKER (2026-11-20):** Amlak 2.0 [Draft PR #22](https://github.com/levent-ay-platform/sell/pull/22) has a successful Android CI build, but **installation and visual acceptance are NOT proven**, and nothing has been merged or released to production. The older visual-blocker history remains documented in [issue #7](https://github.com/levent-ay-platform/sell/issues/7).

> **Official consumer name:** **Amlak (أملاك)**, formerly Malak. Repository slug `sell`, Android application ID `tn.malak.app`, and historical code paths are retained for compatibility; do not rename them ad hoc.

Amlak is a Tunisia-focused property discovery and listing platform with a **2D map-first experience**, optional, provenance-aware Atlas layers and server-guarded listings. The approved launch is sales-only and initially closed to Nabeul/Kélibia. Some QA fixtures are synthetic and must not be presented as public verified properties.

## Start here — authoritative sources

- [Current verified checkpoint and known blockers](docs/CURRENT.md)
- [Mac and GitHub workspace/file layout](docs/WORKSPACE-LAYOUT.md)
- [ATLAS MASTER Issue #20](https://github.com/levent-ay-platform/sell/issues/20) — **official cadastral-plan discovery is the primary Atlas document goal**; INS/PAU/GeoPAU are supporting geospatial resources
- [Approved product architecture Issue #21](https://github.com/levent-ay-platform/sell/issues/21)
- [Android/map implementation Draft PR #22](https://github.com/levent-ay-platform/sell/pull/22)

## Core principles

- **Verification before public visibility.** A verified badge is scoped to an immutable property/ownership snapshot; it is never a client-editable boolean.
- **Different records for property, parcel, ownership claim, evidence, verification, and listing.**
- **Tunisia first:** Arabic and French from day one, TND, m², +216, Tunisian geography and practical low-bandwidth UX.
- **Public source code is not public user data.** Never commit identification documents, title deeds, real addresses linked to people, access tokens, signing files, production data or verification evidence.
- **Android APK first,** with reusable backend contracts for potential iOS/web support.
- **GitHub-hosted CI only:** `ubuntu-latest` or the pinned `ubuntu-24.04` LTS fallback during runner image outages; no Mac/self-hosted runners. No deployment or signing secrets in PR workflows.
- **No brokerage payments, deposits or ownership transfer in V1.**

## Foundation decisions (historical baseline; see Issue #21 for the current approved map-first direction)

| Concern | Initial direction |
| --- | --- |
| Android client | Kotlin + Jetpack Compose; validate map feasibility in a focused spike |
| Globe/map | Native Mapbox spike, MapLibre alternative, graceful list/map fallback |
| Backend | TypeScript modular monolith; Fastify preferred after initial spike |
| Database | PostgreSQL + PostGIS |
| Public listings | Versioned immutable verification scope, server-enforced publication gate |
| Evidence | Private encrypted object storage, least-privilege reviewer access |
| Admin | Separate role-restricted web app |
| CI | Standard GitHub-hosted Ubuntu runner with read-only token |
| Rollout | Tunisia-focused closed pilot, then gradual expansion |

These are engineering defaults, not proof of performance, privacy compliance, or completed integration.

## Roadmap (historical foundation; see Issue #21 for the current implementation plan)

1. Freeze product constraints, data model, security and verification invariants.
2. Prove map/globe rendering on real Android devices, including a low-end device.
3. Implement the data model, verification transition service and SQL constraints with adversarial tests.
4. Build limited Android buyer/seller flows and reviewer console.
5. Private pilot using only approved real-world documents outside this public repository.

See `docs/` for the design records, `db/` for initial schema, and `.github/workflows/` for CI.

## Engineering workflow

- Default branch: `main`. All features go through short-lived branches and PRs.
- Do not run production deployment from pull requests.
- Do not use `pull_request_target`, `workflow_run` with untrusted code or self-hosted runners.
- CI runs on GitHub-managed Ubuntu (`ubuntu-latest` or approved `ubuntu-24.04` LTS), without accessing secrets.
- Keep verification logic server-side and make all decisions auditable.

## License

No software license is granted by this repository unless a `LICENSE` file is added. Public visibility alone does **not** permit reuse. Third-party dependencies must retain their own licenses.
