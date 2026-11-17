# ملاك UI/UX V2 — first consumer-cleanup implementation

Scope of this code batch: coherent consumer layout/language/navigation cleanup, not release or deployed OCR.

- Language selection: French default, Arabic RTL; locale-specific Android resources and Compose LocalLayoutDirection. UI choice persists locally.
- Four fixed bottom destinations (home/map/favorites/more) on correct reading side; no fifth compare tab or fake central globe.
- Unicode-as-icons removed from active screens, replaced with vector Material icons and mirrored semantic back/next navigation. Navigation targets are functional.
- Empty-state first design: no made-up property listings, sellers, pricing, fake map, fake weather, fake 3D or false approval badge; map has an honest unavailable state. Search/category components are functional in selecting filters but cannot fabricate listings.
- Seller wizard: property fields, capacity, PDF/photo chooser, review and local private draft save of **only** non-sensitive form text. No document bytes, URI permissions, OCR, auth token or title certificate stored as draft. The selected documents remain local, not uploaded.
- Technical QA API URL, UUID, bearer-token fields, fake OCR results and debugging labels **removed from all customer UI**, including debug APKs. The backend/client library still exists but is not wired to public UI without real session/case provisioning.
- No claim of visual parity with the approved reference: original user-approved photo asset must be hash-verified before putting it in the app. Current Home uses a clean neutral hero and empty state until authentic listing/image data exist. This is a safer intermediate state than counterfeit property cards or maps.

## Acceptance remaining

1. Real Android screenshot capture 360/393/412dp in ar/fr; check system insets, mirroring, font sizes, accessibility, large text, physical low-end device.
2. Recover and verify **exact** original approved home asset (locked SHA) and replace neutral placeholder without demo property claims; design compare/human signoff.
3. Real authenticated seller account, privacy consent, property draft/case persistence, backed OCR and list/map data. Not presently available to a real user; do not show test credentials to them.
4. Formal owner identity/CPF legal title cross-check stays separate and cannot be inferred from OCR.

Do not deploy to Huawei, CRM, Asterisk, or migrate production databases from this PR. Keep draft until independent approval. Code build PASS is not visual QA proof.
