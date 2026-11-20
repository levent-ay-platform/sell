# MALAK 2.0 — Premium Mobile UI/UX System

> **2026-11-20 owner override — brand renamed to Amlak:** User-facing app/launcher/search masthead is **Amlak**, superseding previous use of «ملاك» or «Malak» as the consumer brand. Crimson/pearl, premium Mediterranean palette and 2D map-first decisions remain. The internal Android application ID `tn.malak.app` is intentionally retained for installation continuity and data safety; do not treat this as the consumer-facing name.\n\n
**Status:** implementation design contract for Draft PR #22, 2026-11-20. Not a claim of completed functionality, build, or visual acceptance.
**Canonical decisions:** [Issue #21](https://github.com/levent-ay-platform/sell/issues/21), [Atlas #20](https://github.com/levent-ay-platform/sell/issues/20), `docs/DESIGN-DIRECTION.md`. 2D map-first, sales-only closed launch Nabeul/Kélibia, Arabic RTL + French LTR. This document extends the design to all screens; it does not supersede data/security constraints.
**User direction:** Full identity redesign including system back navigation, notifications/alerts, account, full screens, icons, typography, elements, gestures and every UI state. No mandatory login for browsing.

## 1. Brand foundations

- **Consumer masthead:** `ملاك`; no old SELL logo. Keep established crimson/pearl Mediterranean light design, no dark-first home. Formal brand red `#B30D2D`, active red `#D21C36`, rose `#FBE9EE`, pearl `#FFFDFB`, text `#22242B`, muted `#5B606A`, border `#E8E1DF`, verified green `#16834A` only when backed by a scoped decision. Semantics over decoration. Previous UI accents in teal must not leak into the new design.
- **Typography:** evaluate IBM Plex Sans Arabic or Cairo for AR, Inter for FR/numerals; choose a bundled licensed, metrically tested font family after QA. Styles: Display 28/34 semibold; headline 22/28 semibold; title 18/24; body 14–16/21–24; label 12–13/18. Never rely on tiny 10sp labels for primary navigation. Tabular numerals for TND, m² and parcel IDs. Support large font scaling, Arabic shaping, diacritics and correct mixed-script Bidi.
- **Spacing:** 4dp base grid, horizontal content 16dp, minimum 48dp tap targets, safe-area insets, 16–24dp radii depending on hierarchy, subtle 1dp dividers, restrained shadow (not simultaneous blur/glow). Keep foreground UI readable on bright maps.
- **Icon language:** one consistent 24dp outlined family across tabs, app bars, actions, empty states, and alerts. Selected state can use filled counterpart; never mix unrelated visual families. Every icon action requires accessibility labels AR/FR and functional wiring. Do not use emoji as UI icons.
- **Scene/art direction:** full-screen map, airy ivory floating controls, restrained crimson emphasis, very subtle vector bougainvillea upper-right from approved visual reference *when not obscuring essential map controls*, animated only when accessible and performant. The 2D map and actual geometry are native, never a static raster facsimile.
- **Motion:** 150–240ms adaptive transitions for sheet, tab, card, detail, alert; no long splash/no forced onboarding. Honor reduced motion, battery saver, low RAM, and back predictive gesture when supported. Loading skeletons avoid layout jumping.

## 2. Navigation / always-on UI

**Five-tab signed-in shell (compact at 360dp):**
1. `الخريطة / Carte` — default full-screen 2D map, viewport and layers preserved.
2. `استكشف / Explorer` — list-mode discovery; identical eligible listing/filter data as map.
3. `أضف عقار / Publier` — visually distinguished central add action; opens seller draft flow, authentication only when required.
4. `التنبيهات / Alertes` — inbox of actual user alerts (no fake counters); login gating only for private user alerts.
5. `حسابي / Compte` — guest/account hub, saved searches, favorites, properties, requests, documents, settings.

**Guest shell:** map/list fully accessible; account shows guest status + sign in; saved local favorites may be available per existing policy; alert tab shows legitimate opt-in/sign-in state rather than phantom alerts. No fake badge or seeded production notifications. Private account screens only post-auth.
**Contextual nav:** property details, documents, full-screen PDF, scanner, crop/correction and owner editor are stack destinations, not tabs. Top app bar has logical back arrow (auto-mirrored RTL/LTR), concise title and only meaningful context actions. Tabs persist state, never restart the map with each visit.
**Android system Back:** priority: close keyboard/search suggestions → dismiss filter/layer modal → collapse/close parcel property bottom sheet → leave details to exact prior viewport/selection/filters → leave nested owner wizard using protected draft/unsaved-change dialog → root map; at root normal OS exit/background behavior. Escape login returns to pending action without losing context. Handle deep-link to unavailable/withdrawn listing via safe detail fallback, not wrong property.
**Window ergonomics:** correct status/navigation bar contrast; edge-to-edge map respecting safe drawing, keyboard `imePadding` and `BringIntoView`, bottom bar never covers textfield or Send/Submit actions. All 360dp/412dp screens tested with AR RTL/FR LTR and 1.3x font.

## 3. Entire screen inventory & expected behaviors

| Screen | Essential elements | States and actions |
|---|---|---|
| Map home | refined `ملاك` masthead, location search, compact kind chips, filter, layers, recenter, map/list toggle, price pins, parcel overlays with legend | initial/loading/map tiles fail/list fallback, pan → Search this area, zoom, selection → compact property bottom sheet, permission only when tap Near me |
| List / explore | search and active filter chips, sort, vertically readable image cards, TND/m², location accuracy | loading/empty/error/retry/page cursor, tap detail, parity with map filters, preserve scroll |
| Property quick sheet | thumbnail/gallery entry, price, surface, location label, save, detail | one-third-height peek, expandable, swipe dismiss, back restores map |
| Property detail | image gallery, price, validated facts, description, boundary tab/legend, documents, contact, favorite, compare | secure/approved content only, no unfounded verified wording; errors and withdrawn status explicit |
| Parcel view | real georeferenced polygon when validated, separate plan-space sketch when not, measured sides/area metadata, clear geometry confidence | official vs candidate vs user outline styles, boundaries never invented or misrepresented |
| Documents / PDF | review-approved, appropriately redacted document tiles, full-screen PDF zoom/page count/back/download when permitted | checksum/version, empty/revoked/failure, cannot leak original private evidence |
| Seller add | stepper, autosaved draft, type/location/details/photos/identity front/private documents/OCR/correction/consent/review/submit | cancel/resume safely, upload/retry/permission errors; seller authentication only on protected action |
| My properties / requests | DRAFT/PENDING/NEEDS_INFO/APPROVED/PAUSED with legitimate server statuses, cards + detail timeline | update/revise/withdraw where authorized; explicit publication state distinct from case approval |
| Alerts center | actual read/unread, grouping by Today/Earlier with localized dates, category chips, safe deep-link, mark read/delete when supported | genuine empty state, opt-in explanation, pull-to-refresh or retry; server-defined unread count only |
| Saved searches / Radar | search name/location/radius/filters, on/off, last match, alert preference | opt-in notification permissions, dedup by publication revision, manual place if geolocation denied |
| Account | profile/guest state, own properties, saved items, documents, notifications preferences, language, privacy, sign-in/out | account deletion/privacy controls only if backend supports; actions never decorative |
| Settings | AR/FR instant direction switch, notification categories, display motion/accessibility, privacy, app info | persist preference, confirm risky actions, maintain screen stack |
| Auth | purposeful compact sign-in sheet or screen, accessible number + code, error/resend/timeout | never force login on app launch, no production `1234` bypass, cancel returns to map |
| Review / verification status | scoped document/parcel evidence status and why it is pending | neutral evidence wording, no universal property verification guarantee |

## 4. Reusable Compose components (single design package)

`MalakTheme` + tokens (`ColorTokens`, `Typography`, `Shapes`, `Spacing`, `Elevation`, `Motion`) and:
`MalakTopBar`, `MalakBottomBar`, `MalakIconButton`, `MalakSearchField`, `MalakFilterChip`, `MalakFloatingCard`, `MalakPriceMarker`, `MalakPropertyPeekSheet`, `MalakListingCard`, `MalakPrimaryButton`, `MalakTextField`, `MalakStepper`, `MalakDocumentCard`, `MalakStatusChip`, `MalakAlertRow`, `MalakSettingsRow`, `MalakEmptyState`, `MalakErrorState`, `MalakLoadingSkeleton`, `MalakSnackbar`, `MalakConfirmDialog`, and `MalakAccountAvatar`.
All controls must be real actions, have disabled/loading/error/pressed/focus state, content descriptions, contrast checks and screen reader order. Do not silently use fallback icons/colors from legacy `MalakHome`.
Notification channels split at least: `listing_matches` (Radar opt-in), `seller_case_updates`, `account_security`; never claim delivery without backend, platform permission and opt-in. Badge only if genuine unread count.

## 5. Engineering sequence (on PR #22; no production changes)

1. **Audit, screenshot and fix entry flag:** verify PR head, local worktrees, Gradle variant + actual installed APK; ensure V2 QA sets `-PmalakV2Discovery=true`. Keep old experience behind flag until acceptance; users should see map first (guest).
2. **Design primitives:** add typed theme tokens, typography and icon map; build/preview sample components with Compose UI tests; preserve locked art direction, use dynamic typography correctly.
3. **Navigation/back state:** centralized route stack, app bars, 5-tab shell, retained map viewport/filter/list scroll, keyboard and modal back sequencing.
4. **Screens:** map + list + peek sheet → detail/docs/PDF → seller/OCR/drafts → notifications/radar → account/settings/auth. No dead controls or fabricated data; use explicit unavailable state if backend endpoint is not there.
5. **Final QA gate:** visual snapshots at 360dp and 412dp Arabic RTL and French LTR; accessibility TalkBack/touch 48dp/large text; back from every modal/page/keyboard, offline and slow connection, low-RAM and reduced motion; map panning and polygon labels; deep links and auth expiry. Verify Android screenshot + UI test evidence on emulator *and* physical-device QA before production.
6. **Governance:** draft PR only, no merge/deploy, no modification of Atlas archives, no CRM change. Rollback via feature flag. Report actual implemented/mocked/not implemented separately.

## 6. Current PR #22 code inventory — observations, not future claims

- `apps/android/app/build.gradle.kts`: `malakV2Discovery` defaults `false`; QA must prove the *actual installed variant* has it on.
- `MalakApp.kt`: legacy `MalakHome`, generic empty `map`/`favorites` paths, `MalakMorePage`, `PilotOtpGate`, and a V2 `ExploreScreen` behind the flag. Bottom nav presently does not provide a complete alerts/account design.
- `ExploreScreen.kt`: Compose/MapLibre candidate map, simple header, filter chips, list toggle, markers, layers sheet; still skeletal Atlas layer controls.
- `DiscoveryComponents.kt`: simple row listing cards and single-column property detail. Document/parcel preview exists in dedicated V2 modules.
- No claim that personal alerts, account workflows, Radar or comprehensive navigation have already been built.
