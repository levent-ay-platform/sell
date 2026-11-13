# Malak OCR QA — public HTTPS API, no phone VPN

Android connects directly to **https://malak-api.ayasofia.shop** using ordinary
HTTPS. No Tailscale installation, VPN, login prompt for a network, or custom
server-address entry is required on the phone.

Current QA ingress (2026-11-13):
1. Cloudflare HTTPS and wildcard DNS for `malak-api.ayasofia.shop`
2. Huawei existing Traefik router (new, isolated `malak-qa-public-api` Docker
   container on `ayasofia-net`, no modification to CRM)
3. Small nginx TLS-validating reverse proxy, limits request rate and image
   body size, exposes **only** `/healthz`, `/internal/v1/pilot/login`,
   `/internal/v1/pilot/preview-cin`
4. **Server-to-server only** private transport to the Mac's existing
   `com.levent.malak.qaocr` launchd process at `127.0.0.1:18443`
5. Existing pinned Arabic-v5 ONNX model, in memory on the Mac, no OCR model
   or personal documents in the APK/repository.

Huawei public proxy configuration location:
`C:\\ProgramData\\MalakQaPublicApi\\default.conf`. Do not rewrite
Traefik or Cloudflare tunnel configurations or modify any CRM service.
Mac launchd: `/Library/LaunchDaemons/com.levent.malak.qaocr.plist`.
The private Mac tailnet link is an internal server transport ONLY — the
Android app must not depend on it.

QA-only authentication: +216 test phone and code `1234` obtain a real
12h, memory-only bearer from the running service. Explicit camera OCR
consent is required before uploading at most 6MiB. The service rate-limits
requests and is isolated from owner/publishing endpoints. The fixed code
is not identity proof and must never be used as production authentication.

The API must return `identityVerified:false`, `ownershipVerified:false`,
and `mayPublish:false`; no legal verification or real listings are
activated. Images and extracted text are not logged or written to disk.

Verified: public HTTPS `/healthz` returned HTTP 200 from Huawei via
Cloudflare with `engine=arabic-v5, modelReady=true`. The actual Android
phone login + image OCR round trip and exact CIN/DOB benchmark are **not
independently measured** yet. Do not claim full acceptance from HTTP 200
or the Android emulator login smoke.

The Mac's older direct tailnet port `10000` is now only a **private backend**
connection, not an Android URL. Previous tailnet-only APK artifact is
obsolete and should not be installed for user QA.
