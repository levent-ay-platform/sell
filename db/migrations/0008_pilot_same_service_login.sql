-- Malak pilot phone + fixed code on the SAME verification service.
-- No separate environment. Enable only through explicit server pilot setting.
-- A shared pilot code never upgrades phone_e164 into a verified identity record.
-- Device-scoped pilot accounts prevent entering somebody else's phone number
-- from granting access to their existing private documents or real user account.
BEGIN;

CREATE TABLE sell.pilot_accounts (
 user_id uuid PRIMARY KEY REFERENCES sell.users(id),
 device_phone_digest char(64) NOT NULL UNIQUE
    CHECK (device_phone_digest ~ '^[0-9a-f]{64}$'),
 phone_e164 text NOT NULL CHECK (phone_e164 ~ '^\\+216[2-9][0-9]{7}$'),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER pilot_accounts_immutable BEFORE UPDATE OR DELETE
 ON sell.pilot_accounts FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE TABLE sell.pilot_sessions (
 token_digest char(64) PRIMARY KEY CHECK (token_digest ~ '^[0-9a-f]{64}$'),
 user_id uuid NOT NULL REFERENCES sell.pilot_accounts(user_id),
 expires_at timestamptz NOT NULL,
 issued_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pilot_sessions_expiry_idx ON sell.pilot_sessions(expires_at);
CREATE TRIGGER pilot_sessions_immutable BEFORE UPDATE OR DELETE
 ON sell.pilot_sessions FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

ALTER TABLE sell.private_evidence_rate_limits
 DROP CONSTRAINT private_evidence_rate_limits_route_check;
ALTER TABLE sell.private_evidence_rate_limits
 ADD CONSTRAINT private_evidence_rate_limits_route_check
 CHECK (route IN ('UPLOAD','INSPECT','OCR','PAIR','OWNER_CASE','CONSENT_REVOKE','PILOT_LOGIN','UNKNOWN'));

REVOKE ALL ON sell.pilot_accounts,sell.pilot_sessions FROM PUBLIC;
COMMIT;
