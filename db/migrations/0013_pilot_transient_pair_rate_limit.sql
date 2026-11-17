-- Explicit per-route distributed limit for the separate transient OCR preview.
-- Never expands owner/private-case permissions or verified-ownership status.
BEGIN;
ALTER TABLE sell.private_evidence_rate_limits
 DROP CONSTRAINT private_evidence_rate_limits_route_check;
ALTER TABLE sell.private_evidence_rate_limits
 ADD CONSTRAINT private_evidence_rate_limits_route_check
 CHECK (route IN ('UPLOAD','INSPECT','OCR','CIN_PREVIEW','PAIR',
  'OWNER_CASE','CONSENT_REVOKE','PILOT_LOGIN','PILOT_CIN_PREVIEW',
  'PILOT_PAIR','UNKNOWN'));
COMMIT;
