# Guided owner journey Implementation Plan

> Execute inline with superpowers:executing-plans; final independent review.

Goal: make the existing pilot a guided owner journey, using the current documents/engines and no new paid AI calls.

Design: keep four steps: Documents → Identity → Your listing → Preview. Explicit identity confirmation also authorizes one automatic comparison. Listing step leads with a concise result and an actionable next step; details stay expandable. Legacy owner-only controls stay outside the pilot. Media/name/description editing precedes preview; final preview is short. Successful receipt opens a dedicated My requests screen focused on the submitted request.

Recovery: persist only opaque review reference + submission idempotency key in account-specific preferences. Authenticated GET /api/pilot/reviews/:id restores the completed report, never raw evidence; submitted reviews return the existing receipt. Invalid/missing references offer start-over; transient failures preserve the pointer and offer retry. Restored reviews can be submitted without recapture; replacing documents invalidates their result and starts a new document set without exposing cached originals.

Pages: remove/replace a single page while retaining the other pages, explicit order controls and updated labels. Changing document pages invalidates comparison; no silent deletion of following pages.

Constraints: no private OCR fields, scan bytes, photo URIs, tokens or credentials in local preferences; explicit consent before remote comparison; scoped restore authorization; no forged name match, area/share conversion or document strength approval; retain v5 thresholds and existing durable evidence.

Tasks:
- [x] RED→GREEN scoped report recovery/submitted receipt tests and page-list policy tests; implement server GET and parser reuse.
- [x] Implement Android guided analysis, local opaque recovery, safe page changes, concise result/details and candidate-area confirmation; move media editing before preview; dedicated focused requests navigation.
- [x] Pilot Python/Android checks and build; final independent review; one installed journey + process restart recovery without new OCR.
- [x] Deploy existing Mac and allow only new authorized GET on existing isolated proxy; build APK and record actual limits.

Ledger:24 Python and65 Android JVM tests passed. Fresh independent review reported no Important/Critical findings. Mac b06cb50 deployed with existing code backup; isolated Huawei Malak Nginx backed up, new GET-only review route validated and reloaded. No CRM changes or new infrastructure.

Installed analysis checkpoint PASS40.608sec. Completed result survived force-stop/relaunch. Final resume-to-submission/request navigation PASS21.13sec, reusing the same review with zero new review records. Saved listing fields/coordinates/description reached the server. Public HTTPS submitted-review recovery also passed without original-file download. Earlier automation failures came from stale objects/IME detection; driver now resolves labelled inputs afresh and uses actual activity focus/window insets. A stale accessible Continue label on the visual Send action was observed, fixed by keying footer composition to step, and the final test passed.

Final artifact/report: dist/malak-0.5.0-guided-owner-pilot.apk and dist/ACCEPTANCE-0.5.0.md (ignored local deliverables). SHA-256319cbf6625c291980876c3fada232250df9da00f1711c62e1d5b9f5b12796eef. Recovery covers completed analysis and non-sensitive listing fields; photo selections, in-flight inference and downloaded originals remain outside recovery. Source OCR thresholds/models and real-document accuracy were not changed or re-benchmarked.
