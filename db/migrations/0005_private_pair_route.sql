-- SELL/Malak synthetic-only preparation for the private OCR pair route.
-- Never apply from a pull request to Huawei, CRM or any production database.
BEGIN;
ALTER TABLE sell.private_evidence_rate_limits
  DROP CONSTRAINT private_evidence_rate_limits_route_check;
ALTER TABLE sell.private_evidence_rate_limits
  ADD CONSTRAINT private_evidence_rate_limits_route_check
  CHECK (route IN ('UPLOAD','INSPECT','OCR','PAIR','UNKNOWN'));
COMMIT;
