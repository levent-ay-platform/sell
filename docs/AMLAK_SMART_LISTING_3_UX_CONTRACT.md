# Amlak Smart Listing 3.0 — unified evidence intake + geo editor

Status: **approved UX direction / implementation contract**, 2026-11-23. NOT an implemented screen, verified georeferencing system, released APK, or deployment.
Owner direction from conversation: one upload screen with four evidence tiles; next screen presents extracted facts, exact-or-labelled location, dimensions/area on an editable native map, and automatically generated listing copy.

## Existing baseline (Draft PR #22)
- `SellerOcrJourney.kt`: 4-step certificate -> CIN camera -> extraction/property -> summary; `DirectOwnerListing.kt` handles direct Pilot publishing and optional `SellerOcrJourney` attachment. Do not remove the publish-first/unverified route.
- `TunisianIdCamera.kt`, `LocalDocumentOcr.kt`: camera and private front-of-ID read.
- `parcel/OwnerParcelEditor.kt` + `ParcelGeometry.kt`: **page-space** contour editing, reference edge and estimated measurements from PDF scans; not map georeferencing.
- `explore/DiscoveryMap.kt`, `AtlasLens.kt`, `MapLayersSheet.kt`: MapLibre plus source-labelled Atlas/INS/PAU/AFI layers. Use verified readonly snapshots, not arbitrary server claims.
- GitHub authoritative records: product Issue #21, Atlas/cadastre Issue #20, Draft PR #22; the latter is not merged.

## UX: 3 screens, not 4 obligatory upload steps

### Screen 1: Evidence hub / `أضف عقارك`
One scroll screen with four **independent, equally weighted 2x2 document tiles**. Each supports empty, choosing, captured, uploading, analysing, extracted, review-required, failed, and replace/remove:
1. **بطاقة الهوية**: FRONT ONLY, camera/gallery, use readable tilted-card capture, local preview/retake, Apple Vision on Mac via permitted private service, structured CIN/name/DOB. Avoid fake success flags and arbitrary auto-rejection for not fitting a painted frame.
2. **شهادة الملكية**: camera/gallery/PDF, multiple pages, classified across five accepted property-document families; allow manual reclassification; read owner, title/plot references, area/share if present.
3. **مخطط الأرض**: PDF/image, page selector and contour detection; separate printed side measures and *plan-space* estimate; drag handles for missing segments.
4. **وثائق إضافية**: multiple PDF/images, type/subtype, evidence preserved with provenance and de-dup; never merge multiple source docs silently.

Each tile shows actual status `0 files`, `uploaded`, `extracting`, `review needed`, and/or selected page count; no required "all green" gate. A small image/photo collection tile for listing media remains accessible separately in the same screen or editor (NOT misclassified as ownership evidence). Uploads may be asynchronous and idempotent. Durable autosave survives application interruption, preserves user-edited values, restores same listing ID. Keep backend-authorization and private evidence guarantees from Issue #21.

Action: **`التالي: عرض العقار`** opens editor even if some tiles are empty. If missing inputs, title/location/area/price are manually editable; publication availability remains backend/capability and Pilot policy-dependent. The closed-Pilot `PUBLISHED` with separate unverified `verificationStatus` is not the legacy V2 verified-publication bypass. Owners can add documents later on the *same listing ID*.

### Screen 2: Smart property workbench
Top: interactive MapLibre 2D map with seller-selected/derived property point and a **draft** polygon, field edit controls and layer selector. Retain map viewport on back; AR RTL and FR LTR.
Below or floating collapsible bottom sheet:
- Extracted private data with source & page and per-field status; CIN visible only in **owner-private** UI, never in public listing.
- `ولاية / معتمدية / عمادة / الموقع / مرجع الرسم / رقم القطعة / المساحة الواردة / المساحة المحسوبة / الأطوال / نوع العقار / الصور / السعر`.
- Value source legend: read from CIN, title doc, plan PDF, user correction, GIS candidate, or **verified official**. Never overwrite originals after a correction.
- Missing/conflicting fields are editable in place; conflicts do not silently resolve. A change to a coordinate or vertex recalculates geometric area/perimeter/edge lengths; a change to text area or price recomputes price/m² and suggested ad copy after user accepts a refresh.

### Geolocation truth ladder (critical)
**Do not claim exact geolocation from PDF outline, title number, municipality or INS/PAU boundary alone.**
- `GEOREFERENCED_VERIFIED`: actual geodetic parcel geometry from provenance-checked map/cadastral record with matching title/parcel and reviewed transform/CRS; separately scoped publish consent. Display exact edges/area as geographically measured, after independent geometric QA.
- `GEO_CANDIDATE`: owner pin or Atlas/CRM reference or candidate geocoding; allow snapping of *place labels* to verified INS admin boundaries and highlight nearest verified layer feature for inspection, never snap polygon onto merely proximate PAU/AFI lot as legal match.
- `PLAN_SPACE_ONLY`: scanned outline plus printed measurements, helpful **estimate or drawing** only; show on PDF canvas, not arbitrarily warp onto Tunisian map. User can place an approximate pin/selection; polygon remains unlocated until georeference.
- `UNLOCATED`: municipality/region only, prompt user for map pin without inventing GPS coordinates.

When GIS coordinates are grounded: interactive vertices, edge labels and closed ring validation, undo/redo, live measurements in m/m², optional 90-degree lock, affine transformation only with sufficient reviewed control points; minimum 3 noncollinear controls for affine transform, more for perspective + QC. Show `printed` vs `calculated` vs `manually supplied`, and residual error when fitting control points.
Admin divisions INS are navigation/filter/suggestions, PAU is land-use planning, AFI is industrial lots, cadastral plan is separate documentary source. Maintain source/version/geometry-status legends and layering. Public map doesn't inherit private exact seller coordinates by default.

### Screen 3: Ad preview / submission
Show suggested title, live ad text, photo order, location accuracy label, area provenance, price TND, sale type, review status and public/private preview toggle. A generated text uses ONLY confirmed facts, not invented utilities, availability, legal ownership, accessibility or exact distances. User can edit and lock custom text; auto-updates are proposals, not destructive overwrites.
- Example template with placeholders: `أرض للبيع في [المنطقة]، المساحة [المساحة] م²، السعر [السعر] د.ت. [تفاصيل مؤكدة عن المخطط/الوثائق].`
- One confirmation uses existing publish capability and backend validation; preserve immutable listing ID, separate verification and georeference decisions, redact all private evidence and CIN from public artifacts.
- The same listing can move from unverified to review-pending and reviewed without losing public listing ID or previous owner edits.

## Performance and camera
- One native camera surface CameraX, fast startup, explicit manual shutter always works. Auto-capture optional on stable detection. Don't show repeated `قرّب/بعّد` loops or block clear photos based on box fit.
- Detect image orientation, skew/perspective, and crop a *copy*; keep original. Retry original if geometry detector fails. For document pages support multipage and rotation.
- Mac Apple Vision QA on four original Tunisian front-ID photos: all four have readable 8-digit CIN, names, surnames; DOB fragments reassemble 4/4 after user confirms C date 21/12/1985. OCR-only duration measured ~0.3-1.1 s, NOT end-to-end Android latency, and 0/15/30/45-degree tilt or upload-network not yet benchmarked.
- OCR jobs per tile are cancellable/idempotent and run concurrently with state UI. Never require OCR to complete to scroll or edit manually.

## Implementation slices (no hidden production toggle)
1. **Slice A**: additive state model `EvidenceTileState`, single 4-card Compose evidence hub, existing CIN and certificate pickers routed to respective tiles, robust saved drafts and non-blocking progress; no dead controls; AR/FR parity.
2. **Slice B**: split plan and extra evidence uploads in a backward-compatible private document contract; adapt OCR/pairing readouts and per-field provenance; pass tests for mixed PDFs/missing docs; no rewriting old docs.
3. **Slice C**: MapLibre workbench with source-labelled admin suggestions, valid map pin + polygon candidate, map/document split, line/area geometry and undo/redo; use existing editor, not a duplicate contour pipeline.
4. **Slice D**: deterministic factual title/description generation, explainable field suggestions, owner edits and locked text, preview/publish-first and same-ID later verification.
5. **Slice E**: physical Android AR/FR acceptance on camera tilt 0/15/30/45, glint, blur, screen widths/keyboard, process kill/resume, offline, alternate location classes, and server backend auth/revocation. Compare Apple Vision vs OCR V5 on same consented originals. No production rollout from unit tests alone.

## Acceptance criteria
- Owner starts from central Add; sees 4 evidence cards on **one screen**, each capture/replace/delete with truthful status and thumbnail/page count.
- One step forward opens map + extracted fields as usable editable content; same listing draft and edits restored after force-close; no forced re-upload.
- Photo listing media and private evidence cannot cross streams. CIN never leaks into public ad. Photo-quality warning is advisory with manual shutter/retake always enabled.
- For a tilted input picture, app shows corrected preview or unmodified fallback and per-field result, not looping frame rejection.
- If plan is only a PDF pixel outline and no actual verified coordinates, UI explicitly says `مخطط غير مسند مكانياً`; no purported exact parcel polygon is drawn on Tunisia map.
- For a verified georeferenced parcel: changing a vertex updates side lengths and metric area, remains inside allowed admin layer and generates source-aware change/review evidence; source geometry not silently rewritten.
- Suggested Arabic/French copy includes only real facts; edits survive recalculation; clear public preview excludes private fields.
- Backend remains authority for publication permissions, verification state and public boundary/doc visibility. Atlas Issue #20 remains the source for official cadastre inventory.
- Unit, UI/emulator, API integration, and physical-phone QA evidence attached to Draft PR #22 before release.
