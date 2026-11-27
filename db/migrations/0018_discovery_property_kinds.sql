-- Extend the verified discovery projection with explicitly classified villas and factories.
BEGIN;

ALTER TABLE sell.properties
  DROP CONSTRAINT properties_kind_check,
  ADD CONSTRAINT properties_kind_check
    CHECK (kind IN ('LAND','APARTMENT','VILLA','HOUSE','COMMERCIAL','FACTORY','OTHER'));

CREATE OR REPLACE FUNCTION sell.validate_listing_discovery_details()
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
  IF (NEW.rooms IS NOT NULL AND property_kind NOT IN ('APARTMENT','HOUSE','VILLA'))
    OR (NEW.floor IS NOT NULL AND property_kind <> 'APARTMENT')
    OR (NEW.built_area_m2 IS NOT NULL AND property_kind NOT IN ('HOUSE','VILLA','FACTORY'))
    OR (NEW.land_use IS NOT NULL AND property_kind <> 'LAND') THEN
    RAISE EXCEPTION 'DISCOVERY_DETAILS_INVALID_FOR_KIND';
  END IF;
  RETURN NEW;
END
$$;


CREATE OR REPLACE VIEW sell.listing_discovery WITH (security_barrier=true) AS
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
WHERE card.item_count=1 AND p.country_code='TN' AND p.kind IN ('LAND','APARTMENT','HOUSE','VILLA','FACTORY');

COMMIT;
