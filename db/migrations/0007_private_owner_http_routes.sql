-- Malak private authenticated case lifecycle HTTP throttle routes.
-- Synthetic-only; never apply to Huawei/CRM/production from a PR.
BEGIN;
ALTER TABLE sell.private_evidence_rate_limits
  DROP CONSTRAINT private_evidence_rate_limits_route_check;
ALTER TABLE sell.private_evidence_rate_limits
  ADD CONSTRAINT private_evidence_rate_limits_route_check
  CHECK (route IN ('UPLOAD','INSPECT','OCR','PAIR','OWNER_CASE','CONSENT_REVOKE','UNKNOWN'));
COMMIT;
