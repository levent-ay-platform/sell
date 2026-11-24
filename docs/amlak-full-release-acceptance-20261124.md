# Amlak release acceptance gate

Baseline: Android 81a6369; deployed OCR 891a787 (operator supplied both local and public health with Vision ready). Existing connected build succeeded; instrumentation previously ran just three tests. This gate expands offline coverage to every existing Android instrumentation class except LivePilotJourneyTest, which requires controlled real-service/local-document access.

Passing this gate is necessary, not sufficient. Remaining acceptance:
- Mac private four-CIN test: compare each field and latency, report counts only; test actual authenticated preview with both engines, not just health.
- Owner flow: consent, document capture/upload, editable partial/conflicting reads, certificate matching, property facts, plan extraction/map alignment, save/reopen and submit review. PR 25 explicitly says optional plan/extra remain session-local; durable private attachment API and reopening are not accepted yet.
- Buyer flow: live map/radar/industrial and CRM Nozha current status, verified public document redaction, correct inquiry eligibility.
- Physical Android: camera capture, rotation/background, permissions, low network, sounds/haptics, Arabic/French text, navigation.
- Never promote OCR into legal verification or publish owner documents without existing review and redaction gates.
- No production deployment, real identities, public uploads or automatic publication in this offline gate.
