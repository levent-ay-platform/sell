# Visual Release Blocker — ملاك Android (2026-11-09)

**Status: BLOCKING. Owner feedback: current prototype visually fails the explicitly approved home design.**

## What is wrong
The delivered QA Android screenshot shows a minimalist, synthetic `Canvas` shore instead of the approved photorealistic coastal/satellite 3D scene. The header overlaps status bar icons, flowers look like simple red circles, category icons are primitive, there is no real parcel overlay tied to evidence, no media-strip/gallery, and the navigation panel does not match the approved design. Passing `assembleDebug` did not demonstrate UI parity.

**Canonical reference:** `01-home-approved-reference.png` in `malak-design-reference-v1.zip`, hashes in `docs/design/REFERENCE-MANIFEST.json`. Do not swap in a different design or dark theme. Product brand **ملاك**.

## Correct approach
1. Implement a genuine native 3D geospatial view with satellite/aerial imagery licensed for Tunisia. Do not fake a satellite map or assert a verified boundary when none exists. Mapbox Android requires a public app access token and has mandatory attribution/telemetry UI; record vendor/region choices and account setup before integrating. A low-end-friendly map/list fallback is mandatory.
2. Preserve all functional overlays as interactive Compose UI: compact header, bright glass search, refined compact category chips, right map controls, clear plotted polygon and red pin only for legitimate demo/verified geometry, properly overlaid photo/thumbnail property drawer, minimalist navigation with Tunisia globe control.
3. Render safe insets for status/navigation bars at Android 15+, ensure flower overlay stays below the status bar and implement a real/lightweight branch asset rather than cheap circles.
4. Remove fabricated verifications, fake weather, fake 3D zoom and artificial service facts from any release UI.
5. Implement a capture-and-review harness for 360dp, 412dp, AR RTL + FR LTR and low-memory/reduced-motion. Record actual images + human acceptance; **no claim of pixel parity from image-generative mockups alone**.
6. Continue Issue #2 trust API. Only effective verified data may show `verified` status.
7. Never ship an APK for review as visually done if these checks fail. All builds remain **PROTOTYPE** until proven.

## Release gate
- [ ] Native satellite/3D scene actually loads on supported phone; controls operate on camera.
- [ ] Screenshot crop/layout matches the selected approved art direction.
- [ ] No status-bar collision; high-quality flower asset and adaptive motion.
- [ ] Card includes photo + thumbnail strip, property facts, verified state only from server.
- [ ] Correct responsive navbar with Tunisia globe.
- [ ] Actual Android screenshot comparison at both sizes; reviewer sign-off.
- [ ] Performance and accessibility measured on Android device.

## Current engineering state
The following safe improvements are included in this correction PR: system inset padding, removal of fake live weather, honest prototype artifact name, and hard BLOCKER notices. These are **not** a claim that UI matching is solved.

## Source truth
- User screenshot supplied 2026-11-09.
- Source files: `apps/android/app/src/main/java/tn/malak/app/ui/{MalakApp.kt,ScenicBackdrop.kt}`.
- Tracking issue: #7.
