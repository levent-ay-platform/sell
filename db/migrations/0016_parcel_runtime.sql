BEGIN;

-- Optional short vertex identifiers from the plan, separate from title labels.
ALTER TABLE sell.parcel_edge_measurements
 ADD COLUMN edge_label text CHECK (edge_label ~ '^[A-Za-z0-9]{1,5}([-–][A-Za-z0-9]{1,5})?$');

ALTER TABLE sell.parcel_geometry_versions
 ADD COLUMN stated_area_m2 numeric(13,6) CHECK (stated_area_m2 > 0);

COMMIT;
