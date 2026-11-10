# ملاك / Malak — implementation checkpoint (2026-11-10)

**Owner-approved source of truth:** `docs/design/reference/MALAK-HOME-APPROVED-EXACT.png`, SHA-256 `7cb5ea83bc0831d0d16ac6d0a219bd650c3ab668cbff19bc48e318b857d7bb47`, 543×1176 PNG. French default, no login on discovery, fixed photographic map in V1, native 3D deferred.

## Previously accepted technical checkpoints (not visual sign-off)
- Base `main`: `4c6b9b603934266defd48e48dc9c90dc5ff03804`.
- PR [#12](https://github.com/levent-ay-platform/sell/pull/12) Draft, no merge, no production.
- Last verified exact-head before this crop fix: `0809762f731d7de3a16e34eb084aedcda377ccc0`, [GitHub-hosted Android Actions #42761772022](https://github.com/levent-ay-platform/sell/actions/runs/42761772022): build PASS, emulator first-frame and swipe captures PASS.
- Rebuilt Compose home with approved coastal photo, logo, five French categories, real tappable card/tab controls. First frame locked. Touch drag hides chrome and tap recovers it.
- Fixed status-bar transparency, overlapping demo badge, upper floral/white villa images, property sheet dimensions and grabber, and calibrated header top to 17dp using actual Android captures.
- All sample property/weather/verification values remain **demo**; never claim title verification or real-time weather.

## Latest bounded correction (this branch)
- `CleanImmersivePhoto.kt` uses the same approved coast backing photo, crops away the baked blurred bottom area, and renders the sharp coastline when the sheet is hidden by a map swipe.
- Uses ContentScale.Crop, not distorted full-image scaling. Normal first frame remains unchanged.
- Distinction: photo/map mock; real camera, tiles, parcel geometry and 3D are unimplemented.

## Gate to proceed
1. New GitHub-hosted Ubuntu exact-head Android build and before/after swipe screenshots.
2. Inspect the **immersive screenshot**, confirm no blurred bottom and no broken visual presentation; compare normal home to canonical screenshot.
3. Keep PR Draft if exact visual mismatch remains. Never merge/deploy or distribute as final APK without owner sign-off.
4. Separate next milestone: actual georeferenced satellite map in Tunisia (vendor/asset rights, attribution, low-RAM fallback) while keeping locked overlays.

No Mac/self-hosted runners, no CRM production changes.

## Current bounded screenshot-driven polish
- Preserved the full-bleed sharp immersive-photo fix from exact-head `465664e3` (Android GitHub Actions #42764515161: PASS).
- Aligned header container 6dp upward, shifted/reduced brand mark, and moved the subtitle towards the exact reference.
- Search field inset adjusted independently; category row receives a narrower inset without changing French copy/order.
- Property card and bottom rail have more translucent pearl treatments; the demo badge is compact and the CTA is shorter.
- Restored the tiny real Tunisian flag detail from the immutable approved image in the static decorative layer.
- No new artwork, no geospatial/ownership-verified data. UI remains FR by default; image mock map V1 only.
- Requirement: capture Android initial home + after-drag at one exact HEAD; check layout improvement against same reference; no merge without user approval.

## Screenshot-corrective calibration 2026-11-10 (second-pass visual gate)
The first polish build `755648e6` was green at [Actions #42766890113](https://github.com/levent-ay-platform/sell/actions/runs/42766890113). Region RGB MAE on stable 543x1176 reference crops (lower is better):
- Property sheet **36.20 → 33.26** and bottom navigation **33.36 → 30.54**: kept the pearl/translucency/compact demo badge refinements.
- Brand/logo header **57.15 → 58.83** and search **48.17 → 52.73**: the combined vertical shift overshot. Don't claim visual acceptance.
- Corrective experiment: header container top offset **11dp → 23dp** (same image/fonts otherwise) to restore reference-aligned vertical placement. Emulator status values normalized to 10:24 and 92% for comparability (QA only).
- Use ONE exact-head Android screenshot pair to evaluate. Keep PR draft if any meaningful mismatch remains.
