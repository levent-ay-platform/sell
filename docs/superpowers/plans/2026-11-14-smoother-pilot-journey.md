# Smoother pilot journey Implementation Plan

> Use superpowers:executing-plans inline; one final independent review.

Goal: improve the existing four-step land listing flow and completion without collecting more documents or repeatedly exercising OCR.

Architecture: retain existing Android/SQLite/Mac services. Reuse authenticated per-account identity reads briefly in memory and completed comparisons by exact input digest, analysis intent and deployed source revision. Preserve separate review evidence for different listing intents. Keep verification thresholds unchanged. Add a real submission receipt screen, field-directed validation, scroll reset per step and complete non-sensitive listing autosave.

Constraints: no new documents, models or infrastructure; no private OCR fields/images in Android preferences/Git/logs; no cross-account cache reuse; changed inputs/revision cannot reuse a prior result; stable analysis intent retries retain review ID; distinct intents retain separate reviews; expiry/session rechecked on cache hits; no repeated full emulator runs unless a concrete failure requires one.

Tasks:
- [x] RED→GREEN identity reuse and comparison replay/isolation/invalidation tests; implement bounded caching on existing endpoints.
- [x] RED→GREEN field completion policy; preserve listing description/location, reset scroll on step changes, disable redundant analysis and replace completed form with receipt + home/request navigation. Clear submitted draft only after confirmed server success.
- [ ] Run pilot Python and Android tests/build; one independent review and one installed emulator journey using existing fixtures. Do not repeat unrelated legacy suites.
- [ ] Deploy existing Mac runtime, produce versioned APK and record results/remaining limits.

Scope ruling: full private-document/OCR recovery after process death is deferred; this batch improves non-sensitive draft persistence and completion. No private results or photo URIs are persisted on Android. Private originals remain encrypted; reports reuse the existing private account-scoped review store.

Ledger: 24 pilot Python tests and 63 Android JVM tests green; APK/instrumentation builds green. No OCR model changes or new documents. Independent review found concurrent same-intent insertion and clone-renewed inference TTL; both corrected with RED→GREEN regressions. Atomic write transactions now preserve one review ID; cache expiry uses original analyzed_at. Identity memory reuse is capped at16 entries/15min and scoped to authenticated account; completed analysis reuse is exact-input/source-revision scoped for12h. Legacy clients without analysis intent get fresh review IDs while still reusing inference output. No unrelated legacy suites rerun.
