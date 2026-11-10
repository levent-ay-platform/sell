# Malak F1 — natural-person property evidence preflight

**Draft domain core, not a live document upload API.** The repository is public: do not commit owner names, identity images, title numbers, photos of deeds, QR contents or access keys.

The first phase is **individuals only** and supports both legacy paper CPF ownership certificates (without QR) and modern electronically signed CPF certificates (CEV/QR). A valid QR alone does NOT prove the person currently owns the parcel or has the unrestricted right to sell. The shared sample document is NOT included here. Every test uses purely fictional values.

## Modules

- `parseCpfOcrText`: conservative Arabic/Arabic-Indic digit parsing of **already extracted OCR text** (it does not run OCR on PDF/image). Extracts page numbering, title reference, area and ownership share units separately, leaves missing/ambiguous fields unknown, and never converts the `التجزئة` line to an authoritative ownership denominator. Never process real claimant data in CI or repository logs.
- `triageIndividualClaim`: consumes extracted metadata from a future OCR/document pipeline (not a raw PDF) and routes to missing-page collection, official-source comparison or specialist legal review. Never mistakes `ownershipShareUnits` (أجزاء) for `areaSquareMetres`, nor historical registry-update date for issuance date. Missing QR is not evidence of forgery.
- `verifyTunTrustCev`: server-side HTTPS POST of `{"value":"DC..."}` to TunTrust's observed responsive `validateDoc` service. No PDF upload; fixed endpoint; timeouts; exact trusted CPF issuer allowlist (unknown, so defaults fail-closed); malformed responses cannot approve. Even a genuine signature yields `SIGNATURE_VALID_CONTENT_NOT_YET_MATCHED` until document-field comparison. This adapter is not wired to real users.

## Test

```sh
node --experimental-strip-types --test services/verification/test/*.test.ts
```

## Before any real identity or ownership document is processed

1. Build authenticated +216 seller/assigned-reviewer APIs, private consent, legal privacy/storage-region review.
2. Private encrypted object store, full-page PDF/image intake, file scanning, SHA256 immutable evidence bind, short-lived audited reviewer access, deletion/retention.
3. OCR/field parsing with confidence and human-confirmed document type. Extracted fields remain untrusted until checked against an authenticated official source.
4. Obtain officially sanctioned TunTrust positive CPF QR sample, actual issuer certificate/value allowlist, data schema, API usage rules and trust/revocation tests.
5. Validate fresh CPF ownership/title and pending operations through authorized means. No public live CPF read API has been confirmed.
6. Reviewer decision with immutable case/snapshot and auditable proof, protected by existing PostGIS publication guard, then Android upload/status UI.

No companies, real personal documents, production deployment, unreviewed automatic approvals or self-hosted/Mac runners. This is only a small, isolated starting point for F1.
