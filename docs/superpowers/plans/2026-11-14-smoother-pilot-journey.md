# Smoother pilot journey Implementation Plan

> Use superpowers:executing-plans inline; one final independent review.

Goal: improve the existing four-step land listing flow and completion without collecting more documents or repeatedly exercising OCR.

Architecture: retain existing Android/SQLite/Mac services. Reuse authenticated per-account identity reads briefly in memory and completed comparisons by exact input digest, analysis intent and deployed source revision. Preserve separate review evidence for different listing intents. Keep verification thresholds unchanged. Add a real submission receipt screen, field-directed validation, scroll reset per step and complete non-sensitive listing autosave.

Constraints: no new documents, models or infrastructure; no private OCR fields/images in Android preferences/Git/logs; no cross-account cache reuse; changed inputs/revision cannot reuse a prior result; stable analysis intent retries retain review ID; distinct intents retain separate reviews; expiry/session rechecked on cache hits; no repeated full emulator runs unless a concrete failure requires one.

Tasks:
- [x] RED→GREEN identity reuse and comparison replay/isolation/invalidation tests; implement bounded caching on existing endpoints.
- [x] RED→GREEN field completion policy; preserve listing description/location, reset scroll on step changes, disable redundant analysis and replace completed form with receipt + home/request navigation. Clear submitted draft only after confirmed server success.
- [x] Run pilot Python and Android tests/build; one independent review and one installed emulator journey using existing fixtures. Do not repeat unrelated legacy suites.
- [x] Deploy existing Mac runtime, produce versioned APK and record results/remaining limits.

Scope ruling: full private-document/OCR recovery after process death is deferred; this batch improves non-sensitive draft persistence and completion. No private results or photo URIs are persisted on Android. Private originals remain encrypted; reports reuse the existing private account-scoped review store.

Ledger: 24 pilot Python tests and 63 Android JVM tests green; APK/instrumentation builds green. No OCR model changes or new documents. Independent review found concurrent same-intent insertion and clone-renewed inference TTL; both corrected with RED→GREEN regressions. Atomic write transactions now preserve one review ID; cache expiry uses original analyzed_at. Identity memory reuse is capped at16 entries/15min and scoped to authenticated account; completed analysis reuse is exact-input/source-revision scoped for12h. Legacy clients without analysis intent get fresh review IDs while still reusing inference output. No unrelated legacy suites rerun.

Acceptance: source fc1585b deployed to existing Mac runtime with code backup. Final installed native journey PASS98.446sec, including missing-field notice, restored description/coordinates, real submission receipt, draft cleanup and home return. Initial driver failed to dismiss the IME; fixed with explicit bounded dismissal verification before gestures, rebuilt and reran only for that concrete failure. A continuation attempt could not reuse an exited app process and did not re-run OCR.

Public cache proof: two HTTPS replays returned the original review ID, zero new review records,1.259sec combined; submitted coordinates/description exactly matched the restored draft. APK0.4.2/versionCode9 SHA-256364a5162d8382e6cbc17a7e3bcae8df6943fc00e48b0bccee7f6ba188d6e8ae0. Local ignored report: dist/ACCEPTANCE-0.4.2.md. No new documents or real-customer submission mutations/publications.
