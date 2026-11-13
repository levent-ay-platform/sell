# MALAK SELL — document-specific dynamic identity matching contract (2026-11-13)

**Binding user requirement:** Document -> direct-front Tunisian ID camera -> ONLY THAT PERSON's source-backed property facts/role/share (when the paper actually has them) -> manually confirmed listing details. Do not force every accepted document into a "co-owner row + shares + CIN" schema. No paid model. No public sharing of private evidence.

## Important distinction: six original document groups, seven classified kinds

The six originally **planned kinds** were OWNERSHIP_CERTIFICATE, SHARED_OWNERSHIP_CERTIFICATE, TRANSFER_CARD, SALE_DEED, POSSESSION_REPORT and FINANCIAL_RECEIPT (QUITTANCE). That does **not** mean six independently confirmed real examples exist: the supposed transfer-card photo was actually a QUITTANCE, and a genuine transfer-card template is still **untested**. A separate two-page ownership-certificate PDF was also supplied. An additional real PROPERTY_SERVICE_RECEIPT, titled "وصل تسلم مطلب خدمة واستخلاص معاليم", was later provided and is class seven. These groups are accepted for private evidence review, NOT all legally conclusive ownership proofs.

### Dynamic per-kind extraction and identity-role contract

| Kind | Extract only if actually printed; never substitute another field | Target role from user's direct-front ID | What a name/CIN match means and **does not** mean | Private base evidence tier* |
| --- | --- | --- | --- | --- |
| OWNERSHIP_CERTIFICATE | Issuing office, register/parent ref, estate/plot, whole physical area (m²), printed owner(s), abstract parts *if present*, issue/update dates and legal restrictions | Explicitly printed rights holder/owner | Owner-name text agreement; compare document CIN **only if printed and source-bound**. No proof of authentic/unchanged CPF record or legal authority | 5 |
| SHARED_OWNERSHIP_CERTIFICATE | Register and whole area, owner-rights table, exact matched person's numbered row and **their** printed abstract share, page/source; do not demand extraction of others' shares | Exactly one numbered rights-holder row corresponding to front-ID name and, when printed in that row, CIN | CIN+name in SAME row -> provisional person-row agreement; name without printed CIN -> **name-only review**, not CIN agreement; missing row/share -> partial review. Others' names/CIN are never displayed. Abstract parts are not m² and do not establish a physical plot | 4 |
| TRANSFER_CARD | Issuer, date/operation/register references, transferor, transferee, plot, transferred **area OR parts as explicitly labelled** | TRANSFEREE vs TRANSFEROR, separately | Name text correspondence only to the labelled party; never assume change in registered ownership. Real confirmed template not yet measured | 3 |
| SALE_DEED | Notary/contract date, buyer, seller and proxies, parent register, **sold** lot and sold m² (distinct from parent area), sale price/terms, registration refs and caveats | BUYER vs SELLER, separately | Party association, not proof the deed is registered or current title. A matching seller is not automatically a legal owner; the buyer is not registered solely from deed text | 3 |
| POSSESSION_REPORT | Officer/date, requester, referenced deed/title, **possessed** lot and observed m², boundaries, observations | Requester/possessor **only when expressly labelled** | Relationship to possession proceedings, **not** registered ownership. No shares are required or inferred | 2 |
| FINANCIAL_RECEIPT (QUITTANCE) | Receipt number/date, payer *when printed*, payment reference, TND amount, purpose and office | PAYER **if explicitly identified**; otherwise no identity actor | Financial-supporting evidence ONLY. No owner/share identity inference from payment or receipt number | 1 |
| PROPERTY_SERVICE_RECEIPT | Service-request number/date, applicant *if printed*, CPF office, title reference, fee amount/purpose | SERVICE_APPLICANT **if explicitly named** | Administrative service request/supporting evidence ONLY; applicant ≠ owner | 1 |

*Private **relative source-evidence tier**, not a legal score, public seller trust percentage, or transferable identity verification. Combining receipts cannot add up to a title. Independent legal CPF/registration review is required for any eventual "verified owner" badge.

## Shared conditional decision rules

1. **Recognize before extracting:** OCR heading, issuer, document layout, page evidence -> classified kind or UNKNOWN. All safe unknown kinds remain admissible for private review; malware, authorization, consent still required.
2. **Read front CIN independently:** surname, given name, all eight CIN digits, date of birth. If a real production photo is blurry, cropped or missing fields, show retake/manual-review status; a screen-photographed card may be deliberately used in the QA pilot, but that is not evidence of genuine card possession. Do not advertise identity verified.
3. **Select relevant actor(s), not every name:** rights-holder row, buyer/seller, transferor/transferee, possession requester, payment payer, service applicant. Distinguish the actor's **legal role** as printed.
4. **Name + CIN rule is conditional on the source:** when the paper contains a clearly labelled CIN source-linked to the same actor, compare all eight digits. When CIN isn't actually printed, do not fabricate it or reject the paper; return `NAME_ONLY_REVIEW` if a unique role-labelled name agrees. If no named actor, `NO_PERSON_IDENTIFIED`. If CIN disagrees, `CONFLICT_REVIEW`. Date of birth comparison only if actually present and source-labelled on the property paper; its absence is normal.
5. **Rights/area are optional per kind:** for coownership, require ONLY the matched person's own row/share if legible; never block because unrelated owners are unreadable. For a sale, use **sold parcel** area; for possession, **observed** area; for certificate, **whole registered estate** area. Never convert abstract rights parts to m², or sell/possess/transfer one area under another's label.
6. **Property correspondence is separately checked:** compare source-specific register references, parent register, sold/possessed/transferred lot and dates, when printed, with other user-submitted evidence. Shared parent register alone never means the same child lot. Mismatches -> review, not silent merging.
7. **One personal result, non-public:** output only the identified person's identity fields from their own captured ID, matching actor role, their own abstract parts if printed, source page/field-box and source-backed property facts. Do not expose unrelated co-owners. An ambiguous duplicate role or person must return review, not a lucky pick.
8. **Separate trust axes:** `documentType`, `documentFields`, `identityOcrQuality`, `actorNameMatch`, `actorCinMatch`, `propertyReferenceMatch`, `legalRegistrationStatus`, `humanReviewNeeded`. Never collapse these into a single "owned/verified" Boolean based on OCR.
9. **Fail safe:** `identityVerified:false`, `ownershipVerified:false`, `mayPublish:false` remain until independent required checks. Missing per-kind optional fields alone do NOT reject intake; security policy still protects uploads.

## Real specimen / engineering gaps

- The actual private **two-page CPF certificate** is still an OCR challenge for owner rows/name/CIN and pagination. Manually cropped index-ordinal recognition was not a real end-to-end acceptance of the private Huawei runtime.
- One user-supplied sale deed and observed possession report refer to the same **parent register** but distinct **child plot and area**: never fuse them into one sale parcel.
- The user-supplied **service request fee receipt** has request/date/title and fee, but does not by itself prove the applicant owns the property.
- No confirmed **transfer card** real sample has passed extraction evaluation. QUITTANCE is a different kind.
- Existing matcher can produce only strict name/CIN matches or INSUFFICIENT, and some rules require a literal `CIN` label in a source row. It does not yet implement all the conditional statuses above for actual documents. This file is **product contract and gap specification**, NOT claim of implementation or deployment.

## Acceptance proof to build

For EACH of the seven kinds, run fabricated positive/negative/ambiguous contract tests and consented private real-document field-level benchmarks: correct kind, exact relevant actors and labels, CIN-present vs absent, no false owner status, exact register/lot distinction, correct only-one-person display, page/box provenance, edit/retake. Report accuracy by field and kind (including UNKNOWN), not a single model "confidence" percentage. Test the full Android app on an authorized real account and actual phone. No merge/deploy or public badge until accepted.


## Implementation checkpoint — draft-only (2026-11-13)

Backend now derives a **per-family role-specific CIN** only from explicit qualified labels (buyer, seller, transferor/transferee, possession requester, payment payer, service applicant). It cannot use an unrelated title, payment or plain "CIN" number. It returns `ROLE_TEXT_MATCH_ONLY` for name + role-bound CIN text equality; `NAME_ONLY_REVIEW` when the named role matches but document CIN is genuinely **not printed or masked**; `CONFLICT_REVIEW` for a direct, source-bound CIN contradiction; `NO_PERSON_IDENTIFIED` only when no role-labelled person exists; otherwise `INSUFFICIENT_EVIDENCE` or `ROLE_AMBIGUOUS`. Masked fields are non-evaluable, NOT failure of optical reading.

The private shared-ownership path still requires page set/source geometry validity and compares **only the user ID-holder's unique numbered row**, never the full roster. A readable share on that row may be shown as abstract rights parts, even if other owners are unreadable; if missing the target share, show "needs correction", not a fabricated physical area. An unlabeled eight-digit token in the row blocks name-only promotion to prevent using a hidden/ambiguous CIN as absent.

Android now displays only the uniquely matching role and its own abstract rights parts (if present); if uncertain or ambiguous it shows a review message without exposing names/roles of other persons. Default capacity declaration is blank ("not declared"), not "sole owner". No report contains other owners' names/CIN. All statuses remain private and `identityVerified=false`, `legalOwnershipVerified=false`, `mayPublish=false`.

These synthetic-contract and app UI changes are **not real-image model acceptance**. A future legal registry check is still needed for any verified-seller claim; live Huawei app/service + database migration not deployed by this Draft PR.
