# Malak F1 — individual-property verification preflight (prototype)

This is an **isolated TypeScript domain core**, not a live seller API or a production document uploader. It uses only synthetic fixtures and never stores images, identity details, QR contents or deed scans in the repository. Real proof depends on CPF data, private secure evidence capture and a qualified reviewer decision.

- `triageIndividualClaim()` classifies scans/CEV, checks page completeness (e.g. `صفحة 1 من 2`), distinguishes ownership share units from square metres, refuses to mistake registry update date for certificate issue date, and asks for fresh official evidence. No QR is **not** considered fraud.
- `verifyTunTrustCev()` is a separate server-side HTTPS-only adapter for the `validateDoc` endpoint. Connection/negative-QR response was proved in an earlier synthetic GitHub Actions probe; the adapter uses injected fetch to test response parsing offline. The exact official CPF issuer allowlist and live successful CPF example remain **unknown**, so default is fail-closed and `result: true` NEVER means ownership verified.
- The only output from this preflight is a route + issues. Publication is **always false**. There is intentionally no endpoint or unauthenticated upload and no code that can bypass the existing SQL public listing gate.

## Reproduce locally (Node.js >=22.6)

```sh
node --experimental-strip-types --test services/verification/test/*.test.ts
```

## What is needed before processing real documents?

1. Build authenticated +216 OTP seller/assigned-reviewer API with strict record-level authorization, storage-region and privacy review.
2. Private encrypted uploads (full multi-page PDF/image), malware/file integrity checks, secret-free object identifiers, SHA-256 evidence digest, object-access audit and retention rules. Never log or commit raw docs.
3. OCR as an **untrusted input extractor** with a human-checkable field bounding box and confidence; the current module accepts extracted data from a future pipeline, not the image file itself.
4. TunTrust authorized positive test and documented CPF issuer identity + signed field formats, then cross-check against entire PDF/physical document (beware copied genuine QR).
5. Independently obtain recent CPF title/ownership, pending transactions, share restrictions and parcel/subdivision status via authorized official channels. Old paper evidence on its own is never current ownership proof.
6. Authorized reviewer outcome bound to immutable source snapshot and evidence, then SQL-gated publication; legal changes suspend automatically. Add Android seller flow and reviewer console with AR/FR support.

No companies in phase 1. Inheritance/mandate/unregistered title requires a legally reviewed lane. This PR is not proof of source registry verification or a completed pilot.

## High-precision / fast post-OCR field engine (bounded V2)

`src/smart-fields.ts` runs an O(text length) single-pass **post-OCR** extractor on Arabic/French text lines with source, page and confidence metadata. It understands Arabic-Indic digits, date validity, `page X of Y`, title reference, area m² vs shares in `جزء`, explicit share totals vs `التجزئة`, parcel count vs parcel number, and repeated-field conflicts. Each result has provenance (page, input line, OCR score, source) and `CANDIDATE`, `LOW_CONFIDENCE`, `CONFLICT`, or `ABSENT` status. It does not invent missing characters or infer legal rights; contradictions trigger a NEEDS_INFORMATION lane.

**Important:** This is *not yet* a photo/PDF OCR engine. The slowest and most accuracy-sensitive portion (preprocessing and model inference) is still to implement. We will not claim an accuracy percentage or sub-second document reading without real benchmark evidence. Only fictional test fixtures are shipped; the user's scanned certificate and ID must never enter the public repo/CI.

### Next OCR engine spike / performance acceptance

1. Signed PDF with embedded text: extract the native text without OCR when possible. Do not trust it for legal validity until official signature/source validation.
2. Image-only PDFs/scans: local/private page rasterization, orientation, cropping, quality inspection (blur/glare/page cut-off); no third-party data transmission. Evaluate **PaddleOCR Arabic PP-OCRv5** and French/Latin recognition versus Tesseract `ara+fra` baselines on permissioned/redacted Tunisian documents. Record page latency, model cold start, p95, memory and exact-field accuracy separately for Arabic text and numeric fields. Official PaddleOCR languages: https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/OCR.html ; Tesseract fast/best: https://tesseract-ocr.github.io/tessdoc/Data-Files.
3. One pass for clear documents; fallback re-scan ONLY low-confidence or conflict regions. Never auto-correct owner identity, title ID or registered shares with a language model. Require human correction + official source cross-check.
4. Feed word/line boxes and confidence into `extractCpfSmartFields`; reject page gaps/conflicts, preserve immutable evidence digests and engine/rule versions. Image OCR must run only inside the approved private data environment, via bounded queues with hard CPU/memory/time limits.
5. Establish real measurement targets on anonymized/permissioned field-level corpus **before** setting claims of throughput/accuracy. Initial safety gate: **zero silent ownership approvals** for missing/contradictory title, identity, legal share or stale documents. Compare performance of digital PDFs, clear phone photos and degraded scans independently.

Synthetic text parser benchmark (not OCR inference):

```sh
node --experimental-strip-types services/verification/scripts/bench-smart-fields.mjs
```

## F1 real image/PDF OCR worker — synthetic spike, no private upload API

- `ocr/ingest.py`: detects PDF/PNG/JPEG/TIFF by file magic, rejects oversized/invalid inputs and symlinks, limits PDF page count to 8, uses native PDF text extraction **per page** where meaningful, and falls back to Poppler rasterization + Tesseract `ara+fra` for scans. Runs a second *selective* OCR segmentation pass if the first pass misses certificate headings. Caps processing time, page dimensions and text size. Returns OCR lines with source/page/confidence in JSON that **can contain personal text**; only run under an approved isolated private service, never in public logs or on the public GitHub repo with customer files.
- `src/reader.ts`: validates the untrusted OCR worker payload and feeds its lines into `extractCpfSmartFields` without permitting ownership approval or publication. Keep official property verification and reviewer decisions separate.
- `test/test_ingest.py`: builds **fictional** Arabic/French image-only scans and native-text PDF at runtime. Checks the text fast path, scanned-image OCR path, partial PDF rasterization, corrupt/oversized inputs, no symlink following, and fail-closed behavior. No real documents in tests.
- GitHub Actions executes the image-OCR smoke tests on hosted Ubuntu with temporary Tesseract language packs and pinned Pillow. Benchmark output records **synthetic** per-document time. This is not a production throughput or real-Tunisian-document accuracy claim.

Critical remaining integration: full field-coordinate bounding boxes, Arabic-RTL reading order calibration, quality rejection for blur/glare/rotations, PaddleOCR Arabic comparator on consented, privately-held redacted documents, native Android consented upload and protected service/storage. Performance and exact-field precision/recall acceptance must be measured on such a corpus before release. Any OCR uncertainty must block silent approval.

## F1 Capture-quality and multipart evidence preflight (2026-11-10)

- `src/capture.ts` is a **server-side contract** for an individual person's private evidence page set. The server must independently determine SHA-256, file magic, size, case authorization and consent; never trust client-supplied hash or legal claims. It blocks malformed/duplicate-page input, missing consent and company accounts. It reports missing numbered pages, unknown printed totals, suspicious quality and requested follow-ups. **It allows private OCR of an existing legitimate page even when page 2 or a printed total is unknown**, so OCR can help discover missing evidence; this is NOT approval to publish or to process without consent.
- `ocr/quality.py` inspects private scan dimensions, contrast, likely blur and EXIF orientation without extracting or exposing personal text. These are advisory screening signals only: ornamental borders, handwriting and old scans can distort quality heuristics. The model never verifies page edges, owner identity, a title, document authenticity or ownership.
- Image and rasterized PDF OCR pages emit optional `qualityWarnings`; `src/reader.ts` validates these as a strict enum, and `src/readiness.ts` can request a better capture while retaining the original evidence. Native PDF text pages do not need image-quality warnings. No real images are in CI.
- **Still not implemented**: a public-facing Android upload UI, authenticated API, encryption-at-rest object storage and retention, scanner/malware checks, reviewer access controls, persistent uploads, actual current CPF rights lookup or legally effective ownership approval. Do not accept real third-party CIN/land-title documents until these controls and consent are ready.

## F1 encrypted evidence upload use-case (Draft, **NOT a public endpoint**) — 2026-11-10

`src/evidence-upload.ts` provides an isolated server-side domain operation for **natural-person verification cases only**. It is NOT wired to Android, an HTTP route, real sessions or a production database. No document bytes are sent to any external service in these tests.

- Enforces trusted server-side authenticated identity and record-level case/party authorization; only PENDING or NEEDS_INFO cases. **No company uploads** in V1.
- Requires **server-recorded, non-revoked, purpose-scoped consent**; checks it before scanning and again while the case is locked.
- Caps input to **12 MiB**; derives file type from magic bytes (PDF, PNG, JPEG, TIFF) instead of trusting client MIME; requires an injected mandatory malware scanner that must return CLEAN. A header match is not full format validation; actual scanner and isolated OCR still required.
- Computes SHA-256 on the server and seals the bytes with **AES-256-GCM**, random 96-bit IV and case/evidence-bound AAD. Requests a **fresh per-file data key from an external KMS adapter** and stores only its wrapped form/key version in private evidence metadata. Do not hardcode a real KMS key or reuse this test fixture's key.
- Writes to an injected private object-store adapter and calls an immutable/transactional evidence metadata repository. Per-case serialization and idempotency prevent duplicate entries on concurrent retries, and an unexpected store/ledger error fails closed. On DB attach failure attempts to remove the encrypted orphan; deployment must additionally reconcile private orphans when cleanup itself fails.
- Returns only an opaque evidence ID, not names, title references, thumbnails, bytes, raw QR or storage URLs. **Always** returns `ownershipVerified:false` and `mayPublish:false`.

**Deployment blockers:** implement and review real session verification, per-case membership SQL query and transactional locking, consent audit & revocation, virus-scan service, isolated OCR queue, true private object store, KMS key wrapping/unwrapping, persistence of wrapped data keys with immutability and audit, scheduled orphan cleanup, retention/deletion obligations, abuse/rate controls, size-limited streaming HTTP ingress, and a server-scoped Android upload/status route. Do not process another person's real CIN/CPF files via this unmounted prototype or store them in this public repository.

**Adversarial synthetic tests:** auth & co-ownership denial, consent revocation during upload, CASE state, replay and concurrency, malicious/unavailable scan, MIME mismatches, file limits, ciphertext integrity/AAD roundtrip, KMS key zeroization and rollback on DB fault. Tests are confined to new domain paths and run in the existing GitHub-hosted Ubuntu TypeScript gate.

## PostgreSQL adapter prototype (individual evidence, synthetic tests only)

`src/postgres-evidence-repository.ts` binds `CaseEvidenceRepository` to the existing `sell` schema through an injected parameterized SQL client/pool. It obtains `SELECT ... FOR UPDATE` locks on the verification case, reads owner membership plus the latest grant/revocation consent, protects replay lookups and inserts evidence, case links, receipts, and audit in one transaction with a savepoint. The transaction is scoped with `AsyncLocalStorage` rather than global mutable state. No SQL credentials, real evidence, browser endpoint or production access are present. This is **not** proof of a functioning production database connection; targeted tests use an in-memory SQL protocol double and the existing disposable PostGIS SQL gate remains separate. Production must inject a restricted DB role/client, authenticated sessions, private KMS/object store, malware scanner and properly bounded isolation/retention before real documents.

## Disposable PostgreSQL end-to-end adapter proof (2026-11-10)

`ci/postgres-live.integration.ts` uses an actual GitHub-hosted PostGIS 17 test service and a temporary CI-only pinned `pg` driver. It exercises the **real** Node repository adapter, synthetic consent/party membership, upload encryption, separate page-2 photo, idempotent retry, conflicting replay, stored evidence/audit and post-revocation denial. No Huawei database, real title, ID, external bucket or real malware scanner is involved. F1 image uploads now permit explicit page indices 1 through 8 rather than only page 1; PDF may carry all pages. The integration test is deliberately excluded from the unit-test glob and guarded with `MALAK_DISPOSABLE_PG=yes`; it only runs after temporary schema migrations on GitHub. The actual private object store/KMS/malware scanner and authenticated HTTP service remain deployment blockers.

## Ciphertext-only filesystem and ClamAV local scanner adapters (2026-11-10)

New `src/local-private-store.ts` and `src/clamav-scanner.ts` are **concrete injected adapters**, not services deployed on Huawei or a usable public endpoint.

- The file store requires an existing **absolute, non-symlink, 0700 private root** outside static/web paths on a Linux/WSL private volume. It creates restricted directories, writes **only** version-marked AES-GCM sealed evidence (ciphertext expected from the upload core), uses `O_EXCL | O_NOFOLLOW`, 0600 file mode and `fsync`, rejects traversal, public permissions, overwrite and symlink file deletion. This is NOT a general encrypted disk or KMS; the enclosing container/service user and mounted volume must be isolated from other processes. It does not create a key or read plaintext itself. Crash-orphan cleanup and actual durability/restore drills remain necessary.
- The scanner uses the clamd `zINSTREAM` protocol against an **explicit Unix socket or loopback TCP**, with bounded input/reply, a fixed wall-clock deadline, and fail-closed `UNKNOWN` on errors, malformed replies, unavailability or timeout. `CLEAN` is accepted only from an exact `stream: OK` daemon response and `FOUND` is treated as `INFECTED`. No remote scanner or document exfiltration is configured. **Real ClamAV deployment, signature freshness and operational health were not tested.**
- Thirteen additional **synthetic** Node tests use an isolated temporary filesystem and a fake local clamd socket to exercise permissions, traversal, tampering, overwrite, response parsing, faults and timeout. CI under GitHub-hosted Ubuntu runs them automatically in the existing targeted unit suite.
- A production-like upload remains disabled: no actual Huawei encrypted volume, per-object external KMS, authenticated HTTP endpoint, real malware service, private reviewed retention/backup policy, protected OCR worker, or live CPF rights source. Do not process real IDs/property certificates through these adapters until those controls and consent are implemented and tested. **No new Docker containers, Huawei commands, CRM mutations, merges or deploys accompany this PR.**

## Real clamd synthetic-only CI acceptance (2026-11-10)

This adds a dedicated GitHub-hosted Ubuntu job `.github/workflows/verification-live-clamd.yml` that installs **real clamd** and connects the existing `LocalClamdScanner` through a private Unix socket to an actual daemon. It uses **one custom fabricated test signature**, generated inside throwaway CI at startup, *not* ClamAV official/Cisco Talos malware signatures or `freshclam`. It proves that the scanner protocol accepts harmless content, flags an inert matching marker, and fails closed on scanner unavailability. Integration test `ci/clamd-live.integration.ts` passes the approved synthetic bytes through `uploadIndividualEvidence` to the actual private 0700 filesystem adapter, verifies the file's 0600 permissions, sealed bytes and AES-256-GCM authenticated recovery with an **ephemeral CI-only key**. It asserts that flagged or unreachable-scanner submissions leave no new evidence. The test itself refuses to run outside a disposable CI environment.

**Not production proof:** a custom signature is not real malware signature freshness, and the CI-only `generateDataKey` is not a KMS provider. No Huawei ClamAV container/service was installed or modified. Before real personal identity or property-title intake: trusted authenticated sessions, real separate KMS/key escrow, operational freshclam updates and health, dedicated isolated storage, retention/backup and OCR document quarantine, plus a legal privacy review. The legacy synthetic OCR/PG gates remain unchanged. Use CTR only for optional read-only capacity checks until explicit separate release approval.
