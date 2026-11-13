# macOS private OCR QA server (tailnet-only, not production)

Provides real server-issued memory-only 12-hour test bearer to the debug
+216 phone and 1234 login. Handles POST /internal/v1/pilot/preview-cin via the
same pinned Arabic PP-OCRv5 ONNX model already tested on the Mac. This is a
LOCAL QA substitute for the not-yet-deployed full PostgreSQL/Huawei gateway;
it does not create property/identity verification or publishing privileges.

**Network boundary:** HTTP binds **127.0.0.1:18443** only. Terminate HTTPS
with Tailscale Serve on the Mac; do NOT use Funnel, port forwarding, or a
public hostname for the shared 1234 QA PIN. The Android phone must be a
connected member of the SAME tailnet. The public URL in a QA build is the
Mac's tailnet HTTPS DNS name. No real CIN/PII in public repository or CI.

Requirements: dedicated Python virtualenv with cv2, numpy, PyYAML and
ONNX Runtime; checked-in OCR source, and the pinned public model weights
stored locally on the private Mac. Run as an unprivileged user with macOS
launchd. Avoid a second model process. Always validate health, login,
synthetic PNG OCR and user consent before enabling a phone.

The worker never stores original images or OCR text on disk. The bearer
table remains in process memory and is invalidated at restart. The server
accepts at most 6 MiB PNG/JPEG, checks image bounds, and responds only
with preliminary masked CIN last four and names/DOB plus
identityVerified=false, ownershipVerified=false, mayPublish=false.
A shared test PIN is intentionally not a verified phone claim.

**Acceptance is NOT inferred from synthetic tests:** compare exact
private eligible CIN 1/2/5 and DOB on 1-5 after explicit QA run.
No merges or production deployment from this script.
