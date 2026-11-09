-- SELL Foundation v0.1: synthetic/pilot schema. Not a production rollout.
-- Ownership and legal facts are versioned; public visibility is derived, never a writable verified flag.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA IF NOT EXISTS sell;

CREATE TABLE sell.parties (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 kind text NOT NULL CHECK (kind IN ('PERSON','COMPANY')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sell.users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 phone_e164 text UNIQUE,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\\+[1-9][0-9]{7,14}$')
);
CREATE TABLE sell.party_memberships (
 party_id uuid NOT NULL REFERENCES sell.parties(id),
 user_id uuid NOT NULL REFERENCES sell.users(id),
 capacity text NOT NULL CHECK (capacity IN ('OWNER','REPRESENTATIVE','REVIEWER')),
 PRIMARY KEY(party_id,user_id,capacity)
);
CREATE TABLE sell.properties (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 kind text NOT NULL CHECK (kind IN ('LAND','APARTMENT','VILLA','HOUSE','COMMERCIAL','OTHER')),
 country_code char(2) NOT NULL DEFAULT 'TN',
 public_point geometry(Point,4326),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX properties_public_point_gix ON sell.properties USING gist(public_point);
CREATE TABLE sell.parcels (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 property_id uuid NOT NULL REFERENCES sell.properties(id),
 registry_reference text,
 cadastral_reference text,
 subdivision_plot_number text,
 submitted_boundary geometry(MultiPolygon,4326),
 boundary_review_status text NOT NULL DEFAULT 'UNREVIEWED'
 CHECK (boundary_review_status IN ('UNREVIEWED','REVIEWED')),
 CHECK (submitted_boundary IS NULL OR
   (ST_IsValid(submitted_boundary) AND NOT ST_IsEmpty(submitted_boundary)))
);
CREATE INDEX parcels_submitted_boundary_gix ON sell.parcels USING gist(submitted_boundary);
CREATE TABLE sell.units (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 property_id uuid NOT NULL REFERENCES sell.properties(id),
 unit_reference text NOT NULL
);
CREATE TABLE sell.property_revisions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 property_id uuid NOT NULL REFERENCES sell.properties(id),
 revision_number integer NOT NULL CHECK (revision_number > 0),
 snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
 snapshot_sha256 text GENERATED ALWAYS AS
   (encode(digest(snapshot::text,'sha256'),'hex')) STORED,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(property_id,revision_number),
 UNIQUE(id,property_id)
);
CREATE TABLE sell.ownership_claims (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 property_id uuid NOT NULL REFERENCES sell.properties(id),
 claimant_party_id uuid NOT NULL REFERENCES sell.parties(id),
 declared_capacity text NOT NULL CHECK (declared_capacity IN ('SOLE_OWNER','CO_OWNER','HEIR','MANDATE','COMPANY_SIGNATORY')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sell.verification_cases (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 property_revision_id uuid NOT NULL REFERENCES sell.property_revisions(id),
 ownership_claim_id uuid NOT NULL REFERENCES sell.ownership_claims(id),
 assigned_reviewer_user_id uuid REFERENCES sell.users(id),
 status text NOT NULL DEFAULT 'PENDING'
 CHECK (status IN ('PENDING','NEEDS_INFO','VERIFIED','REJECTED','SUSPENDED','EXPIRED')),
 expires_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX verification_cases_revision_idx ON sell.verification_cases(property_revision_id);
CREATE TABLE sell.evidence_files (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 storage_key text NOT NULL UNIQUE, -- private bucket ONLY; never render to public API
 sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
 media_kind text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sell.case_evidence (
 case_id uuid NOT NULL REFERENCES sell.verification_cases(id),
 evidence_id uuid NOT NULL REFERENCES sell.evidence_files(id),
 PRIMARY KEY(case_id,evidence_id)
);
CREATE TABLE sell.verification_decisions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 case_id uuid NOT NULL UNIQUE REFERENCES sell.verification_cases(id),
 reviewer_user_id uuid NOT NULL REFERENCES sell.users(id),
 result text NOT NULL CHECK (result IN ('APPROVED','REJECTED','NEEDS_INFO')),
 snapshot_sha256 text NOT NULL CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 reason_code text NOT NULL,
 decided_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sell.listings (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 seller_party_id uuid NOT NULL REFERENCES sell.parties(id),
 title text NOT NULL,
 price_tnd numeric(18,3) NOT NULL CHECK (price_tnd > 0),
 status text NOT NULL DEFAULT 'DRAFT'
 CHECK (status IN ('DRAFT','PUBLISHED','PAUSED','SOLD','WITHDRAWN')),
 created_at timestamptz NOT NULL DEFAULT now(),
 published_at timestamptz
);
CREATE TABLE sell.listing_items (
 listing_id uuid NOT NULL REFERENCES sell.listings(id) ON DELETE RESTRICT,
 property_id uuid NOT NULL REFERENCES sell.properties(id),
 property_revision_id uuid NOT NULL,
 verification_case_id uuid NOT NULL REFERENCES sell.verification_cases(id),
 PRIMARY KEY(listing_id,property_id),
 FOREIGN KEY(property_revision_id,property_id) REFERENCES sell.property_revisions(id,property_id)
);
CREATE INDEX listing_items_property_idx ON sell.listing_items(property_id);
CREATE TABLE sell.audit_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 actor_user_id uuid REFERENCES sell.users(id),
 entity_kind text NOT NULL,
 entity_id uuid NOT NULL,
 action text NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT now(),
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);

-- Single final decision per case: re-review requires a NEW case.
-- A positive decision, matching snapshot/party and current revision are all mandatory.
CREATE FUNCTION sell.listing_items_are_verified(p_listing_id uuid, p_seller_party_id uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT
   EXISTS (SELECT 1 FROM sell.listing_items x WHERE x.listing_id = p_listing_id)
   AND NOT EXISTS (
     SELECT 1 FROM sell.listing_items li
     JOIN sell.property_revisions pr ON pr.id=li.property_revision_id AND pr.property_id=li.property_id
     LEFT JOIN sell.verification_cases vc ON vc.id=li.verification_case_id
     LEFT JOIN sell.ownership_claims oc ON oc.id=vc.ownership_claim_id
     LEFT JOIN sell.verification_decisions vd ON vd.case_id=vc.id
     WHERE li.listing_id=p_listing_id AND (
       vc.id IS NULL OR oc.id IS NULL OR vd.id IS NULL
       OR vc.property_revision_id IS DISTINCT FROM li.property_revision_id
       OR oc.property_id IS DISTINCT FROM li.property_id
       OR oc.claimant_party_id IS DISTINCT FROM p_seller_party_id
       OR vc.status IS DISTINCT FROM 'VERIFIED'
       OR vc.expires_at IS NULL OR vc.expires_at <= now()
       OR vd.result IS DISTINCT FROM 'APPROVED'
       OR vd.reviewer_user_id IS DISTINCT FROM vc.assigned_reviewer_user_id
       OR vd.snapshot_sha256 IS DISTINCT FROM pr.snapshot_sha256
       OR EXISTS (
         SELECT 1 FROM sell.property_revisions newer
         WHERE newer.property_id=pr.property_id AND newer.revision_number > pr.revision_number
       )
     )
   )
$$;

-- Block publications whose ENTIRE set of items has not passed review.
CREATE FUNCTION sell.guard_listing_publication()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='PUBLISHED' THEN
   IF TG_OP='INSERT' OR NOT sell.listing_items_are_verified(NEW.id,NEW.seller_party_id) THEN
     RAISE EXCEPTION 'VERIFICATION_REQUIRED: listing cannot be published';
   END IF;
   IF NEW.published_at IS NULL THEN NEW.published_at:=now(); END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER enforce_listing_publication
BEFORE INSERT OR UPDATE ON sell.listings
FOR EACH ROW EXECUTE FUNCTION sell.guard_listing_publication();

CREATE FUNCTION sell.pause_listing_after_scope_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE sell.listings SET status='PAUSED',published_at=NULL
 WHERE id=COALESCE(NEW.listing_id,OLD.listing_id) AND status='PUBLISHED';
 -- An UPDATE moving a listing item across parents must revoke BOTH.
 IF TG_OP='UPDATE' AND NEW.listing_id IS DISTINCT FROM OLD.listing_id THEN
   UPDATE sell.listings SET status='PAUSED',published_at=NULL
   WHERE id=OLD.listing_id AND status='PUBLISHED';
 END IF;
 RETURN NULL;
END $$;
CREATE TRIGGER revoke_on_listing_item_change
AFTER INSERT OR UPDATE OR DELETE ON sell.listing_items
FOR EACH ROW EXECUTE FUNCTION sell.pause_listing_after_scope_change();

-- Records are evidence and cannot be silently edited or deleted.
CREATE FUNCTION sell.reject_immutable_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_RECORD: append a new revision or case';
END $$;
CREATE TRIGGER property_revisions_immutable BEFORE UPDATE OR DELETE
ON sell.property_revisions FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();
CREATE TRIGGER ownership_claims_immutable BEFORE UPDATE OR DELETE
ON sell.ownership_claims FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();
CREATE TRIGGER decisions_immutable BEFORE UPDATE OR DELETE
ON sell.verification_decisions FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE
ON sell.audit_events FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

-- No approval from a case lacking its one matching reviewer-signed decision.
CREATE FUNCTION sell.guard_verified_case()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='VERIFIED' AND (
   NEW.assigned_reviewer_user_id IS NULL OR NEW.expires_at IS NULL
   OR NEW.expires_at <= now() OR NOT EXISTS (
     SELECT 1 FROM sell.verification_decisions d
     JOIN sell.property_revisions r ON r.id=NEW.property_revision_id
     JOIN sell.ownership_claims c ON c.id=NEW.ownership_claim_id
     WHERE d.case_id=NEW.id AND d.result='APPROVED'
       AND d.reviewer_user_id=NEW.assigned_reviewer_user_id
       AND d.snapshot_sha256=r.snapshot_sha256 AND c.property_id=r.property_id
   )
 ) THEN
   RAISE EXCEPTION 'APPROVED_DECISION_REQUIRED: verified case must have matching approved decision';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER enforce_verified_case BEFORE INSERT OR UPDATE
ON sell.verification_cases FOR EACH ROW EXECUTE FUNCTION sell.guard_verified_case();

-- This is the public read projection; API must never expose raw table reads.
CREATE VIEW sell.public_listing_cards WITH (security_barrier=true) AS
 SELECT l.id, l.title, l.price_tnd, l.status, l.published_at,
        l.created_at,
        count(li.property_id)::integer AS item_count
 FROM sell.listings l
 JOIN sell.listing_items li ON li.listing_id=l.id
 WHERE l.status='PUBLISHED'
   AND sell.listing_items_are_verified(l.id,l.seller_party_id)
 GROUP BY l.id,l.title,l.price_tnd,l.status,l.published_at,l.created_at;

-- Default to no anonymous database role grants; API exposes only vetted projections.
REVOKE ALL ON SCHEMA sell FROM PUBLIC;
COMMIT;
