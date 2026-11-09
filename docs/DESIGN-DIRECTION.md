# ملاك — Approved Light Tunisia Visual System (LOCKED)

Date: 2026-11-09
Status: **LOCKED by user: exact visual/layout baseline; approved product name ملاك** — the light coastal screenshot with Tunisian crimson, white pearl glass cards, bougainvillea at the upper-right, and an interactive 3D real-estate map is the binding art-direction reference for upcoming screen designs. This is a DESIGN decision, not authorization to deploy.

## The approved home reference
- Bright Mediterranean aerial coastline around Kelibia with clear turquoise sea, Tunisian town, real-estate markers and a subtly highlighted parcel.
- Bright, spacious, premium interface. **Never dark mode as the primary home design.**
- ملاك brand in refined deep red/crimson; tasteful Tunisia flag representation; clear Arabic and French.
- Warm ivory / pearl translucent cards, very restrained red / rose highlights and exquisite low-contrast shadows.
- Floating top search with voice/AI action, property-type pills, right-edge map tools, partial bottom property detail sheet, navigation with centered globe.
- Beautiful pink-red bougainvillea branches in the **upper-right**; natural, delicate movement.
- Avoid heavy gold/brown accents, gratuitous glow, clutter, visual noise or fake measurements/badges.

## Color tokens (candidate values to sample against the final reference)
- `brand.crimson: #B30D2D` — primary emphasis; Tunisian identity, not everywhere.
- `brand.red: #D21C36` — active/focused/CTA gradients where appropriate.
- `brand.rose: #FBE9EE` — translucent selected filters and accents.
- `surface.pearl: #FFFDFB`; `surface.glass: rgba(255,255,255,.86)`.
- `text.ink: #22242B`; `text.muted: #5B606A`.
- `sea.azure` is landscape imagery, never used for verification status.
- `trust.green: #16834A` — **only** for verified evidence status.
- `line.subtle: #E8E1DF`.
These are provisional design tokens; validate contrast/accessibility on real Android screens before freezing.

## Motion and performance
- Bougainvillea sways extremely subtly on an independent lightweight vector/layer (small 1–2° rotation or 2–4 px drift around a natural anchor, slow 5–8 s cycle), **not** a loop of heavy photo/video assets.
- Motion respects reduced-motion, battery saving, foreground lifecycle and low-RAM modes; never interferes with map gesture handling.
- 3D map gestures always take priority. Avoid GPU-heavy simultaneous blur, particles, animated blossoms and indefinite background rendering.
- No forced onboarding/login to explore; map/list available while imagery loads.

## Next design screens (coherent family)
1. **Property detail:** scenic listing gallery, scope/date of verified review, seller contact, price, exact area/unit and location.
2. **Smart discovery:** natural-language Arabic/Tunisian/French search, location/price/type constraints, interpreted filters, explainable recommendations, 3D travel on selection.
3. **Map/parcel focus:** zoomed land polygon, source/accuracy of boundaries, plot attributes, nearby services, switch 3D/2D.
4. **Compare properties:** 2–3 property comparison, price per m², location, verification scope, clear source of comparisons.
5. **Favorites and saved search:** no-login local saved items, location-based search reminders by opt-in only.
6. **Sell / add property:** short, delightful seller wizard: type, point, parcel number, rights, evidence, review, draft/save; no automatic public publish before verification.
7. **Ownership verification:** identity and authorized seller capacity, private evidence upload, case timeline, clear status and appeal; never show raw documents in buyer view.
8. **Direct contact:** privacy-first buyer contact flow with seller consent and rate limiting, no brokerage.
9. **Account/settings:** language AR/FR, privacy, motion/accessibility and property management.
10. **Reviewer console:** same brand but function-first, reviewer-only evidence and full audit; not a glossy public marketing screen.

## Truth and content rules
- All design-stage listings, prices, coordinates, property photos, review badges, numbers and property IDs are **illustrative fiction** until bound to actual approved backend data.
- A green/red badge is not a verification decision. Public verified status is computed by server and bound to specific property revision and case.
- Legal parcel lines must come from reviewed polygon metadata, not inferred from imagery.
- Any map-image visual in design mocks is conceptual until native map SDK demonstrates actual Tunisia coverage.
- Provide FR and AR RTL/LTR responsive variants after initial screen family approval.

## Implementation alignment
- Kotlin + Compose design-system tokens and reusable components to be chosen after review.
- Screens render one coherent map scene where practical; a full-screen 3D map can fall back to 2D/list on weaker devices.
- Home image reference is user-supplied in conversation; do NOT imply an image file is stored in the public repository.
- Avoid touching existing CRM, company servers, production deployments or self-hosted/Mac runners.

## Specific approved implementation requirements
- **MALAK/Mلاك is the sole consumer-facing brand:** Arabic masthead `ملاك`; never show old `SELL` wordmark in the actual app. No official Romanized brand was selected; do not invent one for public UI.
- The user's selected home reference is `01-home-approved-reference.png` (hash recorded in manifest); screenshots with old logo only remain archived design inputs.
- Layout fidelity, not a raster screenshot: edge-to-edge scenic map layer; delicate upper-right bougainvillea; compact branded masthead; floating search; property category pills; slim right controls; price/property card anchored above minimal navigation.
- Use **real Compose components**. UI controls must have behavior, avoid decorative dead controls; show explicit demo markers until backed by vetted API.
- All remaining screens use the *same* tokens, radii, shadows, Arab/French typography, icon proportions and spacing; avoid returning to a dark palette.
- Remove any broad `100% verified`, user ratings, market statistics or third-party amenity assertions not grounded by backend data.
- **Faithfulness gate:** screen captures on 360dp & 412dp Android, RTL and FR-LTR, small screen overflow, large text, accessibility, reduced motion, low memory; compare to approved design after connecting licensed imagery and native map.
