-- Malak F1: idempotent private owner case intent ledger (synthetic/isolated only).
-- Create cases ONLY through authenticated, transaction-bound server code.
-- Never apply this migration to CRM, Huawei, or production from a PR.
BEGIN;
CREATE TABLE sell.private_owner_case_intents (
 actor_user_id uuid NOT NULL REFERENCES sell.users(id),
 idempotency_key text NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{16,100}$'),
 request_sha256 text NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
 case_id uuid NOT NULL UNIQUE REFERENCES sell.verification_cases(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(actor_user_id,idempotency_key)
);
CREATE INDEX private_owner_case_created_idx ON sell.private_owner_case_intents(created_at);
CREATE TRIGGER private_owner_case_intent_immutable BEFORE UPDATE OR DELETE
 ON sell.private_owner_case_intents FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();
REVOKE ALL ON sell.private_owner_case_intents FROM PUBLIC;
COMMIT;
