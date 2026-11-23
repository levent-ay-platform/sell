# Amlak Smart Listing 3.1 — two-action evidence cards + document-driven geographic workbench

Status: **owner-approved UX direction / implementation contract updated 2026-11-23**. NOT an implemented screen, verified georeferencing system, released APK, or deployment. This V3.1 revision supersedes conflicting V3.0 UI labels.
Owner direction from conversation: one upload screen with four evidence tiles; next screen presents extracted facts, exact-or-labelled location, dimensions/area on an editable native map, and automatically generated listing copy.

## Existing baseline (Draft PR #22)
- `SellerOcrJourney.kt`: 4-step certificate -> CIN camera -> extraction/property -> summary; `DirectOwnerListing.kt` handles direct Pilot publishing and optional `SellerOcrJourney` attachment. Do not remove the publish-first/unverified route.
- `TunisianIdCamera.kt`, `LocalDocumentOcr.kt`: camera and private front-of-ID read.
- `parcel/OwnerParcelEditor.kt` + `ParcelGeometry.kt`: **page-space** contour editing, reference edge and estimated measurements from PDF scans; not map georeferencing.
- `explore/DiscoveryMap.kt`, `AtlasLens.kt`, `MapLayersSheet.kt`: MapLibre plus source-labelled Atlas/INS/PAU/AFI layers. Use verified readonly snapshots, not arbitrary server claims.
- GitHub authoritative records: product Issue #21, Atlas/cadastre Issue #20, Draft PR #22; the latter is not merged.

## UX: 3 screens, not 4 obligatory upload steps

### Screen 1: Evidence hub / `أضف عقارك`
One scroll screen with four **independent, equally weighted 2x2 document tiles**, and **two explicit working actions on EVERY tile: `تصوير` (open native camera) and `رفع` (open OS file/photo picker)**. Both actions are functional, never decorative. Each tile supports empty, choosing, captured, uploading, analysing, extracted, review-required, failed, and replace/remove:
1. **بطاقة الهوية**: FRONT ONLY, camera/gallery, use readable tilted-card capture, local preview/retake, Apple Vision on Mac via permitted private service, structured CIN/name/DOB. Avoid fake success flags and arbitrary auto-rejection for not fitting a painted frame.
2. **شهادة الملكية**: camera/gallery/PDF, multiple pages, classified across five accepted property-document families; allow manual reclassification; read owner, title/plot references, area/share if present.
3. **مخطط الأرض**: camera-captured multi-page plan or uploaded PDF/image; page selector, identify page containing the land outline, extract cadastral parcel contours, vertex labels and dimensions; compare printed side measures with geometry-calculated lengths, and edit vertices. Integrate map-georeferencing resolver; a page-space contour is not automatically a geodetic polygon.
4. **وثائق إضافية**: camera sequence or picker upload for multiple PDF/images, type/subtype, evidence preserved with provenance and de-dup; never merge multiple source docs silently.

Each tile shows actual status `0 files`, `uploaded`, `extracting`, `review needed`, and/or selected page count; no required "all green" gate. A small image/photo collection tile for listing media remains accessible separately in the same screen or editor (NOT misclassified as ownership evidence). Uploads may be asynchronous and idempotent. Durable autosave survives application interruption, preserves user-edited values, restores same listing ID. Keep backend-authorization and private evidence guarantees from Issue #21.

Action: **`التالي: عرض العقار`** opens editor even if some tiles are empty. If missing inputs, title/location/area/price are manually editable; publication availability remains backend/capability and Pilot policy-dependent. The closed-Pilot `PUBLISHED` with separate unverified `verificationStatus` is not the legacy V2 verified-publication bypass. Owners can add documents later on the *same listing ID*.

### Screen 2: Smart property workbench
Top: interactive MapLibre 2D map with seller-selected or data-derived property point and a **source-labelled candidate** polygon only when map anchors exist; field edit controls, plan-to-map alignment handles and layer selector. Retain map viewport on back; AR RTL and FR LTR. For a PDF-only unlocated plan, render its precise *document-space* outline in a linked plan pane, next to the map, until independently anchored.
Below or floating collapsible bottom sheet:
- Extracted private data with source & page and per-field status; CIN visible only in **owner-private** UI, never in public listing.
- `ولاية / معتمدية / عمادة / البلدية / المكان / العنوان / مرجع الرسم / رقم القطعة / أرقام المقاسم المجاورة / اسم المشروع / اسم الشارع / اتجاه الشمال / المساحة المدونة / المساحة المحسوبة / أطوال الأضلاع / نوع العقار / الصور / السعر / حالة الإسناد المكاني`.
- Value source legend: read from CIN, title doc, plan PDF, user correction, GIS candidate, or **verified official**. Never overwrite originals after a correction.
- Missing/conflicting fields are editable in place; conflicts do not silently resolve. A change to a coordinate or vertex recalculates geometric area/perimeter/edge lengths; a change to text area or price recomputes price/m² and suggested ad copy after user accepts a refresh.

### Geolocation truth ladder (critical)
**Do not claim exact geolocation from PDF outline, title number, municipality or INS/PAU boundary alone.**
- `GEOREFERENCED_VERIFIED`: actual geodetic parcel geometry from provenance-checked map/cadastral record with matching title/parcel and reviewed transform/CRS; separately scoped publish consent. Display exact edges/area as geographically measured, after independent geometric QA.
- `GEO_CANDIDATE`: owner pin or Atlas/CRM reference or candidate geocoding; allow snapping of *place labels* to verified INS admin boundaries and highlight nearest verified layer feature for inspection, never snap polygon onto merely proximate PAU/AFI lot as legal match.
- `PLAN_SPACE_ONLY`: scanned outline plus printed measurements; show on PDF canvas and a linked map alignment pane, not as a legal geodetic map polygon. User can place a *seller-selected listing pin* or pick a reference feature to initiate georeferencing. Do not claim a parcel position without validated map alignment.
- `UNLOCATED`: municipality/region only, prompt user for map pin without inventing GPS coordinates.

When GIS coordinates are grounded: interactive vertices, edge labels and closed ring validation, undo/redo, live measurements in m/m², optional 90-degree lock, affine transformation only with sufficient reviewed control points; minimum 3 noncollinear controls for affine transform, more for perspective + QC. Show `printed` vs `calculated` vs `manually supplied`, and residual error when fitting control points.
Admin divisions INS are navigation/filter/suggestions, PAU is land-use planning, AFI is industrial lots, cadastral plan is separate documentary source. Maintain source/version/geometry-status legends and layering. Public map doesn't inherit private exact seller coordinates by default.

### Document-first geo resolution and auto plan-to-map workflow (V3.1)
**Maximize evidenced facts, not invented precision.** Start resolution as documents arrive, without requiring a single preferred source:
1. Parse each file type, pages, OCR text and vector PDF primitives (if any), original drawing scale, coordinates/CRS if printed, sheet number, title number/TF, parcel/lot references, municipality/lieu-dit, delegation/governorate, adjacent parcel numbers, road labels, north arrow, easements, printed area and side lengths. Preserve every field with source/page/bbox/confidence and corrections.
2. Resolve administrative place via names/aliases in INS and source-verified records; do not infer official owner/location from a CIN birthplace. Match source-scoped title/parcel refs against the indexed official cadastral-document archive when available; no claim of unrestricted official title-number geolocation search.
3. Cross-reference project/lot/phase with the authorized CRM feed, AFI georeferenced subdivision polygons, GeoPAU land-use and road metadata, and cadastral source geometries if verified. AFI planning polygons are not interchangeable with legal title boundaries. Show match candidates and their provenance; resolve conflicting matches openly.
4. When source PDF has vector paths, inspect its actual transform and decode polyline/ring. When raster, use segmentation/line tracing and vertices; separate property outline from frame, text, roads, other lots. Extract printed measurements, scale, rotation and north orientation. Validate closed polygon, self intersections and text/area/length consistency, and allow drag-and-snap vertex correction.
5. **Align on Tunisia map with grounding:** prefer verified coordinates/CRS or a matched existing georeferenced parcel/lot with consistent identifier + geometry. Otherwise expose a simple map control-point workflow (at least 3 noncollinear reliable map/drawing reference anchors for an affine transformation, with residual check; use more and appropriate perspective transform if imagery warrants). Verify geometry, position and dimensions before marking `GEOREFERENCED_VERIFIED`. Location coordinates inferred from a municipality, one pin or an unknown-scale PDF are insufficient.
6. Auto-fill eligible fields and show a linked interactive plan/map workspace. When source is verified, map ring can be edited with vertices, snapping, side labels and m/m² calculations. When unanchored, maintain PDF geometry in the plan pane and enable locating it; on the public map display only the user-approved listing-area representation, never synthetic parcel boundaries.
7. Run geometry checks against source and admin bounds after each edit; show differences between stated area, derived scaled area and geodetic area. Use existing source-specific Atlas/AFI layers as references, not as indisputable property title evidence. Keep the original attachment and original geometry unchanged.

### Arabic/French UX language: confident, source-specific, factual
The owner requests removal of vague repeated wording such as `تقريبي` and `تقديري` from user-facing primary UI where it undermines confidence. Replace with **specific provenance/action labels**, without implying verified legal status:
- `موقع الإعلان على الخريطة` for listing location, `موقع حدده المالك` only when truly owner-selected, `الموقع بانتظار التحديد` when unset.
- `حدود مستخرجة من المخطط` for document-space outline; `مخطط قابل للمحاذاة` when missing trusted map anchors; `حدود مسندة بإحداثيات` only with geospatial coordinate proof; `حدود معتمدة` only following reviewer acceptance.
- `مساحة محسوبة من المخطط` for scaled drawing geometry; `مساحة الوثيقة` for printed reported figure; `مساحة محسوبة من الإحداثيات` for actual mapped geodetic polygon.
- Do NOT globally replace legal/source semantics with `دقيق`, `رسمي`, or `موثّق` where unsupported. Internal geometry accuracy statuses and technical audit fields remain, and localizations AR/FR/EN must be audited.

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
1. **Slice A**: additive state model `EvidenceTileState`, single 4-card Compose evidence hub with TWO working actions on every tile (`تصوير` launches camera, `رفع` opens file picker), existing CIN and certificate pickers routed to respective tiles, robust saved drafts and non-blocking progress; no dead controls; AR/FR parity.
2. **Slice B**: split plan and extra evidence uploads in a backward-compatible private document contract; adapt OCR/pairing readouts and per-field provenance; pass tests for mixed PDFs/missing docs; no rewriting old docs.
3. **Slice C**: document-to-geo resolver using extracted TF/parcel/place/adjacent-lot facts with Atlas INS/AFI/GeoPAU/CRM source links, MapLibre workbench with source-labelled admin matches, editable PDF-derived contour, verified georeference/control-point alignment, independent listing pin and polygon, line/area geometry and undo/redo; reuse existing owner parcel editor, not duplicate pipeline.
4. **Slice D**: AR/FR/EN copy audit removing generic confidence-eroding terms and replacing them with accurate provenance labels, deterministic factual title/description generation, explainable field suggestions, owner edits and locked text, preview/publish-first and same-ID later verification.
5. **Slice E**: physical Android AR/FR acceptance on camera tilt 0/15/30/45, glint, blur, screen widths/keyboard, process kill/resume, offline, alternate location classes, and server backend auth/revocation. Compare Apple Vision vs OCR V5 on same consented originals. No production rollout from unit tests alone.

## Acceptance criteria
- Owner starts from central Add; sees 4 evidence cards on **one screen**, each with separate working `تصوير` and `رفع` actions, replace/delete, truthful status and thumbnail/page count.
- One step forward opens map + extracted fields as usable editable content; same listing draft and edits restored after force-close; no forced re-upload.
- Photo listing media and private evidence cannot cross streams. CIN never leaks into public ad. Photo-quality warning is advisory with manual shutter/retake always enabled.
- For a tilted input picture, app shows corrected preview or unmodified fallback and per-field result, not looping frame rejection.
- A PDF with vector or raster outline yields an editable closed parcel outline in plan space. Its map pane seeks checked geographic anchors from document/title/verified project/official coordinates and offers control-point alignment. Without confirmed georeferencing the plan geometry is not misrepresented as a parcel boundary at a Tunisian map location.
- For a verified georeferenced parcel: changing a vertex updates side lengths and metric area, remains inside allowed admin layer and generates source-aware change/review evidence; source geometry not silently rewritten.
- Suggested Arabic/French copy includes only real facts; edits survive recalculation; clear public preview excludes private fields.
- Backend remains authority for publication permissions, verification state and public boundary/doc visibility. Atlas Issue #20 remains the source for official cadastre inventory.
- Unit, UI/emulator, API integration, and physical-phone QA evidence attached to Draft PR #22 before release.
