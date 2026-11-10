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
