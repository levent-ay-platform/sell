# ملاك — Map-first immersive home (2026-11-09)

Status: User-approved interaction direction, **implementation prototype under PR #9**, final visual parity and real map SDK pending.

## Key interaction
- The **map is the 100% screen background hero**. All home controls are floating layers above, never a 190dp map embedded between scrolling content.
- French is the default visitor language (FR/LTR); Arabic remains an accessible follow-on localization.
- On first intentional map drag beyond touch slop: fade/slide away header, search, categories, property card and bottom nav; leave map tools/marker and an extremely small «Rechercher» / «Voir les biens» set.
- Interactions do not reappear automatically on idle; the user can focus on the map uninterrupted.
- Tap an actual property marker or «Voir les biens» to restore the property card. Tap «Rechercher» to restore search and filters.
- Zoom uses the same immersive presentation. Do not swallow taps on tools, cards or search.
- Animate opacity and translations for `~240–420ms` with reduced-motion settings respected; pause flower when battery saving / low RAM.

## Truth and limits
- The preview image can be panned for interaction acceptance, but **is not geospatial** and is NEVER labeled an actual map or real 3D view.
- The 3D tool stays explicitly unavailable until a licensed map SDK and a true camera event listener exist.
- On integrating a native globe/map, replace synthetic drag detection with `onCameraMoveStarted` / map camera gesture signals while keeping the same chrome state machine.
- The plot outline, owner verification, real weather and invented property metadata are prohibited as real-world facts. Synthetic QA content must be labeled.

## Acceptance
- [ ] Default home FR; hero map occupies the entire screen including behind the floating bottom property sheet.
- [ ] Drag > touch slop hides all secondary panels smoothly on 360dp and 412dp.
- [ ] Map tools continue working and tap on marker or recovery chip restores card.
- [ ] No accidental hide while typing or tapping categories, buttons, marker, or card.
- [ ] Correct inset behavior; no overlap with status/navigation bars.
- [ ] Physical Android screenshot + drag automation demonstration, not just a successful Gradle build.
- [ ] Real native map camera gesture parity before production launch.

## UI demo
The interactive HTML screenshot pair is created and tested in the originating conversation as `malak-map-hero-fr-v1`; it is a **design interaction proof**, not the production geo engine.
