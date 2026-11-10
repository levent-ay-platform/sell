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
