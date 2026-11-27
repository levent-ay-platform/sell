# Codex QA handoff — Amlak 2.1 (23 November 2026)

> **READ FIRST before modifying the app.** This is an evidence-based handoff of the work completed by ChatGPT via AI Agent CTR after Codex reached its usage limit. The project remains in **Draft PR #22**; neither production nor original worktrees were changed.

## Source of truth and safety

- [Issue #20 — ATLAS MASTER](https://github.com/levent-ay-platform/sell/issues/20) is the cadastral/GIS source of truth, separate from Android. [Issue #21 — Amlak product](https://github.com/levent-ay-platform/sell/issues/21) now explicitly approves **publish first (clearly unverified), verify later from the same listing ID**; V2 publication permission and official cadastre provenance remain separate.
- Last **code** SHA tested on Mac and emulator: `1bbf9a74470c04a81257ec6c3ebdaf0af8b9a990`. The commit adding this handoff document is documentation-only and changes PR HEAD, not the Android binary.
- On LEVENT-MAC the original Codex Amlak worktree is `/Users/levent/.codex/worktrees/malak-v2-map-parcel-foundation/amlak pro`, older local HEAD `07e20a7` with **83 local porcelain entries**; Atlas original worktree `/Users/levent/.codex/worktrees/amlak-atlas-review-layer/tunisia-atlas` has **8 local entries**. DO NOT reset, clean, stash, merge or overwrite either.
- The separate clean test worktree is `/Users/levent/.codex/worktrees/amlak-gpt6-safe-continuation-20261122` at `1bbf9a7`, previously confirmed clean.
- Hashed private recovery snapshots of both original worktrees: `/Users/levent/Projects/amlak pro/.runtime/recovery/20261122-amlak-codex-quota-recovery-v1/`. All snapshot checksums were verified. Never upload private document archives or identity data.

## Changes actually made and verified

1. Updated the approved product plan and `docs/CURRENT.md` to distinguish a publicly visible **unverified Pilot listing** from a reviewed V2 parcel. No SQL enum rename and no bypass of existing legal review or private document requirements.
2. Fixed an actual macOS Python compatibility failure in `services/verification/qa/pilot_workflow.py`: when `hashlib.scrypt` is unavailable, use `cryptography`'s RFC 7914 Scrypt with the **same** n=16384, r=8, p=1, 64-byte output. Added a deterministic native/fallback parity test in `test_pilot_workflow.py`. This must not change persisted admin password hashes. Commits `3e1e1aa` and `1bbf9a7`.
3. On LEVENT-MAC under a **separate virtual environment**, reran `test_pilot_workflow.py` **11/11 PASS** and `test_workflow_http.py` **6/6 PASS**, covering synthetic login/owner scoping, review, direct listing + Atlas bridge, document revocation, and cross-origin protections. **17/17 focused tests PASS.**
4. GitHub CI on exact code SHA `1bbf9a7`: Android [run 45061725980](https://github.com/levent-ay-platform/sell/actions/runs/45061725980) **SUCCESS**, Foundation [45061725948](https://github.com/levent-ay-platform/sell/actions/runs/45061725948) **SUCCESS**, Verification [45061725965](https://github.com/levent-ay-platform/sell/actions/runs/45061725965) **SUCCESS**.

## Android real QA — confirmed, not simulated

- Used a **new and isolated AVD**, `Amlak21GithubQA_20261122` on `emulator-5568`. The first boot took **362,027 ms** per emulator log; the second boot returned `sys.boot_completed=1`. Do not misdiagnose it as failing; allow a full cold boot. Original emulators `5564` and `5566` were left untouched.
- From workflow map-first artifact ID `14770245677`, downloaded ZIP SHA256 `fd5bf148f1cc2239d39028e43af0ef80cf8010b5a3286188b307815bdda5ee95`; extracted APK **43,466,572 bytes**, SHA256 `8172186faf082696724d2c2f51fd5cd33a2661eef4285a68e3c44405b84c1781`. Verified on LEVENT-MAC at `/Users/levent/Projects/amlak pro/.runtime/qa/Amlak-GitHub-1bbf9a7/Amlak-1bbf9a7-QA.apk`.
- Confirmed the new QA device had **no existing app**, `adb install -r` returned **Success**, `am start -n tn.malak.app/.MainActivity` returned start Intent and app PID `4423` was observed.
- **French map genuinely rendered MapLibre base tiles**, terrain shapes and place labels around Zuwara / Az Zawiyah; screenshot at `/Users/levent/Projects/amlak pro/.runtime/qa/Amlak-GitHub-1bbf9a7/latest-french-map.png`. Search, filters, industrial zones, radar, map layer control, and five bottom destinations appeared in UI XML. OpenFreeMap Liberty style, vector manifest, sprite and sample raster all returned HTTPS 200 / valid TLS from the Mac (not a guarantee on every device).
- French **Compte** tab opened with correct branding, guest browsing message, login and expected account actions. French **Paramètres** opened, displaying language, privacy text and notification opt-in disclaimer.
- Tapped **العربية** from settings: **Arabic RTL settings worked**, with back arrow and title moved to the right, localized labels and privacy/notification texts. Arabic **رجوع** returned to **حسابي** showing “أهلاً بك في Amlak”, “تسجيل الدخول”, “طلباتي”, “المفضلة”, “أضف عقار”, “الإعدادات” and all five Arabic bottom tabs with mirrored order. This was checked using real UI hierarchy on the running app.
- **Crucial:** The map showed “Les annonces sont indisponibles…” because **this particular generic CI QA APK has no authorized live listing source configured**. This is not evidence that Pilot listing publishing or production backend works. Do not treat synthetic listing tests as real live listings.
- A tap to begin checking Arabic Alerts was issued at the end of the session but there is **no confirmed Alerts screen inspection**, so do not mark it passed. There were occasional timeouts in the CTR relay; retried reads later succeeded.

## Outstanding issues / hard gates

1. **Server:** Read-only GET `https://malak-api.ayasofia.shop/api/public/listings` gave HTTP **200**, but `GET /api/public/capabilities` gave HTTP **404** (TLS valid). Direct publishing **not proven live**. Need staging capability/authenticated safe enrollment, no production flag flip or database migration without approval.
2. **Authentication:** common OTP `1234` only for explicitly authorized closed Pilot, never for public phone-number ownership or public production registration. No confidential evidence in public browsing.
3. **Android visual QA:** remaining AR/FR screens (Explorer, Publish, Alerts, full listing details, parcel PDF, back nav, IME keyboard, 130% text and different widths) and physical phone not yet fully accepted. Do not claim all pages passed.
4. **Cadastre:** a plan-space shape or user-calibrated length/area is **an estimate**, not an approved official parcel polygon. Public boundaries require geo-referencing, reviewer approval, provenance, consent and withdrawal handling; Atlas layers must keep source labels.
5. **Release:** No merge of PR #22; no production deployment; no modifications of existing dirty worktrees or source Atlas archives.

## Resume safely

1. Read this file and the two Issues. Inspect the current PR head and compare it with the clean Mac QA worktree (it may be one documentation commit behind).
2. Continue **read-only UI and staging tests** on the isolated QA emulator `5568`; record specific screenshots + PASS/FAIL, especially Alerts and Publish. Avoid deleting existing emulator app data.
3. If code fixes are necessary, create small test-covered commits on the Draft PR only, preserving the private recovery snapshot. Distinguish backend-disabled QA from UI defects.
4. Add exact results and blockers to PR #22 and `docs/CURRENT.md`; request rollout approval before changing public services.

**Verified outcome:** latest map renders, French account and French/Arabic settings work, Arabic RTL/back/account works, CI and 17 Pilot tests pass. **Not verified:** all screens, true public Pilot publishing, real official title/document workflows or production deployment.
