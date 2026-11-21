-- Public discovery is a read model over the existing verification gate.
BEGIN;

CREATE FUNCTION sell.fold_discovery_place(input_name text)
RETURNS text LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
SET search_path=pg_catalog
AS $$
  SELECT btrim(regexp_replace(
    replace(replace(
      regexp_replace(lower(normalize(input_name, NFKD)), U&'[\0300-\036f\064b-\065f\0670\0640]', '', 'g'),
      'œ','oe'),'æ','ae'),
    '[[:space:]]+', ' ', 'g'))
$$;

CREATE TABLE sell.discovery_places (
  id text PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9._:-]{0,95}$'),
  source_version text NOT NULL CHECK (length(btrim(source_version)) BETWEEN 1 AND 160),
  name_ar text NOT NULL CHECK (length(btrim(name_ar)) BETWEEN 1 AND 140),
  name_fr text NOT NULL CHECK (length(btrim(name_fr)) BETWEEN 1 AND 140),
  search_ar text GENERATED ALWAYS AS (sell.fold_discovery_place(name_ar)) STORED,
  search_fr text GENERATED ALWAYS AS (sell.fold_discovery_place(name_fr)) STORED,
  center geometry(Point,4326) NOT NULL,
  CHECK (NOT ST_IsEmpty(center) AND ST_X(center) BETWEEN -180 AND 180
    AND ST_Y(center) BETWEEN -85.051129 AND 85.051129)
);
CREATE INDEX discovery_places_center_gix ON sell.discovery_places USING gist(center);

CREATE TABLE sell.discovery_place_aliases (
  place_id text NOT NULL REFERENCES sell.discovery_places(id),
  alias text NOT NULL CHECK (length(btrim(alias)) BETWEEN 1 AND 140),
  search_alias text GENERATED ALWAYS AS (sell.fold_discovery_place(alias)) STORED,
  PRIMARY KEY(place_id,alias)
);
CREATE INDEX discovery_place_alias_prefix_idx
  ON sell.discovery_place_aliases(search_alias text_pattern_ops,place_id);

-- Typed display data is written with the draft revision, before it is submitted.
-- It is independent of raw OCR and other private fields in a revision snapshot.
CREATE TABLE sell.listing_discovery_details (
  property_revision_id uuid PRIMARY KEY REFERENCES sell.property_revisions(id),
  place_id text REFERENCES sell.discovery_places(id),
  area_m2 numeric(18,3) CHECK (area_m2 > 0 AND area_m2 <> 'NaN'::numeric),
  rooms smallint CHECK (rooms BETWEEN 0 AND 100),
  floor smallint CHECK (floor BETWEEN -10 AND 100),
  built_area_m2 numeric(18,3) CHECK (built_area_m2 > 0 AND built_area_m2 <> 'NaN'::numeric),
  land_use text CHECK (length(btrim(land_use)) BETWEEN 1 AND 100),
  description text CHECK (length(description)<=4000)
);
CREATE INDEX listing_discovery_place_idx ON sell.listing_discovery_details(place_id);

CREATE FUNCTION sell.validate_listing_discovery_details()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE property_kind text;
BEGIN
  -- Serialize the write with insertion of a case referencing this revision.
  SELECT p.kind INTO property_kind FROM sell.property_revisions r
    JOIN sell.properties p ON p.id=r.property_id
    WHERE r.id=NEW.property_revision_id
    FOR UPDATE OF r;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'DISCOVERY_REVISION_REQUIRED';
  END IF;
  IF EXISTS (SELECT 1 FROM sell.verification_cases c
             WHERE c.property_revision_id=NEW.property_revision_id) THEN
    RAISE EXCEPTION 'DISCOVERY_DETAILS_FROZEN: create a new property revision';
  END IF;
  IF (NEW.rooms IS NOT NULL AND property_kind NOT IN ('APARTMENT','HOUSE'))
    OR (NEW.floor IS NOT NULL AND property_kind <> 'APARTMENT')
    OR (NEW.built_area_m2 IS NOT NULL AND property_kind <> 'HOUSE')
    OR (NEW.land_use IS NOT NULL AND property_kind <> 'LAND') THEN
    RAISE EXCEPTION 'DISCOVERY_DETAILS_INVALID_FOR_KIND';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER validate_listing_discovery_details
BEFORE INSERT ON sell.listing_discovery_details FOR EACH ROW
EXECUTE FUNCTION sell.validate_listing_discovery_details();
CREATE TRIGGER listing_discovery_details_immutable
BEFORE UPDATE OR DELETE ON sell.listing_discovery_details FOR EACH ROW
EXECUTE FUNCTION sell.reject_immutable_change();

-- Asset identifiers refer to reviewed, re-encoded property photos in the
-- explicitly configured public media origin. Original evidence is separate.
CREATE TABLE sell.listing_public_photos (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  property_revision_id uuid NOT NULL REFERENCES sell.property_revisions(id),
  approved_by uuid NOT NULL REFERENCES sell.users(id),
  approved_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz CHECK (revoked_at >= approved_at),
  sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  asset_path text NOT NULL UNIQUE
    CHECK (asset_path ~ '^[A-Za-z0-9][A-Za-z0-9/_-]{0,246}\.(jpg|jpeg|png|webp)$'
      AND asset_path !~ '//')
);
CREATE INDEX listing_public_photos_active_idx
  ON sell.listing_public_photos(property_revision_id,approved_at,id) WHERE revoked_at IS NULL;

CREATE FUNCTION sell.guard_listing_photo_revocation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL
    OR ROW(NEW.id,NEW.property_revision_id,NEW.approved_by,NEW.approved_at,NEW.sha256,NEW.asset_path)
      IS DISTINCT FROM ROW(OLD.id,OLD.property_revision_id,OLD.approved_by,OLD.approved_at,OLD.sha256,OLD.asset_path) THEN
    RAISE EXCEPTION 'IMMUTABLE_RECORD: a reviewed photo may only be revoked';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER listing_photo_revocation_only
BEFORE UPDATE ON sell.listing_public_photos FOR EACH ROW
EXECUTE FUNCTION sell.guard_listing_photo_revocation();
CREATE TRIGGER listing_public_photos_keep_history
BEFORE DELETE ON sell.listing_public_photos FOR EACH ROW
EXECUTE FUNCTION sell.reject_immutable_change();

CREATE INDEX listings_public_newest_idx
  ON sell.listings(published_at DESC,id DESC) WHERE status='PUBLISHED';

-- A discovery card has exactly one property type, area and location. The
-- existing public cards view continues to support verified bundle listings.
CREATE VIEW sell.listing_discovery WITH (security_barrier=true) AS
SELECT card.id,card.title,card.price_tnd,card.published_at,p.kind,
  d.area_m2,d.rooms,d.description,d.place_id,
  place.name_ar AS region_ar,place.name_fr AS region_fr,place.center AS place_center,
  point.public_point,
  CASE
    WHEN point.public_point IS NOT NULL THEN 'APPROXIMATE'
    WHEN place.id IS NOT NULL THEN 'AREA_ONLY'
    ELSE 'UNKNOWN'
  END AS location_accuracy,
  photo.asset_path AS photo_asset_path
FROM sell.public_listing_cards card
JOIN sell.listing_items item ON item.listing_id=card.id
JOIN sell.properties p ON p.id=item.property_id
LEFT JOIN sell.listing_discovery_details d ON d.property_revision_id=item.property_revision_id
LEFT JOIN sell.discovery_places place ON place.id=d.place_id
CROSS JOIN LATERAL (
  SELECT CASE WHEN NOT ST_IsEmpty(p.public_point) AND ST_X(p.public_point) BETWEEN -180 AND 180
    AND ST_Y(p.public_point) BETWEEN -85.051129 AND 85.051129
    THEN p.public_point END AS public_point
) point
LEFT JOIN LATERAL (
  SELECT ph.asset_path FROM sell.listing_public_photos ph
  WHERE ph.property_revision_id=item.property_revision_id AND ph.revoked_at IS NULL
  ORDER BY ph.approved_at,ph.id LIMIT 1
) photo ON true
WHERE card.item_count=1 AND p.country_code='TN' AND p.kind IN ('LAND','APARTMENT','HOUSE');

REVOKE ALL ON sell.listing_discovery,sell.listing_discovery_details,
  sell.listing_public_photos,sell.discovery_places,sell.discovery_place_aliases FROM PUBLIC;
COMMIT;
