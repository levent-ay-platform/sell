# Amlak CPF live release gate — bounded Mac Pilot update

This is an operator handoff and acceptance contract, not evidence of deployment.

## Observed live baseline (operator health and launchctl output)
- Local and public health HTTP 200 at /healthz; source revision 891a7876e8ab3bfd9a2f6d012dfd257fcf94d0f7.
- Apple Vision ready, Arabic v5 ready, vision-consensus-v1.
- Existing system launchd service com.levent.malak.qaocr, listener 127.0.0.1:18443.
- Runtime path ~/Projects/malak-v5-qa-mac/runtime-qa-f0078d7b/services/verification; the name is not the live revision.
- Separate runtime-open-pilot-71ad083 is a test-only runtime and must not replace enrolled/real-data service or storage.
- Existing reader.ts differs from the candidate. Do not overwrite the live TypeScript source tree or reuse V1–V3 ad hoc activation scripts.
- Prior live health checks alone do not prove the CPF parser was activated.

## Release components and boundaries
1. Tested immutable isolated matcher artifact, all TypeScript files and manifest SHA256 verified. Use the artifact from the exact accepted commit, not a moving branch or per-file downloads. Run the synthetic compound-field pair and document-family tests again using the configured Mac Node binary.
2. Keep the original compare entrypoint backed up; point it only at the verified isolated matcher. No change to Apple Vision, Arabic V5, model files, existing reader.ts, or the OCR identity cache.
3. Port only the analysis_revision helper, the document-engine revision in the existing compare route and health header, and startup validation to the actual current Python service. Do not copy workflow_http.py or pilot_workflow.py wholesale from an open-device candidate. Compare to the actual live source first; unexpected local edits require review rather than forced overwrite.
4. Existing runtime configuration retains all authentication, paths, data, publishing, Atlas and CRM settings. Add only the verified MALAK_DOCUMENT_ENGINE_REVISION. Never print runtime.json or secrets. Existing MALAK_SOURCE_SHA remains unchanged; it identifies the service baseline, while the document revision identifies the matcher bundle.
5. The Python changes require an explicit, scoped restart of com.levent.malak.qaocr, after staging and acceptance. Do not restart other services, Docker, Tailscale, Cloudflare, or the Mac. Preserve original file bytes, modes and config in a private backup; rollback must restore files and restart the same service if any activation check fails.
6. Never delete SQLite reviews/cache to force reanalysis. The new cache namespace is keyed by service and document revision; existing reviews remain accessible.

## Required gates before live activation
- Exact candidate CI: TypeScript, isolated source artifact, synthetic private HTTP, Android build and unit tests green.
- Local staging using the actual live runtime code/config, separate temporary store and test-only loopback port. Verify helper/route changes and preserved device enrollment; no credentials or private samples in CI or source control.
- With authorized local samples only, exercise the real Mac OCR/compare adapter for certificate PDF + matching identity. Report field keys, match counts and timings; don't upload or print CIN, names, unredacted documents, or tokens. Check against the visible certificate: area vs rights parts, printed plot number vs parcel count, parent register, category/content/name, creation nature and location. Missing/ambiguous fields stay unresolved, not fabricated.
- Verify Android receives propertyFacts and extendedPropertyFacts; confirm real UI intake, map editor and administrative candidate selection. Historical locality is not automatically a modern imada. Administrative match does not establish official cadastral geometry.
- Official parcel polygon only with actual cadastral evidence / georeferencing and review. No false verified ownership or automatic public publication.

## Activation and rollback verification
- Check both local/public /healthz: HTTP 200, Vision ready, same baseline source and exact expected X-Malak-Document-Revision.
- Authenticated controlled pair response has expected documentEngineRevision and fresh extraction; repeated same analysis key under that revision reuses only that revision's result. Old review remains accessible.
- Check existing authorization policy, public Nozha card and public/private separation without publishing or altering CRM.
- If cutover fails, restore the scoped backup and old service health; retain diagnostics without private payloads. No fallback to insecure open-device mode.
- A green health endpoint alone is not acceptance. Real document, Android and Atlas checks are required before calling the release complete.

## Current status
Code and offline QA are in PR #32. The earlier build passed all 403 TypeScript tests and the isolated matcher artifact; the independent cache revision regression passed. Three HTTP tests failed in CI because OpenCV was missing; the CI dependency correction is in 7ebb6a48. The latest test results, local staging and real-document end-to-end checks remain to be verified. No live changes are asserted.

## Operator-local authorization blocker
The Mac operator verified the embedded artifact SHA256 and manifest, all 28 document-family tests, and the compound synthetic pair. Staging then stopped before any live change at the wrong-device rejection check (expected 401, received 200).
A separate isolated diagnostic configured a temporary enrollment file and ran both before-import and after-import variants. Both reported ENROLLMENT_LOADED=False, OPEN_PILOT=LEGACY, wrong device accepted in PilotStore and HTTP 200; incorrect PIN rejected with HTTP 401. This is not an OCR, Node, network, import timing, or Cache problem. It proves the local legacy store does not enforce that enrollment contract under the tested configuration. It is not a live account-access test.
Local Git blob IDs: pilot_workflow.py 62dabe6878584ee721b5d023e179906814e287db; workflow_http.py 29390a292cff887cb0c849a70e10c9c00ad134e6; mac_pair_runtime.py ba6e7e8a6251c23206a4da0b64f304c6a064cc34; mac_pilot_ocr_http.py f7563e635995d70f40cceb12cd2b58c187c5e7f0. Runtime.json did not contain the four enrollment/open-pilot/publication/private-attachment settings; effective launchd configuration has not been inspected. Do not infer the actual live authentication policy solely from that file.
Do not bypass the failing assertion, expose real identity/title material through fixed-PIN legacy login, copy open-pilot storage to live, or overwrite locally modified workflow code. Inspect local store login/authenticate/schema and effective service settings without printing secrets or customer data; reconcile secure real-document access and isolated synthetic QA separately. No deployment or DB migration has been performed. This is a genuine release blocker, independent of CPF readiness.
