# Current pilot: phone accounts, real Mac OCR, administrative publication

This owner-approved flow replaces historical requirements for a separate verified owner login or legal approval before PILOT administrative publication. It does not change what legal verification means.

- A normalized +216 phone number is the unique customer account, across devices. `1234` is the temporary pilot code; replacing it with OTP later preserves account IDs. It does not verify control of the number.
- One server bearer covers front identity preview, land-document comparison, property photos, submission and own request status. A completed incomplete/ambiguous extraction can be reviewed; service failures require retry.
- Existing Mac v5 reads the identity. Native Mac Vision reads land photos or up to eight PDF pages; the existing TS document-family/role matcher supplies assessment and sourced private facts. Low-confidence fields remain visible and are not autofilled.
- Completed analysis creates a private review ID with AES-GCM encrypted originals. Submission links that review to customer contact and editable listing data. Private SQLite accounts/requests live outside the repository; the key is a separate 0600 file in a 0700 directory. Back up that directory and its key together. No secrets or private evidence in GitHub.
- Separate email/password administrator accesses `/admin`. Original evidence requires an admin bearer. Public `/api/public/listings` and `/api/public/photos/:id` expose only published fields and re-encoded PROPERTY photos, never identity/title evidence.
- Publication is an admin decision, not legal ownership approval. The pilot uses a separate durable workflow store and leaves the existing PostgreSQL legal verification tables/triggers unchanged. Admin may publish uncertain/conflicting analysis with a review note, request changes, reject or pause. A version check rejects stale decisions.
- Initial public cards omit customer phone/name; these remain visible to the administrator. Customer and admin UIs remain AR/FR and English respectively.

## Runtime

Install `qa/requirements-runtime.txt` alongside the existing v5 packages; compile `ocr/mac_vision.swift` with `swiftc -O`. Node >=22.6 runs `qa/compare_mac_pair.mjs` with strip-types. `qa/runtime.json` (local deployment only) configures `MALAK_PILOT_DATA`, `MALAK_PYTHON_DEPS`, `MALAK_VISION_BIN`, `MALAK_NODE_BIN`, and `MALAK_PUBLIC_ORIGIN`. The listener remains on loopback 18443 behind the existing TLS ingress.

`scripts/deploy-mac-pilot.py` backs up only the existing pilot source, updates it and optionally signals that exact listener for launchd restart. It does not install/retrain models, alter CRM or change launchd configuration. Never point it at an unrelated runtime.

## Validation

- Python: `PYTHONPATH=<runtime-deps>:services/verification/qa <v5-python> -B -m unittest discover -s services/verification/qa -p 'test_*.py'`
- TS: `TMPDIR=<real-non-symlink-directory> node --experimental-strip-types --test services/verification/test/*.test.ts`
- Android: Gradle 8.13/JDK17, `:app:testDebugUnitTest :app:assembleDebug -PmalakPrivateBaseUrl=https://malak-api.ayasofia.shop`.
- A generated fictional sale image was actually read on Mac. A synthetic camera-shaped ID returned the actual surname/given name from v5; a unique-digit direct preview returned the full CIN, while that sample’s DOB remained null. Do not claim real identity accuracy from that fixture.

## Current limitations

Customer "changes requested" editing supports listing fields and a fresh OCR request that replaces evidence on that same submission. Administration currently retains an all-submissions queue without pagination. The pilot SQLite adapter needs a migration path when the production database/OTP provider is adopted. No automatic official registry verification is implemented.

## Live HTTPS acceptance checkpoint

Runtime code revision: `2f4c1aa75c61686694e05bbb27b74522ef184a48`. Mac listener and existing isolated Huawei proxy updated with code/config backups; other services untouched. The public HTTPS journey passed 17 requests: same-login preview/compare, encrypted original retrieval by admin, durable phone account across devices, other-phone isolation, property-photo upload, submission, admin publish, redacted public listing/photo, then pause. The synthetic test listing was removed from public visibility by pausing it.

- `/internal/v1/pilot/preview-cin` → 200, request `d0dd266d1f5a7e605da1afdb`.
- `/internal/v1/pilot/compare-pair` → 200, request `75f92c9c7e23a2fc189071a9`.

Automated results: Python workflow/HTTP/bridge tests 17/17; existing TS tests 387/387; Android JVM tests 57/57 and connected debug APK build passed (about 24 MB). Installed-emulator acceptance PASSED: login → selected sale image → CameraX capture → real v5 surname/given name/DOB → real Vision document facts → customer submission → admin browser publication → published card rendered in the installed APK. Camera-captured CIN remained unreadable and was not fabricated. A separate direct synthetic preview returned the complete eight-digit CIN. After screenshots, the test listing was PAUSED and removed from public visibility.

## Installed Android acceptance

`LivePilotJourneyTest` runs only with `livePilot=true`; it uses the genuine configured HTTPS service and a synthetic image-file emulator camera, with no mocked OCR or injected success. The journey test and the separate public-feed test both passed (`OK (1 test)` each). Run the manually built test APK with `adb shell am instrument -w -e livePilot true tn.malak.app.test/androidx.test.runner.AndroidJUnitRunner`; for the already-published feed assertion add `-e stage feed`. Test originals are generated locally and are never committed.

The native CameraX quality guidance remained intact. Early diagnostic failures were emulator image path/placement and test-driver accessibility/keyboard issues; they were corrected in test setup without faking inference or changing model thresholds.

A regression also ensures that a second photographed continuation page retains the first page’s document-family context and correct page provenance.
