BEGIN;

-- Common geometry gate. Source SRID and vertices remain on the private version.
CREATE FUNCTION sell.parcel_geometry_wgs84(source geometry) RETURNS geometry
LANGUAGE plpgsql STABLE STRICT AS $$
DECLARE output geometry;
BEGIN
 IF ST_SRID(source)<=0 OR NOT EXISTS (SELECT 1 FROM spatial_ref_sys WHERE srid=ST_SRID(source))
 OR GeometryType(source) NOT IN ('POLYGON','MULTIPOLYGON') OR ST_NDims(source)<>2
 OR ST_IsEmpty(source) OR ST_NPoints(source)>4000 OR NOT ST_IsValid(source) THEN
   RAISE EXCEPTION 'INVALID_PARCEL_BOUNDARY' USING ERRCODE='22023';
 END IF;
 output:=ST_Multi(ST_Transform(source,4326));
 IF ST_IsEmpty(output) OR NOT ST_IsValid(output)
 OR NOT ST_CoveredBy(output,ST_MakeEnvelope(-180,-90,180,90,4326)) THEN
   RAISE EXCEPTION 'INVALID_PARCEL_BOUNDARY' USING ERRCODE='22023';
 END IF;
 RETURN output;
END $$;

-- A versioned, derived Atlas record, never a write into the source archive.
CREATE TABLE sell.atlas_parcel_source_records (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 source_namespace text NOT NULL CHECK (source_namespace~'^[A-Za-z0-9_-]{1,64}$'),
 source_id text NOT NULL CHECK (length(btrim(source_id)) BETWEEN 1 AND 200),
 layer_id text NOT NULL CHECK (length(btrim(layer_id)) BETWEEN 1 AND 200),
 external_id text NOT NULL CHECK (length(btrim(external_id)) BETWEEN 1 AND 200),
 source_version text NOT NULL CHECK (length(btrim(source_version)) BETWEEN 1 AND 200),
 source_sha256 text NOT NULL CHECK (source_sha256~'^[0-9a-f]{64}$'),
 registry_scope text CHECK (length(btrim(registry_scope)) BETWEEN 1 AND 100),
 title_reference text CHECK (length(btrim(title_reference)) BETWEEN 1 AND 100),
 plot_reference text CHECK (length(btrim(plot_reference)) BETWEEN 1 AND 100),
 locality_key text CHECK (length(btrim(locality_key)) BETWEEN 1 AND 100),
 classification text NOT NULL CHECK (classification IN
  ('OFFICIAL_CADASTRAL','VERIFIED_PLANNING','PLAN_NOT_GEOREFERENCED','CANDIDATE')),
 allow_boundary_redistribution boolean NOT NULL DEFAULT false,
 raw_geometry geometry,
 geometry_4326 geometry(MultiPolygon,4326),
 imported_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(source_namespace,source_id,layer_id,external_id,source_version),
 CHECK (classification<>'PLAN_NOT_GEOREFERENCED' OR raw_geometry IS NULL),
 CHECK (classification<>'OFFICIAL_CADASTRAL' OR
  (raw_geometry IS NOT NULL AND registry_scope IS NOT NULL AND title_reference IS NOT NULL
   AND plot_reference IS NOT NULL AND locality_key IS NOT NULL))
);
CREATE INDEX atlas_parcel_lookup_idx ON sell.atlas_parcel_source_records
 (registry_scope,title_reference,plot_reference,locality_key);
CREATE INDEX atlas_parcel_geometry_gix ON sell.atlas_parcel_source_records USING gist(geometry_4326);
CREATE TRIGGER atlas_parcel_record_immutable BEFORE UPDATE OR DELETE ON sell.atlas_parcel_source_records
 FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE FUNCTION sell.derive_atlas_parcel_geometry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.geometry_4326:=sell.parcel_geometry_wgs84(NEW.raw_geometry);
 RETURN NEW;
END $$;
CREATE TRIGGER derive_atlas_parcel_geometry BEFORE INSERT ON sell.atlas_parcel_source_records
 FOR EACH ROW EXECUTE FUNCTION sell.derive_atlas_parcel_geometry();

CREATE FUNCTION sell.register_atlas_parcel_record(
 p_namespace text,p_source text,p_layer text,p_external_id text,p_version text,p_sha256 text,
 p_registry_scope text,p_title text,p_plot text,p_locality text,p_classification text,
 p_allow_redistribution boolean,p_raw_geometry geometry DEFAULT NULL
) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE saved sell.atlas_parcel_source_records%ROWTYPE;
BEGIN
 INSERT INTO sell.atlas_parcel_source_records(
  source_namespace,source_id,layer_id,external_id,source_version,source_sha256,registry_scope,
  title_reference,plot_reference,locality_key,classification,allow_boundary_redistribution,raw_geometry)
 VALUES (p_namespace,p_source,p_layer,p_external_id,p_version,p_sha256,p_registry_scope,
  p_title,p_plot,p_locality,p_classification,p_allow_redistribution,p_raw_geometry)
 ON CONFLICT (source_namespace,source_id,layer_id,external_id,source_version) DO NOTHING
 RETURNING * INTO saved;
 IF FOUND THEN RETURN saved.id; END IF;
 SELECT * INTO STRICT saved FROM sell.atlas_parcel_source_records a
 WHERE (a.source_namespace,a.source_id,a.layer_id,a.external_id,a.source_version)=
   (p_namespace,p_source,p_layer,p_external_id,p_version);
 IF (saved.source_sha256,saved.registry_scope,saved.title_reference,saved.plot_reference,
    saved.locality_key,saved.classification,saved.allow_boundary_redistribution,ST_AsEWKB(saved.raw_geometry))
 IS DISTINCT FROM (p_sha256,p_registry_scope,p_title,p_plot,p_locality,p_classification,
    p_allow_redistribution,ST_AsEWKB(p_raw_geometry)) THEN
   RAISE EXCEPTION 'ATLAS_SOURCE_VERSION_CONFLICT' USING ERRCODE='23505';
 END IF;
 RETURN saved.id;
END $$;

-- All returned matches remain candidates. Location is corroborating evidence, not proof of identity.
CREATE FUNCTION sell.match_atlas_parcel_candidates(
 p_registry_scope text,p_title text,p_plot text,p_locality text,p_anchor geometry DEFAULT NULL
) RETURNS TABLE(atlas_record_id uuid,spatial_status text) LANGUAGE sql STABLE AS $$
 SELECT a.id, CASE
   WHEN p_anchor IS NULL OR a.geometry_4326 IS NULL THEN 'NEEDS_LOCATION_REVIEW'
   WHEN GeometryType(p_anchor)<>'POINT' OR ST_SRID(p_anchor)<>4326 OR ST_IsEmpty(p_anchor)
    THEN 'SPATIAL_MISMATCH'
   WHEN ST_Covers(a.geometry_4326,p_anchor) THEN 'SPATIALLY_COMPATIBLE'
   ELSE 'SPATIAL_MISMATCH'
 END
 FROM sell.atlas_parcel_source_records a
 WHERE (a.registry_scope,a.title_reference,a.plot_reference,a.locality_key)=
       (p_registry_scope,p_title,p_plot,p_locality)
 ORDER BY a.id LIMIT 50
$$;

ALTER TABLE sell.parcels ADD CONSTRAINT parcel_property_scope UNIQUE (id,property_id);
ALTER TABLE sell.verification_cases ADD CONSTRAINT verification_case_revision_scope UNIQUE (id,property_revision_id);
ALTER TABLE sell.evidence_upload_receipts
 DROP CONSTRAINT evidence_upload_receipts_evidence_kind_check,
 ADD CONSTRAINT evidence_upload_receipts_evidence_kind_check CHECK (evidence_kind IN
  ('IDENTITY','OWNERSHIP_CERTIFICATE','TITLE_EXTRACT','OTHER_LEGAL_PROOF',
   'SHARED_OWNERSHIP_CERTIFICATE','SALE_DEED','TRANSFER_CARD','POSSESSION_REPORT',
   'FINANCIAL_RECEIPT','PROPERTY_SERVICE_RECEIPT','CADASTRAL_PLAN'));

CREATE TABLE sell.parcel_geometry_versions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 parcel_id uuid NOT NULL,
 property_id uuid NOT NULL,
 property_revision_id uuid NOT NULL,
 case_id uuid NOT NULL,
 method text NOT NULL CHECK (method IN
  ('ATLAS_OFFICIAL_BOUNDARY','MALAK_DOC_GEOREFERENCED','OWNER_TRACED_ESTIMATE','DOCUMENT_ONLY')),
 source_evidence_id uuid,
 atlas_record_id uuid REFERENCES sell.atlas_parcel_source_records(id),
 registry_scope text CHECK (length(btrim(registry_scope)) BETWEEN 1 AND 100),
 title_reference text CHECK (length(btrim(title_reference)) BETWEEN 1 AND 100),
 plot_reference text CHECK (length(btrim(plot_reference)) BETWEEN 1 AND 100),
 locality_key text CHECK (length(btrim(locality_key)) BETWEEN 1 AND 100),
 match_anchor geometry(Point,4326),
 raw_geometry geometry,
 raw_crs integer GENERATED ALWAYS AS (ST_SRID(raw_geometry)) STORED,
 geometry_4326 geometry(MultiPolygon,4326),
 local_unit text CHECK (local_unit IN ('PAGE_POINT','PIXEL','LOCAL_METRE')),
 local_page smallint CHECK (local_page BETWEEN 1 AND 8),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(id,case_id),
 FOREIGN KEY (parcel_id,property_id) REFERENCES sell.parcels(id,property_id),
 FOREIGN KEY (property_revision_id,property_id) REFERENCES sell.property_revisions(id,property_id),
 FOREIGN KEY (case_id,property_revision_id) REFERENCES sell.verification_cases(id,property_revision_id),
 FOREIGN KEY (case_id,source_evidence_id) REFERENCES sell.case_evidence(case_id,evidence_id),
 CHECK ((method='ATLAS_OFFICIAL_BOUNDARY')=(atlas_record_id IS NOT NULL)),
 CHECK ((atlas_record_id IS NULL)=(match_anchor IS NULL)),
 CHECK (method<>'DOCUMENT_ONLY' OR source_evidence_id IS NOT NULL),
 CHECK (method<>'MALAK_DOC_GEOREFERENCED' OR source_evidence_id IS NOT NULL),
 CHECK (method='DOCUMENT_ONLY' OR (local_unit IS NULL AND local_page IS NULL)),
 CHECK (method<>'DOCUMENT_ONLY' OR
       (raw_geometry IS NULL AND local_unit IS NULL AND local_page IS NULL) OR
       (raw_geometry IS NOT NULL AND local_unit IS NOT NULL AND local_page IS NOT NULL))
);
CREATE INDEX parcel_geometry_scope_idx ON sell.parcel_geometry_versions(property_revision_id,case_id);
CREATE INDEX parcel_geometry_gix ON sell.parcel_geometry_versions USING gist(geometry_4326);
CREATE TRIGGER parcel_geometry_immutable BEFORE UPDATE OR DELETE ON sell.parcel_geometry_versions
 FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE FUNCTION sell.derive_parcel_geometry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE atlas sell.atlas_parcel_source_records%ROWTYPE;
BEGIN
 IF NEW.source_evidence_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM sell.evidence_upload_receipts u
    WHERE (u.case_id,u.evidence_id)=(NEW.case_id,NEW.source_evidence_id) AND
     u.evidence_kind IN ('CADASTRAL_PLAN','TITLE_EXTRACT','OWNERSHIP_CERTIFICATE',
      'SHARED_OWNERSHIP_CERTIFICATE','SALE_DEED','TRANSFER_CARD','POSSESSION_REPORT','OTHER_LEGAL_PROOF')
 ) THEN RAISE EXCEPTION 'PARCEL_SOURCE_EVIDENCE_REQUIRED' USING ERRCODE='23514'; END IF;
 IF NEW.method='ATLAS_OFFICIAL_BOUNDARY' THEN
   SELECT * INTO atlas FROM sell.atlas_parcel_source_records WHERE id=NEW.atlas_record_id;
   IF NOT FOUND OR atlas.classification<>'OFFICIAL_CADASTRAL'
     OR (atlas.registry_scope,atlas.title_reference,atlas.plot_reference,atlas.locality_key)
        IS DISTINCT FROM (NEW.registry_scope,NEW.title_reference,NEW.plot_reference,NEW.locality_key)
     OR NOT COALESCE(ST_Covers(atlas.geometry_4326,NEW.match_anchor),false) THEN
     RAISE EXCEPTION 'OFFICIAL_ATLAS_SCOPE_REQUIRED' USING ERRCODE='23514';
   END IF;
   NEW.raw_geometry:=atlas.raw_geometry;
 ELSIF NEW.method='DOCUMENT_ONLY' THEN
   IF NEW.raw_geometry IS NOT NULL AND
     (ST_SRID(NEW.raw_geometry)<>0 OR GeometryType(NEW.raw_geometry)<>'POLYGON'
      OR ST_NDims(NEW.raw_geometry)<>2 OR ST_NPoints(NEW.raw_geometry)>129
      OR ST_IsEmpty(NEW.raw_geometry) OR NOT ST_IsValid(NEW.raw_geometry)) THEN
     RAISE EXCEPTION 'INVALID_LOCAL_PLAN_OUTLINE' USING ERRCODE='22023';
   END IF;
   NEW.geometry_4326:=NULL;
   RETURN NEW;
 ELSIF NEW.raw_geometry IS NULL OR (NEW.method='OWNER_TRACED_ESTIMATE' AND ST_SRID(NEW.raw_geometry)<>4326) THEN
   RAISE EXCEPTION 'INVALID_PARCEL_BOUNDARY' USING ERRCODE='22023';
 END IF;
 NEW.geometry_4326:=sell.parcel_geometry_wgs84(NEW.raw_geometry);
 RETURN NEW;
END $$;
CREATE TRIGGER derive_parcel_geometry BEFORE INSERT ON sell.parcel_geometry_versions
 FOR EACH ROW EXECUTE FUNCTION sell.derive_parcel_geometry();

CREATE TABLE sell.parcel_edge_measurements (
 geometry_version_id uuid NOT NULL REFERENCES sell.parcel_geometry_versions(id),
 ordinal smallint NOT NULL CHECK (ordinal BETWEEN 1 AND 4000),
 stated_length_m numeric(13,6) CHECK (stated_length_m>0 AND stated_length_m<10000000),
 source_page smallint CHECK (source_page BETWEEN 1 AND 8),
 stated_bearing_deg numeric(7,4) CHECK (stated_bearing_deg>=0 AND stated_bearing_deg<360),
 computed_length_m numeric(13,6) CHECK (computed_length_m>0 AND computed_length_m<10000000),
 PRIMARY KEY(geometry_version_id,ordinal),
 CHECK (stated_length_m IS NOT NULL OR computed_length_m IS NOT NULL),
 CHECK (stated_length_m IS NULL OR source_page IS NOT NULL)
);
CREATE TRIGGER parcel_edge_immutable BEFORE UPDATE OR DELETE ON sell.parcel_edge_measurements
 FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

-- A separately re-encoded and checked rendition, distinct from its private original.
CREATE TABLE sell.parcel_document_versions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 property_revision_id uuid NOT NULL,
 case_id uuid NOT NULL,
 source_evidence_id uuid NOT NULL,
 original_sha256 text NOT NULL CHECK (original_sha256~'^[0-9a-f]{64}$'),
 rendition_sha256 text NOT NULL CHECK (rendition_sha256~'^[0-9a-f]{64}$'),
 rendition_mime text NOT NULL CHECK (rendition_mime IN ('application/pdf','image/jpeg','image/png')),
 document_kind text NOT NULL CHECK (document_kind IN
   ('CADASTRAL_PLAN','OWNERSHIP_CERTIFICATE','SHARED_OWNERSHIP_CERTIFICATE','TITLE_EXTRACT',
    'SALE_DEED','TRANSFER_CARD','POSSESSION_REPORT')),
 storage_object_id uuid NOT NULL UNIQUE,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(id,case_id),
 UNIQUE(case_id,source_evidence_id,rendition_sha256),
 FOREIGN KEY (case_id,property_revision_id) REFERENCES sell.verification_cases(id,property_revision_id),
 FOREIGN KEY (case_id,source_evidence_id) REFERENCES sell.case_evidence(case_id,evidence_id),
 CHECK (original_sha256<>rendition_sha256)
);
CREATE INDEX parcel_document_scope_idx ON sell.parcel_document_versions(property_revision_id,case_id);
CREATE TRIGGER parcel_document_immutable BEFORE UPDATE OR DELETE ON sell.parcel_document_versions
 FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE FUNCTION sell.guard_parcel_document_original() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM sell.evidence_upload_receipts e
   WHERE (e.case_id,e.evidence_id,e.sha256,e.evidence_kind)=
    (NEW.case_id,NEW.source_evidence_id,NEW.original_sha256,NEW.document_kind))
 THEN RAISE EXCEPTION 'DOCUMENT_SOURCE_NOT_PUBLISHABLE' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER enforce_parcel_document_original BEFORE INSERT ON sell.parcel_document_versions
 FOR EACH ROW EXECUTE FUNCTION sell.guard_parcel_document_original();

CREATE TABLE sell.parcel_geometry_review_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 geometry_version_id uuid NOT NULL,
 case_id uuid NOT NULL,
 reviewer_user_id uuid NOT NULL REFERENCES sell.users(id),
 decision text NOT NULL CHECK (decision IN ('APPROVED','CHANGES_REQUESTED','REJECTED','REVOKED')),
 include_reference_labels boolean NOT NULL DEFAULT false,
 decided_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (geometry_version_id,case_id) REFERENCES sell.parcel_geometry_versions(id,case_id)
);
CREATE INDEX parcel_geometry_review_latest_idx ON sell.parcel_geometry_review_events(geometry_version_id,id DESC);
CREATE TRIGGER parcel_geometry_review_immutable BEFORE UPDATE OR DELETE ON sell.parcel_geometry_review_events
 FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE TABLE sell.parcel_document_review_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 document_version_id uuid NOT NULL,
 case_id uuid NOT NULL,
 reviewer_user_id uuid NOT NULL REFERENCES sell.users(id),
 decision text NOT NULL CHECK (decision IN ('APPROVED','REDACTION_REQUIRED','PRIVATE_ONLY','REVOKED')),
 redaction_checked boolean NOT NULL DEFAULT false,
 rights_cleared boolean NOT NULL DEFAULT false,
 decided_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (document_version_id,case_id) REFERENCES sell.parcel_document_versions(id,case_id),
 CHECK (decision<>'APPROVED' OR (redaction_checked AND rights_cleared))
);
CREATE INDEX parcel_document_review_latest_idx ON sell.parcel_document_review_events(document_version_id,id DESC);
CREATE TRIGGER parcel_document_review_immutable BEFORE UPDATE OR DELETE ON sell.parcel_document_review_events
 FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE FUNCTION sell.guard_parcel_review_actor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM sell.verification_cases
  WHERE id=NEW.case_id AND assigned_reviewer_user_id=NEW.reviewer_user_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'ASSIGNED_PARCEL_REVIEWER_REQUIRED' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER enforce_parcel_geometry_reviewer BEFORE INSERT ON sell.parcel_geometry_review_events
 FOR EACH ROW EXECUTE FUNCTION sell.guard_parcel_review_actor();
CREATE TRIGGER enforce_parcel_document_reviewer BEFORE INSERT ON sell.parcel_document_review_events
 FOR EACH ROW EXECUTE FUNCTION sell.guard_parcel_review_actor();

CREATE FUNCTION sell.freeze_reviewed_parcel_edges() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM sell.verification_cases c JOIN sell.parcel_geometry_versions g ON g.case_id=c.id
  WHERE g.id=NEW.geometry_version_id FOR UPDATE OF c;
 IF EXISTS (SELECT 1 FROM sell.parcel_geometry_review_events WHERE geometry_version_id=NEW.geometry_version_id)
 THEN RAISE EXCEPTION 'PARCEL_REVIEW_STARTED_CREATE_NEW_VERSION' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER freeze_reviewed_parcel_edges BEFORE INSERT ON sell.parcel_edge_measurements
 FOR EACH ROW EXECUTE FUNCTION sell.freeze_reviewed_parcel_edges();

CREATE TABLE sell.parcel_geometry_consent_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 geometry_version_id uuid NOT NULL,
 case_id uuid NOT NULL,
 actor_user_id uuid NOT NULL REFERENCES sell.users(id),
 decision text NOT NULL CHECK (decision IN ('GRANTED','REVOKED')),
 recorded_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(geometry_version_id,case_id) REFERENCES sell.parcel_geometry_versions(id,case_id)
);
CREATE INDEX parcel_geometry_consent_latest_idx ON sell.parcel_geometry_consent_events(geometry_version_id,id DESC);
CREATE TRIGGER parcel_geometry_consent_immutable BEFORE UPDATE OR DELETE ON sell.parcel_geometry_consent_events
 FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE TABLE sell.parcel_document_consent_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 document_version_id uuid NOT NULL,
 case_id uuid NOT NULL,
 actor_user_id uuid NOT NULL REFERENCES sell.users(id),
 decision text NOT NULL CHECK (decision IN ('GRANTED','REVOKED')),
 recorded_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(document_version_id,case_id) REFERENCES sell.parcel_document_versions(id,case_id)
);
CREATE INDEX parcel_document_consent_latest_idx ON sell.parcel_document_consent_events(document_version_id,id DESC);
CREATE TRIGGER parcel_document_consent_immutable BEFORE UPDATE OR DELETE ON sell.parcel_document_consent_events
 FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE FUNCTION sell.guard_parcel_consent_actor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM sell.verification_cases c
 JOIN sell.ownership_claims claim ON claim.id=c.ownership_claim_id
 JOIN sell.party_memberships member ON member.party_id=claim.claimant_party_id
 WHERE c.id=NEW.case_id AND member.user_id=NEW.actor_user_id
  AND member.capacity IN ('OWNER','REPRESENTATIVE') FOR UPDATE OF c;
 IF NOT FOUND THEN RAISE EXCEPTION 'PARCEL_PUBLICATION_CONSENT_ACTOR_REQUIRED' USING ERRCODE='42501'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER enforce_parcel_geometry_consent BEFORE INSERT ON sell.parcel_geometry_consent_events
 FOR EACH ROW EXECUTE FUNCTION sell.guard_parcel_consent_actor();
CREATE TRIGGER enforce_parcel_document_consent BEFORE INSERT ON sell.parcel_document_consent_events
 FOR EACH ROW EXECUTE FUNCTION sell.guard_parcel_consent_actor();

CREATE VIEW sell.public_listing_parcel_boundaries WITH (security_barrier=true) AS
 SELECT l.id AS listing_id,g.id AS boundary_id,g.method,ST_AsGeoJSON(g.geometry_4326)::jsonb AS geometry,
  a.source_version,
  CASE WHEN review.include_reference_labels THEN g.title_reference END AS title_reference,
  CASE WHEN review.include_reference_labels THEN g.plot_reference END AS plot_reference
 FROM sell.public_listing_cards l JOIN sell.listing_items i ON i.listing_id=l.id
 JOIN sell.parcel_geometry_versions g ON (g.property_revision_id,g.case_id)=(i.property_revision_id,i.verification_case_id)
 LEFT JOIN sell.atlas_parcel_source_records a ON a.id=g.atlas_record_id
 JOIN LATERAL (SELECT r.decision,r.include_reference_labels FROM sell.parcel_geometry_review_events r
  WHERE r.geometry_version_id=g.id ORDER BY r.id DESC LIMIT 1) review ON review.decision='APPROVED'
 JOIN LATERAL (SELECT c.decision FROM sell.parcel_geometry_consent_events c
  WHERE c.geometry_version_id=g.id ORDER BY c.id DESC LIMIT 1) consent ON consent.decision='GRANTED'
 WHERE g.geometry_4326 IS NOT NULL
  AND (g.atlas_record_id IS NULL OR a.allow_boundary_redistribution);

CREATE VIEW sell.public_listing_parcel_documents WITH (security_barrier=true) AS
 SELECT l.id AS listing_id,d.id AS document_id,d.document_kind,d.rendition_mime,
  d.rendition_sha256,review.decided_at AS reviewed_at
 FROM sell.public_listing_cards l JOIN sell.listing_items i ON i.listing_id=l.id
 JOIN sell.parcel_document_versions d ON (d.property_revision_id,d.case_id)=(i.property_revision_id,i.verification_case_id)
 JOIN LATERAL (SELECT r.decision,r.decided_at FROM sell.parcel_document_review_events r
  WHERE r.document_version_id=d.id ORDER BY r.id DESC LIMIT 1) review ON review.decision='APPROVED'
 JOIN LATERAL (SELECT c.decision FROM sell.parcel_document_consent_events c
  WHERE c.document_version_id=d.id ORDER BY c.id DESC LIMIT 1) consent ON consent.decision='GRANTED';

-- Future delivery retrieves the eligible outbound geometry at send time. The
-- outbox itself carries no raw source or private metadata.
CREATE TABLE sell.atlas_parcel_outbox (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 listing_id uuid NOT NULL REFERENCES sell.listings(id),
 geometry_version_id uuid NOT NULL REFERENCES sell.parcel_geometry_versions(id),
 layer_id text GENERATED ALWAYS AS ('MALAK_OWNER_SUBMITTED'::text) STORED,
 event_kind text NOT NULL CHECK (event_kind IN ('UPSERT','WITHDRAW')),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(listing_id,geometry_version_id,event_kind)
);
CREATE TRIGGER atlas_parcel_outbox_immutable BEFORE UPDATE OR DELETE ON sell.atlas_parcel_outbox
 FOR EACH ROW EXECUTE FUNCTION sell.reject_immutable_change();

CREATE FUNCTION sell.enqueue_atlas_parcel_event(p_listing uuid,p_version uuid,p_event text)
RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE event_id uuid;
BEGIN
 IF p_event IS NULL OR p_event NOT IN ('UPSERT','WITHDRAW') THEN
   RAISE EXCEPTION 'ATLAS_CONTRIBUTION_INVALID_EVENT' USING ERRCODE='22023';
 END IF;
 PERFORM 1 FROM sell.parcel_geometry_versions WHERE id=p_version FOR UPDATE;
 IF p_event='UPSERT' THEN
   IF EXISTS (SELECT 1 FROM sell.atlas_parcel_outbox
    WHERE (listing_id,geometry_version_id,event_kind)=(p_listing,p_version,'WITHDRAW'))
   OR NOT EXISTS (SELECT 1 FROM sell.public_listing_parcel_boundaries
    WHERE (listing_id,boundary_id)=(p_listing,p_version) AND
     method IN ('OWNER_TRACED_ESTIMATE','MALAK_DOC_GEOREFERENCED')) THEN
     RAISE EXCEPTION 'ATLAS_CONTRIBUTION_NOT_ELIGIBLE' USING ERRCODE='23514';
   END IF;
 ELSIF NOT EXISTS (SELECT 1 FROM sell.atlas_parcel_outbox
    WHERE (listing_id,geometry_version_id,event_kind)=(p_listing,p_version,'UPSERT')) THEN
   RAISE EXCEPTION 'ATLAS_CONTRIBUTION_NOT_ELIGIBLE' USING ERRCODE='23514';
 END IF;

 INSERT INTO sell.atlas_parcel_outbox(listing_id,geometry_version_id,event_kind)
 VALUES(p_listing,p_version,p_event)
 ON CONFLICT(listing_id,geometry_version_id,event_kind) DO NOTHING RETURNING id INTO event_id;
 IF NOT FOUND THEN
   SELECT id INTO STRICT event_id FROM sell.atlas_parcel_outbox
    WHERE (listing_id,geometry_version_id,event_kind)=(p_listing,p_version,p_event);
 END IF;
 RETURN event_id;
END $$;

REVOKE ALL ON sell.atlas_parcel_source_records,sell.parcel_geometry_versions,sell.parcel_edge_measurements,
 sell.parcel_document_versions,sell.parcel_geometry_review_events,sell.parcel_geometry_consent_events,
 sell.parcel_document_review_events,sell.parcel_document_consent_events,sell.atlas_parcel_outbox,
 sell.public_listing_parcel_boundaries,sell.public_listing_parcel_documents FROM PUBLIC;
REVOKE ALL ON FUNCTION sell.register_atlas_parcel_record(text,text,text,text,text,text,text,text,text,text,text,boolean,geometry),
 sell.match_atlas_parcel_candidates(text,text,text,text,geometry),
 sell.enqueue_atlas_parcel_event(uuid,uuid,text) FROM PUBLIC;

COMMIT;
