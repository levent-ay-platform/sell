# Malak / ملاك — Android seller OCR vertical MVP (experimental)

This batch prioritizes one customer-facing journey. It is **not** a complete deployed application or a verification/identity product. The repo is public; no CIN, real CPF scans, session tokens, live owners, secret keys or production docs may be committed or sent through public CI.

## Real code path

1. Android Kotlin/Compose seller workflow: land title, geographic area, declared m², TND price, declaration of capacity, system PDF/photo selection. User initiates OCR explicitly (never background file upload).
2. Only in a **debuggable QA APK**, an internal tester may enter a disposable **HTTPS-only** endpoint, an existing server-issued case UUID and an expiring bearer session token. These QA inputs are transient in memory; a release build never displays this test session form. This is not a phone-login substitute; OTP and session lifecycle are not implemented.
3. Android `SellerPrivateOcrClient.kt` streams selected content via fixed-length HTTPS POST `/internal/v1/evidence/:caseId/upload-ocr`. It rejects cleartext URLs/redirects, bounds upload/response sizes, passes idempotency key and declared area (untrusted). Only synthetic documents are permitted in the QA environment.
4. The **unmounted** Node HTTP handler authenticates before upload, denies cookies/queries/malformed or duplicate headers, uses PostgreSQL atomic throttle with an **OCR** route bucket and records denied attempts without tokens/IP/documents in audit.
5. The existing mandatory upload use case must malware-scan, encrypt with a fresh per-file Vault DEK, and commit a private evidence receipt. The new OCR route calls the analyzer *only after a successful upload*; the analyzer authenticates the owner and checks current SQL consent/case state **before and after** the OCR process.
6. The worker runs the existing Python/PDF/Tesseract OCR in a fixed, limited, private temporary directory; no raw OCR output is logged or sent publicly. The TypeScript parser and `ocr-presentation.ts` turn untrusted text into an **owner-only** field DTO: label AR/FR, value, page, source, confidence, quality, missing pages and numeric area comparison. All returned data is `ownershipVerified:false,mayPublish:false`, and stage-2 identity/CPF status remains `NOT_STARTED`.
7. Android renders actual OCR values for each selected file, with uncertainty and provenance. A clearly labelled **synthetic example** remains available for a UI demonstration without any real document.

## Evidenced acceptance and limits

- A disposable localhost Node HTTP integration uses a **generated fictional PDF**, the actual `uploadIndividualEvidence` AES-GCM sealed private filesystem, the actual Python PDF text extraction (and Tesseract fallback where needed), real structured OCR extraction, and owner-only JSON response. Scanner/KMS/case DB are injected test doubles **in this specific integration**, while separate existing CI gates test real disposable PostgreSQL, real clamd and Vault DEV. Do not claim all production dependencies were tested together.
- Test: an invalid bearer is denied and audited; revoked consent blocks returning fields; a stored sealed private file contains no cleartext; OCR temporary files disappear. The new route returns 202 `ocrStatus:UNAVAILABLE` when extraction fails after a successful upload, never a fabricated result.
- The HTTP module **still opens no port itself** and no public API or Huawei service is deployed. Future production needs actual sessions/OTP, explicit case creation/consent APIs, private TLS reverse proxy, real roles, bounded per-service CPU/RAM, malicious document isolation, backups and a live key authority. Do not test real docs in a CI runner or public synthetic pilot.
- Android QA APR build/compilation is not a substitute for install, actual TLS network tests, layout screenshots at 360/412dp or approved home visual release gate Issue #7. Any appearance of professional OCR must remain factually honest.
- **Stage 2 (separate):** compare identity with current CPF certificate owner, official title references, shares and rights via an authorized current source and human reviewer. OCR alone must never approve ownership, customer identity, a sale, or public listing.

## Operator decision gate

Before real-user activation: independently review privacy/consent and a reversible isolated Huawei deployment plan via canonical AI Agent CTR (SELL Issue #15); no CRM, Asterisk, or existing database mutations. PR #14 must remain Draft until separate explicit merge/deploy approval.
