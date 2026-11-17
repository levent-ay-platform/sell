-- Malak F1 pilot foundation: append-only consents and private, encrypted evidence receipts.
-- Do not run on production. Requires 0001_foundation.sql and trusted server adapters.
BEGIN;

CREATE TABLE sell.evidence_consent_events (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  case_id uuid NOT NULL REFERENCES sell.verification_cases(id),
  person_user_id uuid NOT NULL REFERENCES sell.users(id),
  purpose text NOT NULL DEFAULT 'PROPERTY_OWNERSHIP_EVIDENCE'
    CHECK (purpose='PROPERTY_OWNERSHIP_EVIDENCE'),
  decision text NOT NULL CHECK (decision IN ('GRANTED','REVOKED')),
  policy_version text NOT NULL CHECK (length(policy_version) BETWEEN 1 AND 100),
  recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX evidence_consent_lookup_idx
  ON sell.evidence_consent_events(case_id,person_user_id,purpose,id DESC);
CREATE TRIGGER evidence_consent_immutable BEFORE UPDATE OR DELETE
  ON sell.evidence_consent_events FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

-- Must be used from an authenticated, trusted API. Never grant raw INSERT to sellers.
-- Lock case row so consent revocation and upload receipt appends are serializable per case.
CREATE FUNCTION sell.guard_evidence_consent_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM sell.verification_cases WHERE id=NEW.case_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'CASE_NOT_FOUND'; END IF;
  IF NOT EXISTS (
     SELECT 1 FROM sell.verification_cases c
     JOIN sell.ownership_claims claim ON claim.id=c.ownership_claim_id
     JOIN sell.parties p ON p.id=claim.claimant_party_id AND p.kind='PERSON'
     JOIN sell.party_memberships pm ON pm.party_id=p.id AND pm.user_id=NEW.person_user_id AND pm.capacity='OWNER'
     WHERE c.id=NEW.case_id AND (
       NEW.decision='REVOKED' OR (c.status IN ('PENDING','NEEDS_INFO')
       AND NOT EXISTS(SELECT 1 FROM sell.verification_decisions v WHERE v.case_id=c.id))
     )
  ) THEN RAISE EXCEPTION 'INDIVIDUAL_CASE_CONSENT_REQUIRED'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER enforce_evidence_consent_actor BEFORE INSERT ON sell.evidence_consent_events
  FOR EACH ROW EXECUTE FUNCTION sell.guard_evidence_consent_event();

CREATE FUNCTION sell.case_accepts_individual_evidence(p_case uuid,p_actor uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT EXISTS (
    SELECT 1 FROM sell.verification_cases c
    JOIN sell.ownership_claims oc ON oc.id=c.ownership_claim_id
    JOIN sell.parties p ON p.id=oc.claimant_party_id AND p.kind='PERSON'
    JOIN sell.party_memberships pm ON pm.party_id=p.id AND pm.user_id=p_actor AND pm.capacity='OWNER'
    WHERE c.id=p_case AND c.status IN ('PENDING','NEEDS_INFO')
      AND NOT EXISTS(SELECT 1 FROM sell.verification_decisions v WHERE v.case_id=c.id)
      AND (
        SELECT e.decision FROM sell.evidence_consent_events e
        WHERE e.case_id=c.id AND e.person_user_id=p_actor
          AND e.purpose='PROPERTY_OWNERSHIP_EVIDENCE'
        ORDER BY e.id DESC LIMIT 1
      )='GRANTED'
  )
$$;

CREATE TABLE sell.evidence_upload_receipts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  case_id uuid NOT NULL REFERENCES sell.verification_cases(id),
  uploader_user_id uuid NOT NULL REFERENCES sell.users(id),
  evidence_id uuid NOT NULL UNIQUE REFERENCES sell.evidence_files(id),
  idempotency_key text NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{16,100}$'),
  sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  -- KMS ciphertext wrapping a fresh per-file DEK, never a raw key.
  wrapped_data_key text NOT NULL CHECK (length(wrapped_data_key) BETWEEN 16 AND 4096),
  key_version text NOT NULL CHECK (length(key_version) BETWEEN 1 AND 100),
  media_kind text NOT NULL CHECK (media_kind IN ('image/png','image/jpeg','image/tiff','application/pdf')),
  file_size integer NOT NULL CHECK (file_size BETWEEN 20 AND 12582912),
  evidence_kind text NOT NULL CHECK (evidence_kind IN
     ('IDENTITY','OWNERSHIP_CERTIFICATE','TITLE_EXTRACT','OTHER_LEGAL_PROOF')),
  page_number smallint CHECK (page_number BETWEEN 1 AND 8),
  uploaded_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(case_id,uploader_user_id,idempotency_key)
);
CREATE INDEX evidence_receipts_case_idx ON sell.evidence_upload_receipts(case_id);
CREATE TRIGGER evidence_receipt_immutable BEFORE UPDATE OR DELETE
  ON sell.evidence_upload_receipts FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE FUNCTION sell.guard_individual_evidence_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM sell.verification_cases WHERE id=NEW.case_id FOR UPDATE;
  IF NOT FOUND OR NOT sell.case_accepts_individual_evidence(NEW.case_id,NEW.uploader_user_id)
    THEN RAISE EXCEPTION 'PRIVATE_EVIDENCE_SCOPE_OR_CONSENT_REQUIRED'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM sell.evidence_files f
    JOIN sell.case_evidence ce ON ce.evidence_id=f.id AND ce.case_id=NEW.case_id
    WHERE f.id=NEW.evidence_id AND f.sha256=NEW.sha256 AND f.media_kind=NEW.media_kind
  ) THEN RAISE EXCEPTION 'EVIDENCE_BINDING_MISMATCH'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER enforce_individual_evidence_receipt BEFORE INSERT ON sell.evidence_upload_receipts
  FOR EACH ROW EXECUTE FUNCTION sell.guard_individual_evidence_receipt();

-- Keep provenance records private even if API role gains read access elsewhere.
REVOKE ALL ON sell.evidence_consent_events FROM PUBLIC;
REVOKE ALL ON sell.evidence_upload_receipts FROM PUBLIC;
REVOKE ALL ON FUNCTION sell.case_accepts_individual_evidence(uuid,uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION sell.guard_evidence_consent_event() FROM PUBLIC;
REVOKE ALL ON FUNCTION sell.guard_individual_evidence_receipt() FROM PUBLIC;
COMMIT;
