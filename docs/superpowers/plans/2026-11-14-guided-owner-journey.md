# Guided owner journey Implementation Plan

> Execute inline with superpowers:executing-plans; final independent review.

Goal: make the existing pilot a guided owner journey, using the current documents/engines and no new paid AI calls.

Design: keep four steps: Documents → Identity → Your listing → Preview. Explicit identity confirmation also authorizes one automatic comparison. Listing step leads with a concise result and an actionable next step; details stay expandable. Legacy owner-only controls stay outside the pilot. Media/name/description editing precedes preview; final preview is short. Successful receipt opens a dedicated My requests screen focused on the submitted request.

Recovery: persist only opaque review reference + submission idempotency key in account-specific preferences. Authenticated GET /api/pilot/reviews/:id restores the completed report, never raw evidence; submitted reviews return the existing receipt. Invalid/missing references offer start-over; transient failures preserve the pointer and offer retry. Restored reviews can be submitted without recapture; replacing documents invalidates their result and starts a new document set without exposing cached originals.

Pages: remove/replace a single page while retaining the other pages, explicit order controls and updated labels. Changing document pages invalidates comparison; no silent deletion of following pages.

Constraints: no private OCR fields, scan bytes, photo URIs, tokens or credentials in local preferences; explicit consent before remote comparison; scoped restore authorization; no forged name match, area/share conversion or document strength approval; retain v5 thresholds and existing durable evidence.

Tasks:
- [ ] RED→GREEN scoped report recovery/submitted receipt tests and page-list policy tests; implement server GET and parser reuse.
- [ ] Implement Android guided analysis, local opaque recovery, safe page changes, concise result/details and candidate-area confirmation; move media editing before preview; dedicated focused requests navigation.
- [ ] Pilot Python/Android checks and build; final independent review; one installed journey + process restart recovery without new OCR.
- [ ] Deploy existing Mac and allow only new authorized GET on existing isolated proxy; build APK and record actual limits.
