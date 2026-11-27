-- Malak MVP: distinguish owner OCR requests from ordinary private uploads.
-- In a new, disposable, isolated database first. NO production migration from PR.
BEGIN;
ALTER TABLE sell.private_evidence_rate_limits
  DROP CONSTRAINT private_evidence_rate_limits_route_check;
ALTER TABLE sell.private_evidence_rate_limits
  ADD CONSTRAINT private_evidence_rate_limits_route_check
  CHECK (route IN ('UPLOAD','INSPECT','OCR','UNKNOWN'));
COMMIT;
