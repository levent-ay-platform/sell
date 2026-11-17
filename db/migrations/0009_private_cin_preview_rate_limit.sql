-- Draft-only additive route policy; no production migration is executed from this PR.
-- Private Huawei CIN preview is rate-limited and case/consent checked.
BEGIN;
ALTER TABLE sell.private_evidence_rate_limits
 DROP CONSTRAINT private_evidence_rate_limits_route_check;
ALTER TABLE sell.private_evidence_rate_limits
 ADD CONSTRAINT private_evidence_rate_limits_route_check
 CHECK (route IN ('UPLOAD','INSPECT','OCR','CIN_PREVIEW','PAIR',
   'OWNER_CASE','CONSENT_REVOKE','PILOT_LOGIN','UNKNOWN'));
COMMIT;
