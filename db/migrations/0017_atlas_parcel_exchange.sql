BEGIN;

-- Atlas reads this derived layer. Eligibility, consent and withdrawals are
-- evaluated at read time, so a stale outbox event cannot republish a polygon.
CREATE VIEW sell.atlas_contribution_features WITH (security_barrier=true) AS
SELECT b.listing_id,b.boundary_id,b.method,b.geometry,o.id AS event_id,o.created_at
FROM sell.atlas_parcel_outbox o
JOIN sell.public_listing_parcel_boundaries b
  ON (b.listing_id,b.boundary_id)=(o.listing_id,o.geometry_version_id)
WHERE o.event_kind='UPSERT'
  AND b.method IN ('OWNER_TRACED_ESTIMATE','MALAK_DOC_GEOREFERENCED')
  AND NOT EXISTS (
    SELECT 1 FROM sell.atlas_parcel_outbox withdrawn
    WHERE (withdrawn.listing_id,withdrawn.geometry_version_id)=(o.listing_id,o.geometry_version_id)
      AND withdrawn.event_kind='WITHDRAW'
  );

REVOKE ALL ON sell.atlas_contribution_features FROM PUBLIC;
COMMIT;
