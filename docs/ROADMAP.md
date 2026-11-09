# ملاك milestone plan

## F0 — Repo foundation (this PR)
- [ ] Document product invariants, architecture, Tunisia conventions and trust model.
- [ ] Add baseline PostGIS migration and a DB test that proves publication blocking.
- [ ] Add GitHub-hosted Ubuntu-only CI with least privilege and no deployment/secrets.
- [ ] Open reviewable PR; no production keys or user data.

## F1 — Vertical proof (before mobile feature expansion)
- [ ] Define API contracts for anonymous discovery and seller submission.
- [ ] Implement authentication/roles and assigned-case restrictions with adversarial tests.
- [ ] Implement revisions, claims and one/multi-property listing approval atomically.
- [ ] Private evidence upload pipeline with encryption, retention and reviewer-only access.
- [ ] Geo queries: bbox, pagination, clustering; benchmark with synthetic 100k locations.

## F2 — Native Android technology spike
- [ ] Compare Mapbox Native Globe vs MapLibre fallback with actual device measurements.
- [ ] Measure first frame, jank/FPS, memory, battery, app restart and cellular latency.
- [ ] Compare rural Tunisia map data coverage and licensing.
- [ ] Freeze client/map technology; retain list-first accessibility.

## F3 — Closed pilot
- [ ] Buyer anonymous browsing/map, listing page, search, contact permission.
- [ ] Seller draft/submission, evidence upload and review status.
- [ ] Reviewer console, suspend/appeal/audit.
- [ ] Arabic/French quality, security review, app signing/recovery, monitoring, backups.
- [ ] Release only after G1-G5 approvals.

## Guardrails
One coherent feature set per PR; tests matched to touched risks, not blanket reruns. No launch or deployment without explicit approval. Avoid integrations with existing production systems.
