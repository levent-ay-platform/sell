# Malak pilot to publication

Approved spec: this chat, 2026-11-14. Phone number uniquely identifies an account across installations; 1234 is a pilot code only.

1. Align v5 six-field schema; mount real Mac land OCR and existing TS matcher behind same pilot session. Tests: actual six-key engine, frame/auth/consent, extraction and matching.
2. Persistent phone accounts, private submission/evidence storage, separate admin email/password, reviewed publication and public feed. Tests: phone stability/isolation, restart durability, admin-only decisions and redaction, idempotency.
3. English browser admin: queue, private evidence, editable listing, publish/request changes/reject/pause. Android: sourced autofill, descriptive evidence tier, submit/status/public feed. OCR processing errors require retry; completed ambiguous results may be submitted.
4. Deploy only isolated Malak service/ingress, build connected APK, run real inference and phone/browser acceptance. Never substitute synthetic results for real OCR or claim device proof without a device.

Architecture: retain Python Mac v5 listener for preview and Mac document reading; same listener dispatches comparison to existing TypeScript domain code. Durable pilot workflow uses private SQLite and encrypted evidence on Mac, isolated from old verification approval tables. Public listings represent admin publication, never legal verification. Existing Postgres legal verification remains independent.

Defaults: contact details remain private to admin in initial public cards; customer can review own submission. Admin may override OCR conflicts with recorded review note. Originals never public. TLS through existing Huawei QA gateway. No production CRM changes.
