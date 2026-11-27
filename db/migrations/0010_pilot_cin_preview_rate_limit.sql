-- Add an explicit rate-limit route for transient pilot OCR preview.
-- Separate from verified-owner CIN preview, and scoped to test-only deployment.
-- Apply only to the authorized Malak private QA database, not CRM.
BEGIN;
ALTER TABLE sell.private_evidence_rate_limits
 DROP CONSTRAINT private_evidence_rate_limits_route_check;
ALTER TABLE sell.private_evidence_rate_limits
 ADD CONSTRAINT private_evidence_rate_limits_route_check
 CHECK (route IN ('UPLOAD','INSPECT','OCR','CIN_PREVIEW','PAIR',
   'OWNER_CASE','CONSENT_REVOKE','PILOT_LOGIN','PILOT_CIN_PREVIEW','UNKNOWN'));
COMMIT;
