# ملاك | MALAK — Trusted Property Discovery in Tunisia

> **VISUAL RELEASE BLOCKER (2026-11-09):** The first Android QA APK did **not** match the approved visual reference. It compiles, but is NOT visually approved and MUST NOT be described as finished. See issue #7 and `docs/design/VISUAL-RELEASE-BLOCKER.md`.
>
> **Status:** platform foundation merged; Android prototype remains unapproved. No production listing or release is live.
>
> **Public product name:** **ملاك**. The GitHub repository retains the technical slug `sell` to preserve links, database migrations, and GitHub Actions history.

ملاك is an owner-to-buyer real-estate marketplace for Tunisia: properties and land plots are published only after a server-side verification decision; buyers browse a globe/map or a fast list and contact authorized sellers directly.

## Core principles

- **Verification before public visibility.** A verified badge is scoped to an immutable property/ownership snapshot; it is never a client-editable boolean.
- **Different records for property, parcel, ownership claim, evidence, verification, and listing.**
- **Tunisia first:** Arabic and French from day one, TND, m², +216, Tunisian geography and practical low-bandwidth UX.
- **Public source code is not public user data.** Never commit identification documents, title deeds, real addresses linked to people, access tokens, signing files, production data or verification evidence.
- **Android APK first,** with reusable backend contracts for potential iOS/web support.
- **GitHub-hosted CI only:** `ubuntu-latest`; no Mac/self-hosted runners. No deployment or signing secrets in PR workflows.
- **No brokerage payments, deposits or ownership transfer in V1.**

## Foundation decisions

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

## Roadmap

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
- CI runs on GitHub-managed `ubuntu-latest`, without accessing secrets.
- Keep verification logic server-side and make all decisions auditable.

## License

No software license is granted by this repository unless a `LICENSE` file is added. Public visibility alone does **not** permit reuse. Third-party dependencies must retain their own licenses.
