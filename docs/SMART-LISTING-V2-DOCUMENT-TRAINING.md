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
