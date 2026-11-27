-- SELL/Malak F1 private evidence transport throttle; synthetic CI first.
-- NO production migration via PR or application startup. Never store raw IP or token.
BEGIN;
CREATE TABLE sell.private_evidence_rate_limits (
 bucket_hash text NOT NULL CHECK (bucket_hash ~ '^[0-9a-f]{64}$'),
 route text NOT NULL CHECK (route IN ('UPLOAD','INSPECT','UNKNOWN')),
 window_started timestamptz NOT NULL,
 hits integer NOT NULL CHECK (hits BETWEEN 1 AND 60),
 PRIMARY KEY (bucket_hash,route,window_started)
);
CREATE INDEX private_evidence_rate_expiry_idx ON sell.private_evidence_rate_limits(window_started);
-- Runtime role must only SELECT/INSERT/UPDATE counter rows through a guarded
-- service; provide a separate maintenance role for old bucket pruning.
COMMIT;
