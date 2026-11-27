# Malak 2026 — Huawei OCR v5, APK under 60MB

Android includes CameraX capture, guide frame, instant review and explicit retake, but NO Tesseract, OpenCV, ONNX Runtime, or OCR model assets. The QA APK must remain below 60,000,000 bytes.

Private Huawei holds the public SHA256-pinned PP-OCRv5 Arabic model and the OpenCV recognition pipeline. The Python worker is services/verification/ocr/cin_v5.py; it must run in a dedicated CPU/RAM-limited environment, with no external data egress and private 0700 tmpfs, not in the CRM environment. The authenticated gateway mounts the optional private-cin-v5-worker and checks owner case permissions and active consent before and after each OCR job. No legal verification claim is made.

Private API: POST /internal/v1/evidence/{caseId}/preview-cin uses HTTPS and verified owner sessions only, with 6MB bounded JPEG/PNG uploads, rate limits, deny audit, and no permanent preview image storage. An unavailable OCR service fails closed in the same identity step. Unverified demo OTP may not submit ID photos.

Manual deployment checklist:
1. Build Huawei private worker from services/verification/docker/HuaweiCinV5.Dockerfile using pinned public weights; run with CPU 1, RAM <=768MiB, private 128MiB tmpfs, no public ports, no privileged mode. This reference image is NOT deployed by CI.
2. Configure real production gateway with verified +216 authentication and inject CinV5Worker; it is disabled by default. Keep separate from CRM and its data.
3. Review migration db/migrations/0009_private_cin_preview_rate_limit.sql and apply only with explicit production authorization, backup and rollback.
4. Privately test the five consented Tunisian identity samples on the actual Huawei worker; do NOT upload photos, names, CIN, OCR outputs or crop files to GitHub or CI.
5. Install QA APK, test frame alignment, camera permission, same-step retake, auth/consent, Huawei downtime and exact field accuracy; measure CPU/RAM/latency.
6. Merge or publish only after independent acceptance. A successful APK build is not proof of working Huawei OCR.

Local five-card benchmark: v5 strict full-name match 4/5; normalized comparison (ى/ي only) 5/5; front-side CIN visible in three cases 3/3; DOB remains unreliable. These results were local computer measurements, not Huawei or phone acceptance.
