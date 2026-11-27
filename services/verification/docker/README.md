# Malak private OCR — Huawei synthetic canary release gate (2026-11-11)

## Scope, NOT yet production
This plan covers one ephemeral, **synthetic-only** Tesseract/Poppler
validation canary on Huawei. It is **not** the production private evidence
HTTP API, not proof of a signed-in seller account and not approval for real
Tunisian CIN/CPF images. Keep PR #14 Draft and unmerged until separate
explicit approval.

## Proven source state
- Backend comparison accepts only authenticated and consented case-owner
  receipts with linked immutable certificate and ID kinds; encrypted
  objects are verified (GCM tag and SHA-256) before extraction.
- Real disposable localhost HTTP integration tests exercise synthetic
  two-page ID and certificate matching and mismatching; separate isolated
  Postgres/Vault/ClamAV gates exist.
- Android may only initiate upload when a real authenticated
  OwnerEvidenceSession is injected. No consumer QA URL/token inputs.
- Huawei Direct/User Agents recovered 2026-11-11; existing Docker services
  MUST remain untouched. Recheck health, RAM, disk and OCR container count
  immediately before any maintenance window.

## Dedicated **manual** canary
- Files: OcrCanary.Dockerfile and compose.canary.yaml in this directory.
- Mode: no network, no published port, no host volume, no environment
  secrets, no CRM dependency, non-root UID 10001, all capabilities dropped,
  no-new-privileges, read-only filesystem, private bounded tmpfs.
- Initial resource ceilings: 1 CPU, 768 MiB RAM, swap disabled,
  64 PIDs, no auto-restart. These are unverified starting values, not
  production SLOs.
- The image only runs a finite synthetic Python unittest suite (real
  Poppler/Tesseract, Arabic/French models, quality checks) and exits.
  It never serves identity documents to clients.
- Before any canary launch: explicit operator approval, exact SHA, checks
  for >2 GiB *available host RAM* and healthy CRM/MySQL, disk allowance,
  no unintended Docker name/port/volume conflicts. Pause if not satisfied.
- Build during an approved low-traffic period. Build CPU/RAM overhead is
  NOT covered by the runtime container limits, so review build strategy
  separately. Prefer a trusted prebuilt image with locally verified digest.
- Capture exit status, elapsed time, max container working set and
  memory/CPU pressure before/after; scrub logs for only synthetic content.
  Clean only the dedicated canary after acceptance.
- On any regression, stop/delete only the named ephemeral canary. Never
  stop/restart shared Docker Desktop, CRM/MySQL or Asterisk.

## Production gates not met by this canary
1. Mature seller session/OTP and explicit server-stored consent/case APIs.
2. Private HTTPS reverse proxy, least privilege and document retention law review.
3. Durable private object storage, real Vault keys, real ClamAV, Postgres
   schema isolation, O(1) concurrency limit and total job timeout/queue.
4. Fail-closed OAuth/phone change, revoked consent after OCR, privacy audit
   without full names/CIN in logs; avoid real documents in public GitHub.
5. Real Android phone QA for upload certificate and both CIN sides,
   cancelled/offline/retry, Arabic RTL/French LTR, accessible status and
   final preliminary comparison report.
6. Official CPF review and identity-document authenticity remain separate;
   never show a legally verified ownership badge due to OCR match.
