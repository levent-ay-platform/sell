-- Malak doc-first private intake: no guessed legal capacity or property facts.
-- Controlled migration BEFORE enabling EVIDENCE_ONLY owner-case mode.
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
ALTER TABLE sell.ownership_claims
  DROP CONSTRAINT ownership_claims_declared_capacity_check,
  ADD CONSTRAINT ownership_claims_declared_capacity_check
  CHECK (declared_capacity IN (
    'SOLE_OWNER','CO_OWNER','HEIR','MANDATE','COMPANY_SIGNATORY','UNDECLARED'));

CREATE OR REPLACE FUNCTION sell.prevent_unconfirmed_intake_verification()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,sell AS $$
BEGIN
  IF NEW.status='VERIFIED' AND EXISTS(
    SELECT 1 FROM sell.ownership_claims c
    WHERE c.id=NEW.ownership_claim_id AND c.declared_capacity='UNDECLARED'
  ) THEN
    RAISE EXCEPTION 'EVIDENCE_ONLY_CASE_CANNOT_VERIFY';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS prevent_unconfirmed_intake_verification
  ON sell.verification_cases;
CREATE TRIGGER prevent_unconfirmed_intake_verification
 BEFORE INSERT OR UPDATE ON sell.verification_cases
 FOR EACH ROW EXECUTE FUNCTION sell.prevent_unconfirmed_intake_verification();
