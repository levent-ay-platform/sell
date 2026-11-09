# ملاك — pixel-aware UI acceptance checklist

**Approved identity.** The consumer product is `ملاك`; repository slug remains `sell`. Never render the old SELL logo.

## Shared visual specification
- [ ] Pearl glass surfaces (#FFFDFB); Tunisian crimson (#B30D2D) and rose; clean sky and coastline. No brown dominance or dark-mode home.
- [ ] Arabic RTL first; French LTR variant, same content density and grid.
- [ ] Rounded search, red smart action, five rounded category pills, map controls at right.
- [ ] Hero map fills behind controls; detailed land polygon only when backed by verified geometry. Demo must say demo.
- [ ] Bottom property card: scenic photo, media strip, localized TND price, area, legal status, action; ability to dismiss/expand.
- [ ] Minimal floating navigation with central globe, tap actions work.
- [ ] Upper-right bougainvillea moves under 2° and 4px on low-motion devices; stop for reduced-motion, battery-saver, background.
- [ ] No login on initial discover, map or search. Contact/publishing requests authentication context.

## Screen parity
- [ ] Home matches 01 photo composition, spacing, hierarchy and token style.
- [ ] Property details matches 02/06 and clearly shows review scope and date when available.
- [ ] Smart discovery matches 03, with explainable filters; never invent listings.
- [ ] Favorites and comparison matches 04 with anonymous local storage option.
- [ ] Guest/settings matches 05, no phantom counters.
- [ ] Seller wizard and verification progress designed as pearl forms with strong red actions and explicit private evidence treatment.

## Engineering gates
1. Android debug builds on GitHub-hosted Ubuntu; no Mac/self-hosted runners.
2. Clear demo-only mode and no fake backend verification.
3. Real native globe map and licensed imagery are separate technology gates (not proven by mockup).
4. Accessibility: touch targets >=48dp, readable Arabic, RTL, 360dp layout, reduced motion, landscape fallback, dark text contrast.
5. No sensitive images or personal documents in public repo. No release APK signed with shared secrets.
6. At review time, screenshot compare with canonical hashed files; exact imagery and detailed overlays cannot be claimed until sources/assets are integrated.

## Track
- Current PR: brand lock and Android starter design system. Remaining screenshots require implementation and comparison against reference images.
