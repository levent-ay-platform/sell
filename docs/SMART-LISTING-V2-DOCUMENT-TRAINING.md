# MALAK Smart Listing V2 — Tunisian property-document intelligence (schema v1)

**Status:** Draft-only classification contract; no trained image model, no actual-document OCR benchmark, no production deploy. **No paid APIs.**

## Why this matters

A form reading `المساحة` anywhere will silently misrepresent subdivided sales. We must keep **registered estate area**, **offered plot / sold area**, **possession area**, and **abstract ownership parts** in separate typed fields. One original title can cover multiple numbered parcels/transactions; a registry printout may list many co-owners across multiple pages.

## Five requested document classes

| Type | Heading signals | Source fields to capture (non-exhaustive) | Must NOT infer |
|---|---|---|---|
| `OWNERSHIP_CERTIFICATE` | `شهادة ملكية`, `Certificat de propriété` | Issuer/regional CPF office, register and district, parent reference, property name, whole area in m², distinct abstract parts denominator, land use/category, plot list, locality, **all** owner/rights rows across **all** pages (name, CIN and DOB *only if actually printed*, each rights part, filing/date/volume), certificate issue vs register update, stamps, all printed legal remarks/restrictions | The first owner holds 100%; a 20,800-part total is 20,800 saleable square metres per person; recent title or legal clearance from a photo |
| `SHARED_OWNERSHIP_CERTIFICATE` | `شهادة اشتراك في الملكية` | Parent/new register reference, whole physical area, printed shares denominator, co-owner rows including decimals, filing refs, dates, land type, locality and page completeness | A co-owner is entitled to sell the entire title or a specified physical location solely from a fractional share |
| `TRANSFER_CARD` | `بطاقة نقل` / `بطاقة نقل الملكية` | Transferor/transferee + their legal capacities, register and plot, operation, transferred **parts vs area** separately, registration references/dates, issuer, completeness | This proves current registered title without independent CPF status check |
| `SALE_DEED` | `إنجاز بيع`, `عقد بيع`, `Acte de vente` | Seller/buyer/capacity/proxy, notary/date, **parent register area** vs **sold plot number and area**, price, terms/payment units, formal registration, boundaries and adjacent plots; page signatures/clauses | Sale deed proves registration completed; the first person named is current owner; parent area equals the sold subparcel |
| `POSSESSION_REPORT` | `محضر معاينة تحويز` | Court officer and act date/time, requester, parent register, observed **possession plot** and area, borders/measurements, underlying deed ref, observations | Observed physical possession is legally equivalent to registered ownership |

## A required sixth *detection*, NOT an accepted title

`FINANCIAL_RECEIPT` — `وصل قبض / QUITTANCE`: extract payer/receipt number/series/accounting ref/fee amount/payment method/date **only as supporting evidence**. The example provided by the user is a financial receipt, **not** a transfer card. **A confirmed transfer-card sample is still missing**; no measured accuracy claim may be made for that document. Never classify a receipt as title/transfer.

## Runtime/data flow

1. Keep original multi-page evidence inside the authorized encrypted private workflow; verify page counts and file magic. Never push originals, personal names, CIN or title numbers to public source control/CI/logs.
2. Private text extraction by page (native PDF text when present, OCR on image scans). Bounded CPU/memory/time; record page/line, OCR confidence and optional bounding boxes.
3. `classifyLandPaper()` recognizes **short headings on page 1**. Conflicting title headings return `AMBIGUOUS`; missing page 1 returns `UNKNOWN`. A deed's body mention of another paper is not that paper's class. Classification is a **candidate**, not document validity.
4. Per-type typed extraction (future stage) must capture source spans for every field. **No guessing** absent/occluded values. Separate `NOT_IN_SOURCE`, `MASKED`, `UNREADABLE`, `LOW_CONFIDENCE`, `CONFLICT`. Never call masked text OCR failure.
5. Cross-check within document: same register/date/issuer across pages; owner table rows may continue on page 2; distinct page numbers; decimal share-part sum matches printed denominator (decimal math), not a square-metres calculation. Do not equate fractional undivided share to a mapped parcel.
6. Cross-document links require both original register and exact parcel/transaction scope, not just same parent title number. A receipt without clear matching transaction identifier stays unlinked.
7. Only after authorized owner-scope matching may the app show **pre-filled suggestions** with original page and highlighted source. User can correct errors. Never overwrite user edits silently. Publish remains disabled until independent CPF/title verification and human legal acceptance.

### Field priority

**P0**: type/classification, page completeness, parent register + district, distinct property-area/sold-area/possession-area/share-parts semantics, parties and their roles, share denominator and every listed rights holder, legal caveats, provenance and conflict flags.

**P1**: dates (issue/registration/possession distinguished), plot boundaries, underlying deed and filing refs, category/crops, document issuer, signatures as *visual indicators only*.

**P2**: optional address detail, transaction payment instalment information, printable presentation refinements.

### Training strategy (zero paid inference)

- Phase 1 **implemented in Draft code**: heading-rule classifier and per-type extraction-field contracts; synthetic tests for six detections (five target classes plus receipt) and fail-closed classification. This is **NOT ML training**.
- Phase 2: grammar/ROI extractor tuned from the private reference, including owner-table cross-page segmentation, Arabic/French table reading order, value types, per-field spans and validation. Produce redacted or wholly fictional training examples only in public CI.
- Phase 3: private, permissioned evaluation of real documents by **document type** and scan quality (including newly supplied genuine transfer card), with target labels audited by a human; measure confusion matrix, per-critical-field precision/recall, unknown/ambiguous refusal, p50/p95 time and memory. Evaluate failure cases, not just average OCR confidence.
- Phase 4: optional local compact model only if rule-first classification fails the held-out corpus. Never depend on a paid provider or increase APK size: OCR/classification stays behind controlled private service.

### Acceptance gates

- 0 silent transfers between a financial receipt and transfer-card class in tested private corpus; support-only receipts never grant ownership.
- 0 accepted title declarations from one co-owner share alone, no physical-area/share parts substitution.
- All pages/owners read or the result is incomplete and reviewed.
- No document PII in app analytics, GitHub artifacts, screenshots or logs.
- Exact-head CI plus real Android device UX acceptance; PR remains Draft and not deployed.

Source: User-supplied **private** Tunisian title/share/sale/possession/receipt samples and a two-page ownership certificate. Only class-level field patterns are documented here; no individual or property identifiers included.


## Stage-2 checkpoint: private text provenance and multi-page owner-table contract (2026-11-13)

Implemented on the **Draft branch only**:

- `src/owner-table-v2.ts` composes **explicit row-and-column observations** originating from a trusted-infrastructure layout step, never from free-floating text guessed across table columns. Each owner row has its **printed ordinal** (the label `1/7` is ordinal 7, not 1/7 share), a source page for every observed cell, and field states `CANDIDATE / LOW_CONFIDENCE / CONFLICT / MASKED / NOT_IN_SOURCE / UNREADABLE / NOT_OBSERVED`.
- Seven-row, two-page **fictional** unit cases cover complete and missing pages/owners, repeated value agreement, conflicting names, masked and truly absent CIN, leading-zero CIN strings, invalid coordinates, and 0.001-part discrepancies. Declared owner count, page count and printed total parts must be known before the table can be considered structurally ready for **human** review. Even then `ownershipVerified=false` and `mayPublish=false`.
- `ocr/ingest.py` retains bounded **Tesseract line pixel rectangles** on full scanned pages. Focused OCR over cropped/enlarged ROI deliberately **discards local crop rectangles** rather than misleadingly identifying them as source-page coordinates. Native text PDF lines currently have no rectangle unless a trusted PDF layout mapping is added later.
- `src/reader.ts` validates numeric geometry and method, preserves rectangle provenance in `RecognizedLine`; `src/smart-fields.ts` keeps rectangles with OCR field evidence; `src/ocr-presentation.ts` exposes them only as optional **authorized private** fact-card source locations.
- Existing line OCR is **NOT** a complete spatial table parser. Do not fabricate `ownerTable` rows by regex on OCR text. The next step is a secure layout detector that associates real word-level boxes to printed table columns, paired with private, labeled acceptance. No personally identifying document or genuine title data was committed.

Verification: GitHub-hosted `Malak F1 individual verification core` targeted Node tests and synthetic PDF/OCR integration passed at corrected head `053c89ae`. Recheck final head if additional changes are made. Android real-device visual acceptance and measured classifier recall on genuine, permissioned documents remain open.



## Stage 3 — private word-geometry and layout candidates (2026-11-13)

The private Python document OCR worker may now attach bounded Tesseract **word** rectangles with original page pixel dimensions (up to 1000 words/page and 2500/document). If truncated, it explicitly marks the spatial view unusable while preserving the ordinary text-OCR result. This is sensitive material and must never enter public telemetry, logs, artifacts or listing APIs.

The private TypeScript reader validates each word's page, coordinates, dimensions, confidence and output size, and invokes the **deterministic** `owner-table-layout-v2.ts` candidate finder **only when all pages have untruncated raster word geometry**. The finder uses printed column headings and numbered `1/n` rows; it refuses absent or ambiguous headings, duplicated indices, multiple share candidates, and shares without an adjacent printed `جزء`. It emits a *private candidate observation set* for `analyzeOwnerTable` but DOES NOT verify identity, ownership, property boundaries or sale authority.

Critical caveats:
- This is **synthetic geometry/rule acceptance, not a trained image model**. It is not guaranteed to segment dense real Tunisian legal tables until separately benchmarked on consented, privately held documents.
- Word tokens from raster OCR can have unreliable Arabic ordering. Candidate person names are therefore always subject to human correction, and a missing name remains unresolved.
- Header geometry may differ across editions and office layouts. No permanent fixed pixel column locations are assumed. Real-paper QA must measure false association of owners and parts, including owner tables spilling onto a second page.
- No genuine source files or legal/identity person data are placed in GitHub. No paid model, CRM changes or production deployment.

**Private QA gate before enabling customer autofill:** on each real certificate, verify document family, detected page count, every printed ordinal, all rights sums, correct source bounding boxes and separation of **physical estate area**, **ownership parts**, **subparcel sold area** and **possession area**. Fail closed on ambiguous evidence.


## Stage 4 — evidence-backed, document-family-specific field suggestions (2026-11-13)

**Draft-only. No unattended autofill, legal proof, public access or production deployment.**

The private document-family-fields-v2.ts module adds conservative exact-label source-backed extraction for five target kinds plus the supporting-only financial receipt. The private OCR reader attaches privateFamilyFields and does NOT expose it through a public listing endpoint. Each fact retains its original page, line index, optional page pixel box, OCR method and confidence. Conflicting values are blocked; masked personal fields stay masked. Three-digit comma suffixes (such as 42,910) are marked for manual review, not silently interpreted as thousands or decimals.

| Private type | Supported explicit labels (initial subset) | Prohibited inference |
|---|---|---|
| Ownership / shared-ownership certificate | register, parent reference, registered whole-estate area m², registered plot, total abstract rights parts | Whole-estate area is not offered seller-specific area; abstract parts are not square metres |
| Sale deed | parent register, clearly labelled sold plot and sold area, explicit seller/buyer roles, price | Parent estate area is not sold area; first named person is not necessarily the buyer |
| Possession report | explicitly labelled observed plot and possession area | Possession does not establish registered ownership |
| Transfer card | transferor, transferee, transferred physical area, operation reference | Real transfer-card sample remains unavailable: no tested accuracy claim |
| Financial QUITTANCE | receipt number and amount, supporting evidence only | Payment receipt is not a transfer card or title |

**Deliberate limits:** current rules extract only explicit standalone labels. They do not parse free legal prose, legal names in multi-column table without a layout candidate, or unlabelled sums/dates. OCR confidence is not official CPF verification. Real-document benchmark, authenticated UI review/source highlight and party-role adjudication are next gates.

QA consists of wholly fictional fixtures only; no real identity or document data is committed.


## Stage 5 — accepting safe administrative service receipts + numeric private evidence progress (2026-11-13)

**User product decision:** recognize every uploaded document as supporting private evidence when it passes the existing malware/MIME/consent controls. Do not reject solely for being a receipt or an unrecognized title; instead classify, privately extract source-linked data, optionally compare the applicant/registry evidence with independent identity documents, and ask for stronger evidence when required. This is a **policy/domain contract**; existing evidence upload API kind allowlists have NOT yet been migrated to accept all classes.

**Seventh class**: PROPERTY_SERVICE_RECEIPT detects CPF regional paper titled "وصل تسلم مطلب خدمة واستخلاص معاليم". Separate from FINANCIAL_RECEIPT (QUITTANCE), TRANSFER_CARD and the five primary classes. Initial exact-label extraction targets request number, request date, applicant (private PII), land register reference, and fee amount. The original example is held privately; its source image, numbers, names and raw OCR are never committed. Dates are validated and attached to page evidence. Never presume the person who paid an administrative fee owns the property.

**Two distinct numeric product signals:**
1. Document evidence support level from 0 to 5 (product-level editorial ranking, not a legal conclusion): registered ownership certificate 5, shared certificate 4, sale deed and genuine transfer card 3, possession record 2, administrative service receipt and financial payment receipt 1, unknown 0.
2. Private *evidence-review progress* up to **75** (not a probability of fraud/ownership): type signal at most 40, source-readable critical fields at most 10, preliminary identity-text agreement 15 and land-reference alignment 10. The remaining **25 points are never automatically awarded** and represent a separate future official registry/legal review gate; this module does not implement that gate.

**Hard constraints:** all classes and unknown intake output ACCEPT_FOR_PRIVATE_REVIEW for file-type policy, but security/malware/consent checks remain mandatory. A mere photo, name/CIN match or numeric score NEVER sets a verified-seller badge, legal ownership, title clearance or publication permission. The score is PRIVATE until product/legal review of fairness and misuse. Differences in ID/parcel references, incomplete/low-quality OCR and conflicting values need human review. Repeating multiple low-quality receipts must not inflate score. Service receipts are not title deeds.

**Current stage limitations:** no real-world accuracy calibration, no actual image-based model training for the new service-receipt specimen, no production allowance for all document upload kinds, no end-user UI change to show this private score yet. Do not publicize as “seller confidence percentage”.


## Stage 6 — ONE unified private identity/document matcher (2026-11-13)

The user's decision: retire independent document-identity policy branches in favor of **one orchestrator with document-specific roles**. Do not merge all legal meanings into one generic “seller is owner” status.

Code: `src/unified-identity-v2.ts` coordinates private document classification, source-backed legal actors and front-CIN identity-name text using the same validated normalizer as the existing certificate comparator. The encrypted case-bound pair service invokes this orchestrator for all supported private evidence kinds. The previous certificate-only comparison fields remain backward compatible, but other documents never produce fake certificate agreement.

| OCR document family | Person roles compared to CIN-front name | Meaning and limitation |
| --- | --- | --- |
| OWNERSHIP_CERTIFICATE | Explicit, uniquely labelled registered owner + separate strict CIN text when present | Name/CIN text agreement only, never authentic title |
| SHARED_OWNERSHIP_CERTIFICATE | Rights holder only after per-row owner/CIN source association | No guessed match from any name in multi-owner certificate |
| SALE_DEED | BUYER and SELLER separately | Matching buyer/seller name doesn't prove currently registered title |
| TRANSFER_CARD | TRANSFEREE and TRANSFEROR separately | Registered transfer is not assumed from form |
| POSSESSION_REPORT | Requester when independently and explicitly extracted (not yet supported) | No automatic title agreement |
| PROPERTY_SERVICE_RECEIPT | SERVICE_APPLICANT | Applicant/fee payer is not a registered owner |
| FINANCIAL_RECEIPT | Payer when reliably and explicitly extracted (not yet supported) | Supporting payment only |
| UNKNOWN | None | Preserve private evidence and request review |

Hard rules: no fuzzy or cross-script name matches, no CIN from general title or contract digits, multiple matching roles ambiguous, missing/low-confidence person fields inconclusive, mismatched stored kind versus detected OCR type inconclusive. New result contains **role status only, never the raw names or CIN**, plus `identityVerified:false`, `legalOwnershipVerified:false`, `mayPublish:false`, `requiresHumanReview:true`. All sensitive processing stays within authorized, consented, scanned, encrypted per-case boundary.

Status: Draft-only; synthetic CI contract acceptance is not real-document benchmark, legal ownership validation or real Android UX acceptance. The staged mobile flow must switch from the old certificate-only button to the new unified role report with guarded evidence-kind mapping before claiming complete mobile parity.
