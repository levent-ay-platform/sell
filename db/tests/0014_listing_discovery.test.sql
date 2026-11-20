-- Disposable PostGIS, synthetic data only. Roll back all fixture rows.
BEGIN;
DO $test$
DECLARE
  seller uuid;
  reviewer uuid;
  property uuid;
  revision uuid;
  claim uuid;
  review_case uuid;
  listing uuid;
  photo uuid;
  revision_hash text;
BEGIN
  INSERT INTO sell.parties(kind) VALUES ('PERSON') RETURNING id INTO seller;
  INSERT INTO sell.users DEFAULT VALUES RETURNING id INTO reviewer;
  INSERT INTO sell.discovery_places(id,source_version,name_ar,name_fr,center)
    VALUES ('qa-kelibia','synthetic-test','قليبية','Kélibia',ST_SetSRID(ST_MakePoint(11.092,36.851),4326));
  INSERT INTO sell.properties(kind) VALUES ('APARTMENT') RETURNING id INTO property;
  INSERT INTO sell.property_revisions(property_id,revision_number,snapshot)
    VALUES (property,1,'{"privateOriginal":"never projected"}')
    RETURNING id,snapshot_sha256 INTO revision,revision_hash;
  INSERT INTO sell.listing_discovery_details(property_revision_id,place_id,area_m2,rooms,floor,description)
    VALUES (revision,'qa-kelibia',123.457,3,2,'Synthetic public text');
  INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity)
    VALUES (property,seller,'SOLE_OWNER') RETURNING id INTO claim;
  INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,assigned_reviewer_user_id,expires_at)
    VALUES (revision,claim,reviewer,now()+interval '1 day') RETURNING id INTO review_case;
  INSERT INTO sell.listings(seller_party_id,title,price_tnd)
    VALUES (seller,'Synthetic apartment',201000.001) RETURNING id INTO listing;
  INSERT INTO sell.listing_items(listing_id,property_id,property_revision_id,verification_case_id)
    VALUES (listing,property,revision,review_case);
  IF EXISTS (SELECT 1 FROM sell.listing_discovery WHERE id=listing) THEN
    RAISE EXCEPTION 'PENDING listing visible';
  END IF;

  INSERT INTO sell.verification_decisions(case_id,reviewer_user_id,result,snapshot_sha256,reason_code)
    VALUES (review_case,reviewer,'APPROVED',revision_hash,'SYNTHETIC');
  UPDATE sell.verification_cases SET status='VERIFIED' WHERE id=review_case;
  UPDATE sell.listings SET status='PUBLISHED' WHERE id=listing;
  INSERT INTO sell.listing_public_photos(property_revision_id,approved_by,sha256,asset_path)
    VALUES (revision,reviewer,repeat('a',64),'qa/selected.jpg') RETURNING id INTO photo;

  IF NOT EXISTS (
    SELECT 1 FROM sell.listing_discovery d
    WHERE d.id=listing AND d.location_accuracy='AREA_ONLY'
      AND d.public_point IS NULL AND ST_X(d.place_center)=11.092
      AND d.price_tnd=201000.001 AND d.area_m2=123.457 AND d.rooms=3
      AND d.photo_asset_path='qa/selected.jpg'
      AND NOT to_jsonb(d) ?| ARRAY['privateOriginal','snapshot','evidence_id','storage_key']
  ) THEN
    RAISE EXCEPTION 'public projection does not match the approved area-only fixture';
  END IF;
  BEGIN
    UPDATE sell.listing_discovery_details SET rooms=1 WHERE property_revision_id=revision;
    RAISE EXCEPTION 'typed details changed without a revision';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'IMMUTABLE_RECORD:%' THEN RAISE; END IF;
  END;
  UPDATE sell.listing_public_photos SET revoked_at=now() WHERE id=photo;
  IF EXISTS (SELECT 1 FROM sell.listing_discovery WHERE id=listing AND photo_asset_path IS NOT NULL) THEN
    RAISE EXCEPTION 'revoked photo still projected';
  END IF;

  UPDATE sell.properties SET public_point=ST_SetSRID(ST_MakePoint(11.098,36.859),4326) WHERE id=property;
  IF NOT EXISTS (
    SELECT 1 FROM sell.listing_discovery WHERE id=listing AND location_accuracy='APPROXIMATE'
      AND ST_X(public_point)=11.098 AND ST_Y(public_point)=36.859
  ) THEN
    RAISE EXCEPTION 'eligible approximate location missing';
  END IF;
  UPDATE sell.verification_cases SET status='EXPIRED',expires_at=now()-interval '1 day' WHERE id=review_case;
  IF EXISTS (SELECT 1 FROM sell.listing_discovery WHERE id=listing) THEN
    RAISE EXCEPTION 'expired listing still projected';
  END IF;

  IF sell.fold_discovery_place('KÉLIBIA') <> 'kelibia'
    OR sell.fold_discovery_place('قَلِيبِيَة') <> sell.fold_discovery_place('قليبية') THEN
    RAISE EXCEPTION 'place folding failed';
  END IF;
  RAISE NOTICE 'PASS: discovery eligibility, location, approved media, expiry and AR/FR place names';
END
$test$;
ROLLBACK;
