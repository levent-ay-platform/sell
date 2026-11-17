# Codex handoff — finish Malak SELL Mac OCR integration (2026-11-14)

**Owner's priority: SHIP THE EXISTING END-TO-END OCR FLOW.** Do not restart OCR training, move OCR to Huawei, create extra login flows, expand the architecture or turn this into an open-ended compliance project. Integrate the already working Mac Arabic PP-OCRv5 into the Android app, close concrete defects, and demonstrate one installed-APK result.

## Starting point / proof boundary

- Repository: https://github.com/levent-ay-platform/sell
- Work branch: `feat/individual-property-triage-20261110`; Draft PR [#14](https://github.com/levent-ay-platform/sell/pull/14), **OPEN, UNMERGED**.
- Last read-only source checkpoint at handoff: `53a3eebca578d76f5c289f3c6dfc205d24d82377`. **Recheck HEAD before editing or reporting test results.**
- Blocker history: [Issue #17](https://github.com/levent-ay-platform/sell/issues/17). Its historical instructions for two-sided CIN, pilot OCR denied by shared PIN, Huawei OCR, Android Tailscale, or obligatory legal owner verification **do not override the current product requirements below**.
- **Previously reported, not re-probed by this handoff:** Mac `com.levent.malak.qaocr` at `127.0.0.1:18443` answered health HTTP 200; `https://malak-api.ayasofia.shop/healthz` answered 200 via public HTTPS/Cloudflare/Huawei isolated reverse proxy. Android-to-Mac document comparison was **not** demonstrated.
- Seven prior exact-head CI checks succeeded on an earlier SHA, and a connected QA APK was built; neither demonstrates actual document OCR on a phone. Do not rerun historical suites without a source change needing them.

## Locked product contract (current owner decision)

1. **Mac is the OCR compute host.** Keep the Mac's already installed Arabic PP-OCRv5 model for Tunisian CIN; document OCR/classification/matching must be connected to that Mac-hosted service. Huawei may only remain as the existing HTTPS ingress/routing layer. No OCR rehosting or model bundled into APK.
2. **The existing single PIN `1234` sign-in opens the complete *pilot OCR workspace*.** One server-issued pilot session should support CIN extraction **and** land-document extraction/comparison. No second sign-in, per-document token prompt, app-side fake success or separate owner-account enrollment as a prerequisite for pilot OCR. A shared pilot PIN does **not** prove phone ownership, identity authenticity or legal land ownership; the app must not label it as such.
3. **Tunisian CIN: live camera, FRONT ONLY.** Extract surname, given name, full 8-digit CIN and date of birth if visible. No compulsory back image, gallery picker or identity PDF. If a reference sample deliberately masks CIN/DOB, mark that field unevaluable, not OCR failure. Give immediate clarity/retake feedback before accepting the photo.
4. Land documents may be photo(s) or multi-page PDF. Classify/extract according to **the actual document kind**, not one universal owner/CIN/share form; source-missing optional data is not a failed OCR result.
5. Show *that ID holder's* relevant document role and personal share **only when the document actually prints them**, plus source-backed land fields. Keep preliminary text agreement separate from legal ownership verification.
6. Retain an intelligible AR/FR UI; no repeated disclaimers or consent checkboxes. A clear explicit user action sends files. No public display of original personal documents or unrelated co-owners.

## Concrete P0 defects confirmed in current source

| Defect | Exact source | Necessary closure |
| --- | --- | --- |
| Real v5 output rejected by Mac adapter | `services/verification/ocr/cin_v5.py` returns SIX keys including `cinFull`; `services/verification/qa/mac_pilot_ocr_http.py` declares FIVE `NULL_FIELDS`, then `MemoryPilot.read` requires exact key equality | Align the actual response schema and safe normalization; preserve `cinFull` consistency checks, add a test that calls a six-key v5-shaped engine result (not a misleading five-key FakeOCR). Redeploy/restart only the Mac QA service after tests |
| Missing compare endpoint on Mac | Android `apps/android/app/src/main/java/tn/malak/app/ui/SellerPairClient.kt` posts binary-framed `/internal/v1/pilot/compare-pair`; Mac `mac_pilot_ocr_http.py` accepts only `/pilot/login` and `/pilot/preview-cin` | Mount a real compare handler on the Mac QA runtime and honor Android's existing binary-frame contract; call the actual land-document reader and role-aware matching logic, not mocked summaries. Use existing `services/verification/src/private-evidence-http.ts`, `pilot-private-pair.ts`, document-family modules as references/reuse; if their Node runtime is needed, connect a small Mac-side adapter to the existing v5 worker |
| Public ingress blocks/undercuts compare | Last live Nginx inspection: only login/preview/health forwarded, compare -> 404; `client_max_body_size 7m` vs Android pair payload up to 12 MiB; `proxy_read_timeout 50s` vs Android 180s | Allow exactly the missing compare route with body/timeout settings consistent with the existing Android client and Mac worker budget; retain bounded traffic. Validate routing via the existing public HTTPS endpoint; **no Android VPN/Tailscale requirement** |
| Mac land-document OCR not yet demonstrated | Mac has Python/v5 and locally available Swift Vision; TS family classifier/matcher exists on PR, but no deployed Mac Vision/TS bridge was demonstrated | Connect a concrete OCR reader for Arabic/French multi-page land papers and the existing dynamic role matcher on the Mac. Return true extracted fields/provenance, or clearly mark unreadable. No invented legal facts or fake OCR response |

**Important:** The identity-preview endpoint may appear healthy even while actual inference throws 503 because of the mismatched key set. A green `healthz` and passing synthetic GitHub tests are NOT functional proof.

## Dynamic property-document rules — reuse, do not redesign

Canonical full specification: [docs/MALAK-DYNAMIC-IDENTITY-MATCHING-CONTRACT.md](../MALAK-DYNAMIC-IDENTITY-MATCHING-CONTRACT.md) and [docs/SMART-LISTING-V2-DOCUMENT-TRAINING.md](../SMART-LISTING-V2-DOCUMENT-TRAINING.md).

The **six originally planned groups / seven classified kinds** are `OWNERSHIP_CERTIFICATE`, `SHARED_OWNERSHIP_CERTIFICATE`, `TRANSFER_CARD`, `SALE_DEED`, `POSSESSION_REPORT`, `FINANCIAL_RECEIPT` (QUITTANCE) and additional `PROPERTY_SERVICE_RECEIPT`.

- Match the actor **by printed role** (owner/rights-holder row; buyer or seller; transferor or transferee; possessor; payer; service applicant). A receipt applicant/payer is never implicitly the landowner.
- Compare CIN only if the document actually prints a CIN that is unambiguously associated with that exact person; otherwise allow a **name-only preliminary review**. A deliberately masked field is not OCR extraction failure.
- For shared title, show only the identity holder's **single correct row and printed abstract share if present**, not other owners' data. Abstract parts are not physical square metres.
- Keep registered whole area, sold lot area, observed possession area and transferred area distinct; same parent register does not prove same child lot. Detect unknown/ambiguous/incomplete pages instead of silently forcing a match.
- The current matching contract and synthetic tests exist in `services/verification/src/document-family-fields-v2.ts`, `document-rights-v2.ts`, `private-document-comparison.ts`, `pilot-private-pair.ts` and their tests. This is **existing code**, not proof of deployed Mac behavior. A genuine transfer-card benchmark remains unconfirmed.

## Execution sequence for Codex — narrow, autonomous

1. On current PR #14 HEAD, make Mac v5 return a contract accepted by Android, and write the one six-key regression. Verify locally with non-sensitive fixture; check both `cinFull` and birth-date fields.
2. Implement/mount Mac land-document reader + `compare-pair` runtime, reusing the role-specific TypeScript code where practical. Keep the same existing pilot bearer and a single user flow. Add a focused positive + conflict/ambiguous test for this actual handler, including consent/session rejection; avoid parallel replacement backends.
3. Update only the existing isolated public Nginx QA ingress for compare body/timeout and test login, preview-cin and compare through `https://malak-api.ayasofia.shop`. Do not modify shared CRM routing or move inference off Mac.
4. Produce **one connected QA APK** from exact new HEAD and run an **installed-phone** end-to-end journey: `1234` login -> camera front CIN -> land paper photo/PDF -> private HTTPS -> real Mac OCR -> document classification -> that person's matching role/share (when present) -> localized result. Include a mismatch/unknown result, not a forced match. Capture sanitized request IDs, HTTP statuses, build/run link and redacted screenshots/field results; never attach genuine private evidence to public CI/GitHub.
5. Update Issue #17 with exact SHA, source file changes, real live probes and pass/fail matrix. **No self-declared success from CI build or health alone**. Keep PR Draft until owner accepts working APK; do not merge or deploy production.

## Minimal acceptance (one vertical slice, not endless gates)

- [ ] Mac `/pilot/preview-cin` does not fail on a real six-field v5 result; full eight digits and DOB are shown if visible, otherwise explicitly absent/masked.
- [ ] Mac and HTTPS ingress accept `POST /internal/v1/pilot/compare-pair` (not 404, not request-size rejected for Android-valid payload).
- [ ] Server-issued bearer from the **same `1234` login** is reused for both calls; no second login or duplicate consent UX.
- [ ] At least one complete real installed-APK -> **Mac inference** -> result proof, plus a safe mismatching/ambiguous paper proof. No fake seeded extraction.
- [ ] The relevant document kind, person's role/share and differentiated land field are returned with source/page; not-printed fields remain optional. No legal ownership-verified badge from OCR.
- [ ] Publish the exact code SHA and the concise acceptance report in Issue #17. If blocked, name the single concrete error and its exact failing route, not a vague roadmap.

## Explicit non-goals / avoid churn

No Huawei OCR deployment, phone-side Tailscale, new ML model, paid OCR API, 2-sided CIN, second account login, sweeping architecture rewrite, unrelated CRM/TVX modifications, legal title-validation product or large Android design refactor. Don't lose time re-running five already inspected identity samples just to prove the historical benchmark. Scope work only to missing real runtime integrations and their focused proof.

**This handoff documents known source and previously reported runtime evidence; it does not itself fix, deploy or verify Mac-to-APK matching.**
