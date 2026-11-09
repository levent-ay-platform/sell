# ADR-0001: Foundation and release boundaries

Date: 2026-11-09
Status: Accepted for initial engineering baseline (implementation pending)
Context: Tunisia-first owner-to-buyer verified property marketplace, public GitHub source, Android APK priority, no Mac runners.

## Decision
1. Standalone repository and system, not coupled to an existing CRM.
2. Android-native Kotlin + Jetpack Compose is default, pending an explicit 3D-globe performance spike.
3. TypeScript modular-monolith API + PostgreSQL/PostGIS, not early microservices.
4. All listings derive visibility from scoped server verification; no writable verified flag.
5. All documents and identity information reside in private storage and are excluded from GitHub.
6. CI runs solely on standard GitHub-hosted ubuntu-latest, with least-privilege, no secrets, no self-hosted agents.
7. No production deploy/keystore from untrusted PR workflow. Signing will use separately configured trusted environment with approvals.
8. Pinned test fixtures synthetic only. Use a risk-based test matrix rather than repeating unrelated tests.

## Trade-offs
- Kotlin increases initial iOS effort but lowers native globe integration risk.
- Mapbox may introduce vendor costs; preserve MapLibre fallback and a usable list experience.
- Manual review slows verification but preserves human accountability.
- Public repo reduces CI expense, increases supply-chain and credential-exposure sensitivity.
- Hosted Ubuntu can build Android; it cannot build/sign iOS apps, which are out of V1 scope.

## Later gates
G1 executable PostGIS verification constraints and tests; G2 3D globe on low-end phone; G3 safe storage/roles; G4 Ubuntu Android release-signing custody; G5 pilot evidence and metrics.
