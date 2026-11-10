# ملاك MVP — product-first acceptance (2026-11-10)

**Project truth:** Android remains a QA prototype, and the prior 3D/photorealistic approved-home visual blocker (Issue #7) is still open. The encrypted/OCR/security backend is isolated development code in Draft PR #14. Passing CI is not deployment or legal ownership verification.

## MVP vertical slice and explicit status

1. Seller enters property title, location, area (m²), price (TND), and legal capacity: **Android local UI**. No public listing is created yet.
2. Seller chooses one full PDF or up to eight numbered photos: **Android system document picker**. The app does not silently upload or keep a document in public storage.
3. Private OCR reads submitted CPF evidence and generates **source-attributed structured fields**: the existing Python worker and TS parser do this in isolation; `services/verification/src/ocr-presentation.ts` generates owner/reviewer-only field cards with page, OCR source, confidence, missing and uncertain values. **Authenticated Android-to-service delivery is not yet connected**.
4. Seller reviews a polished, clearly **synthetic** example of OCR field cards in the Android QA UI, and a safe numerical comparison with entered area. The selected real document is **not scanned** in this current build. Do not infer that the example's values came from the user's selected file.
5. Phase 2 checks identity information vs name on current title, recorded shares/rights, issuance date/current CPF rights and human reviewer evidence. All stay **NOT_STARTED** until independently verified and signed off; no document/OCR can mark ownership verified automatically.
6. Only an authorized server decision tied to immutable evidence can ever enable a public listing. This is NOT wired yet.

## Completion criteria before claiming real MVP

- Authenticated +216 user session and consent grant/revoke through server, with file upload progress/retry and bounded API, integrated with the existing private encrypted store, ClamAV and Postgres.
- OCR worker queued from actual stored encrypted evidence and private OCR result rendered in Android, with confidence/provenance; no fake pre-filled result for real files.
- Identity/title matching phase-2 evidence received and checked by an authorized reviewer, with explicit mismatch, missing pages and unreadable values. Human approval must bind to immutable case/evidence snapshot and source recency.
- Dedicated, reversible isolated Huawei/Ayasofia services and a documented private backup/restore procedure; real docs allowed only after separate privacy/security authorization.
- Device acceptance: Android install, readable 360/412dp layouts in Arabic/French, screenshots, low-memory UX, approved reference design and real map/satellite provider or honest low-bandwidth list fallback.

**Developer policy:** next batch must connect a user-visible step end-to-end; avoid another series of disconnected infrastructure adapters. Use AI Agent CTR for authorized device operations, GitHub-hosted Ubuntu for CI, no self-hosted runner/production merge or deploy without owner approval. No real CIN/CPF samples in public repo or Actions.
