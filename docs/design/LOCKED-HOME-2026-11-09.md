# ملاك / Malak — exact locked home screenshot

**USER APPROVED, STRICT VISUAL LOCK — 2026-11-09.**

## Source of truth

Use the exact user-uploaded image(4).png, NOT any assistant-generated variant and NOT the previous screenshot references.
Reference size **543×1176 px**. SHA-256 **7cb5ea83bc0831d0d16ac6d0a219bd650c3ab668cbff19bc48e318b857d7bb47**.
Portable exact original: MALAK-APPROVED-DESIGN-LOCK.zip supplied in this conversation.
The exact source PNG is now in this public repository at `docs/design/reference/MALAK-HOME-APPROVED-EXACT.png`, with SHA-256 checked before any QA comparison.

## Non-negotiable visual contract

1. App name ملاك / Malak, French default, guest browsing without registration.
2. Original bright turquoise Mediterranean sunset photo, fuchsia flowers, Tunisian home accents, original header/logo.
3. Search and five exact category pills in order: Terrains, Villas, Appartements, Fermes, Locaux commerciaux.
4. Keep the screenshot's composition, icon placement, control shape, spacing, shadows, text hierarchy and coral red.
5. Large photo-map hero, on-photo parcel highlight and pin, four vertical right-side map tools.
6. Same weather-style pill lower left; must not claim live weather unless sourced.
7. Original pearl bottom card with landscape thumbnail, demo Vérifié badge, Terrain à Hammamet, 900 m², Proche de la mer, 540 000 TND, red Voir les détails CTA.
8. Original translucent four-item navigation: Accueil, Carte, Favoris, Plus.
9. Do not add fifth tab, center globe, new dashboards, generic icons or old flat Canvas scenery.
10. Static photographic map is explicitly allowed during V1; do not claim working 3D. It will be replaced only as a separate approved engineering step.

## Interactions without changing initial appearance

Real map drag hides secondary chrome with elegant 240-420 ms fade/slide; map remains full screen. Tap a property marker or restore control to show the sheet again. Motion must honor reduced-motion, battery-saver and low-memory conditions.

## Implementation must remain interactive

The screenshot is a VISUAL QA REFERENCE, not a one-image fake UI. Implement actual tappable Compose widgets with the locked screenshot as the comparison source. Demo preview may use a licensed or approved photo background. Real 3D follows in a later milestone.

## Hard gate before any acceptance claim

Compare captured Android initial home at 360dp, 393dp and 412dp against this image. Capture swipe collapsed and tap restore too. Visual match requires human review and explicit user signoff; build PASS alone is insufficient. Never present demo properties/owner verification as live.

Previous home design directions and the open draft PR #9 are superseded by this EXACT reference. Do not merge PR #9 as visually approved without reworking it for the new locked image.

**Any nontrivial visual change needs explicit user authorization.**
