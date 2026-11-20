-- Synthetic boundaries and evidence only.
BEGIN;
DO $test$
DECLARE
 owner_id uuid; reviewer_id uuid; outsider_id uuid; seller_id uuid;
 property_id uuid; parcel_id uuid; revision_id uuid; claim_id uuid; case_id uuid;
 plan_id uuid; identity_id uuid; test_listing uuid; test_document uuid; snapshot_hash text;
 geometry_id uuid; local_geometry_id uuid; atlas_id uuid; repeated_id uuid; official_id uuid;
 plot geometry:=ST_GeomFromText('POLYGON((10 36,10.001 36,10.001 36.001,10 36.001,10 36))',4326);
 anchor geometry:=ST_SetSRID(ST_MakePoint(10.0005,36.0005),4326);
BEGIN
 INSERT INTO sell.users DEFAULT VALUES RETURNING id INTO owner_id;
 INSERT INTO sell.users DEFAULT VALUES RETURNING id INTO reviewer_id;
 INSERT INTO sell.users DEFAULT VALUES RETURNING id INTO outsider_id;
 INSERT INTO sell.parties(kind) VALUES ('PERSON') RETURNING id INTO seller_id;
 INSERT INTO sell.party_memberships VALUES (seller_id,owner_id,'OWNER');
 INSERT INTO sell.properties(kind) VALUES ('LAND') RETURNING id INTO property_id;
 INSERT INTO sell.parcels(property_id) VALUES (property_id) RETURNING id INTO parcel_id;
 INSERT INTO sell.property_revisions(property_id,revision_number,snapshot)
 VALUES (property_id,1,'{}') RETURNING id,snapshot_sha256 INTO revision_id,snapshot_hash;
 INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity)
 VALUES (property_id,seller_id,'SOLE_OWNER') RETURNING id INTO claim_id;
 INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,assigned_reviewer_user_id,expires_at)
 VALUES (revision_id,claim_id,reviewer_id,now()+interval '1 day') RETURNING id INTO case_id;
 INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
 VALUES (case_id,owner_id,'GRANTED','synthetic-1');
 INSERT INTO sell.evidence_files(storage_key,sha256,media_kind)
 VALUES ('private/synthetic-plan',repeat('a',64),'application/pdf') RETURNING id INTO plan_id;
 INSERT INTO sell.evidence_files(storage_key,sha256,media_kind)
 VALUES ('private/synthetic-identity',repeat('b',64),'image/jpeg') RETURNING id INTO identity_id;
 INSERT INTO sell.case_evidence VALUES (case_id,plan_id),(case_id,identity_id);
 INSERT INTO sell.evidence_upload_receipts(
  case_id,uploader_user_id,evidence_id,idempotency_key,sha256,wrapped_data_key,key_version,
  media_kind,file_size,evidence_kind,page_number)
 VALUES
 (case_id,owner_id,plan_id,'synthetic_plan_reference',repeat('a',64),repeat('a',40),'v1','application/pdf',100,
  'CADASTRAL_PLAN',NULL),
 (case_id,owner_id,identity_id,'synthetic_cin_reference',repeat('b',64),repeat('b',40),'v1','image/jpeg',100,
  'IDENTITY',1);

 INSERT INTO sell.parcel_geometry_versions(parcel_id,property_id,property_revision_id,case_id,
  method,source_evidence_id,raw_geometry,local_unit,local_page)
 VALUES (parcel_id,property_id,revision_id,case_id,'DOCUMENT_ONLY',plan_id,
  ST_GeomFromText('POLYGON((0 0,30 0,30 40,0 40,0 0))'),'PAGE_POINT',1)
 RETURNING id INTO local_geometry_id;
 IF (SELECT geometry_4326 FROM sell.parcel_geometry_versions WHERE id=local_geometry_id) IS NOT NULL
 THEN RAISE EXCEPTION 'FAIL: local scan placed on the map'; END IF;
 BEGIN
   INSERT INTO sell.parcel_geometry_versions(parcel_id,property_id,property_revision_id,case_id,
     method,source_evidence_id,raw_geometry)
   VALUES (parcel_id,property_id,revision_id,case_id,'MALAK_DOC_GEOREFERENCED',plan_id,
     ST_GeomFromText('POLYGON((10 36,10.001 36.001,10.001 36,10 36.001,10 36))',4326));
   RAISE EXCEPTION 'FAIL: self-intersecting boundary accepted';
 EXCEPTION WHEN SQLSTATE '22023' THEN
   IF SQLERRM <> 'INVALID_PARCEL_BOUNDARY' THEN RAISE; END IF;
 END;
 INSERT INTO sell.parcel_geometry_versions(parcel_id,property_id,property_revision_id,case_id,
  method,source_evidence_id,raw_geometry)
 VALUES (parcel_id,property_id,revision_id,case_id,'MALAK_DOC_GEOREFERENCED',plan_id,ST_Transform(plot,32632))
 RETURNING id INTO geometry_id;
 IF NOT EXISTS (
   SELECT 1 FROM sell.parcel_geometry_versions
   WHERE id=geometry_id AND raw_crs=32632 AND ST_SRID(geometry_4326)=4326 AND ST_Covers(geometry_4326,anchor)
 ) THEN RAISE EXCEPTION 'FAIL: source CRS lost or transform wrong'; END IF;
 INSERT INTO sell.parcel_edge_measurements(geometry_version_id,ordinal,stated_length_m,source_page)
 VALUES (geometry_id,1,30,1);

 atlas_id:=sell.register_atlas_parcel_record('ATLAS','agency','cadastre','00x','V1',repeat('c',64),
  'TN-REG-A','000991','0042','KE-1','OFFICIAL_CADASTRAL',true,plot);
 repeated_id:=sell.register_atlas_parcel_record('ATLAS','agency','cadastre','00x','V1',repeat('c',64),
  'TN-REG-A','000991','0042','KE-1','OFFICIAL_CADASTRAL',true,plot);
 IF atlas_id IS DISTINCT FROM repeated_id THEN RAISE EXCEPTION 'FAIL: duplicate Atlas import produced two rows'; END IF;
 BEGIN
   PERFORM sell.register_atlas_parcel_record('ATLAS','agency','cadastre','00x','V1',repeat('d',64),
    'TN-REG-A','000991','0042','KE-1','OFFICIAL_CADASTRAL',true,plot);
   RAISE EXCEPTION 'FAIL: conflicting Atlas version silently overwritten';
 EXCEPTION WHEN unique_violation THEN
   IF SQLERRM <> 'ATLAS_SOURCE_VERSION_CONFLICT' THEN RAISE; END IF;
 END;
 PERFORM sell.register_atlas_parcel_record('ATLAS','agency','cadastre','00y','V1',repeat('e',64),
  'TN-REG-A','000991','0042','OTHER-AREA','OFFICIAL_CADASTRAL',true,plot);
 PERFORM sell.register_atlas_parcel_record('ATLAS','agency','cadastre','00z','V1',repeat('f',64),
  'TN-REG-A','000991','0042','KE-1','OFFICIAL_CADASTRAL',true,ST_Translate(plot,0.5,0));
 IF (SELECT count(*) FROM sell.match_atlas_parcel_candidates('TN-REG-A','000991','0042','KE-1',anchor))<>2
 OR (SELECT count(*) FROM sell.match_atlas_parcel_candidates('TN-REG-A','000991','0042','KE-1',anchor)
     WHERE spatial_status='SPATIALLY_COMPATIBLE')<>1
 OR EXISTS (SELECT 1 FROM sell.match_atlas_parcel_candidates('TN-REG-A','991','0042','KE-1',anchor))
 THEN RAISE EXCEPTION 'FAIL: Atlas matching ignored source scope, identifiers or spatial mismatch'; END IF;
 IF EXISTS (
   SELECT 1 FROM sell.match_atlas_parcel_candidates('TN-REG-A','000991','0042','KE-1',NULL)
   WHERE spatial_status<>'NEEDS_LOCATION_REVIEW'
 ) THEN RAISE EXCEPTION 'FAIL: Atlas confirmed without location'; END IF;

 INSERT INTO sell.parcel_geometry_versions(parcel_id,property_id,property_revision_id,case_id,method,
  atlas_record_id,registry_scope,title_reference,plot_reference,locality_key,match_anchor)
 VALUES (parcel_id,property_id,revision_id,case_id,'ATLAS_OFFICIAL_BOUNDARY',
  atlas_id,'TN-REG-A','000991','0042','KE-1',anchor)
 RETURNING id INTO official_id;

 INSERT INTO sell.parcel_document_versions(property_revision_id,case_id,source_evidence_id,
  original_sha256,rendition_sha256,rendition_mime,document_kind,storage_object_id)
 VALUES (revision_id,case_id,plan_id,repeat('a',64),repeat('c',64),'application/pdf','CADASTRAL_PLAN',gen_random_uuid())
 RETURNING id INTO test_document;
 BEGIN
   INSERT INTO sell.parcel_document_versions(property_revision_id,case_id,source_evidence_id,
    original_sha256,rendition_sha256,rendition_mime,document_kind,storage_object_id)
   VALUES (revision_id,case_id,identity_id,repeat('b',64),repeat('d',64),'image/jpeg','CADASTRAL_PLAN',gen_random_uuid());
   RAISE EXCEPTION 'FAIL: identity original eligible for public derivative';
 EXCEPTION WHEN SQLSTATE '23514' THEN
   IF SQLERRM <> 'DOCUMENT_SOURCE_NOT_PUBLISHABLE' THEN RAISE; END IF;
 END;

 INSERT INTO sell.listings(seller_party_id,title,price_tnd)
 VALUES(seller_id,'Synthetic listing',1000) RETURNING id INTO test_listing;
 INSERT INTO sell.listing_items VALUES(test_listing,property_id,revision_id,case_id);
 INSERT INTO sell.verification_decisions(case_id,reviewer_user_id,result,snapshot_sha256,reason_code)
 VALUES(case_id,reviewer_id,'APPROVED',snapshot_hash,'SYNTHETIC_OK');
 UPDATE sell.verification_cases vc SET status='VERIFIED' WHERE vc.id=case_id;
 UPDATE sell.listings l SET status='PUBLISHED' WHERE l.id=test_listing;

 INSERT INTO sell.parcel_document_review_events(document_version_id,case_id,reviewer_user_id,decision,
  redaction_checked,rights_cleared)
 VALUES(test_document,case_id,reviewer_id,'APPROVED',true,true);
 IF EXISTS (SELECT 1 FROM sell.public_listing_parcel_documents d WHERE d.listing_id=test_listing)
 THEN RAISE EXCEPTION 'FAIL: review alone publicly exposed a document'; END IF;
 BEGIN
   INSERT INTO sell.parcel_document_consent_events(document_version_id,case_id,actor_user_id,decision)
   VALUES(test_document,case_id,outsider_id,'GRANTED');
   RAISE EXCEPTION 'FAIL: outside actor consented to public publication';
 EXCEPTION WHEN insufficient_privilege THEN
   IF SQLERRM <> 'PARCEL_PUBLICATION_CONSENT_ACTOR_REQUIRED' THEN RAISE; END IF;
 END;
 INSERT INTO sell.parcel_document_consent_events(document_version_id,case_id,actor_user_id,decision)
 VALUES(test_document,case_id,owner_id,'GRANTED');
 IF NOT EXISTS (SELECT 1 FROM sell.public_listing_parcel_documents d WHERE d.listing_id=test_listing AND d.document_id=test_document)
 OR EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='sell' AND table_name='public_listing_parcel_documents'
   AND column_name IN ('storage_object_id','source_evidence_id','original_sha256'))
 THEN RAISE EXCEPTION 'FAIL: authorized derivative projection missing or exposes private pointers'; END IF;

 INSERT INTO sell.parcel_geometry_review_events(geometry_version_id,case_id,reviewer_user_id,decision)
 VALUES (geometry_id,case_id,reviewer_id,'APPROVED'),(official_id,case_id,reviewer_id,'APPROVED'),
 (local_geometry_id,case_id,reviewer_id,'APPROVED');
 INSERT INTO sell.parcel_geometry_consent_events(geometry_version_id,case_id,actor_user_id,decision)
 VALUES (geometry_id,case_id,owner_id,'GRANTED'),(official_id,case_id,owner_id,'GRANTED'),
 (local_geometry_id,case_id,owner_id,'GRANTED');
 IF (SELECT count(*) FROM sell.public_listing_parcel_boundaries b WHERE b.listing_id=test_listing)<>2
 THEN RAISE EXCEPTION 'FAIL: the map included the unlocated scan or omitted a reviewed geographic boundary'; END IF;
 IF sell.enqueue_atlas_parcel_event(test_listing,geometry_id,'UPSERT') IS DISTINCT FROM
    sell.enqueue_atlas_parcel_event(test_listing,geometry_id,'UPSERT')
 THEN RAISE EXCEPTION 'FAIL: contribution is not idempotent'; END IF;
 BEGIN
   PERFORM sell.enqueue_atlas_parcel_event(test_listing,official_id,'UPSERT');
   RAISE EXCEPTION 'FAIL: official geometry exported as owner contribution';
 EXCEPTION WHEN check_violation THEN
   IF SQLERRM <> 'ATLAS_CONTRIBUTION_NOT_ELIGIBLE' THEN RAISE; END IF;
 END;

 IF (SELECT count(*) FROM sell.atlas_contribution_features c WHERE c.listing_id=test_listing)<>1
 OR NOT EXISTS (SELECT 1 FROM sell.atlas_contribution_features
  WHERE listing_id=test_listing AND boundary_id=geometry_id AND method='MALAK_DOC_GEOREFERENCED')
 THEN RAISE EXCEPTION 'FAIL: dedicated Atlas layer does not contain exactly the approved contributed polygon'; END IF;

 INSERT INTO sell.parcel_document_consent_events(document_version_id,case_id,actor_user_id,decision)
 VALUES(test_document,case_id,owner_id,'REVOKED');
 IF EXISTS (SELECT 1 FROM sell.public_listing_parcel_documents d WHERE d.listing_id=test_listing)
 THEN RAISE EXCEPTION 'FAIL: document consent revocation did not remove public metadata'; END IF;
 UPDATE sell.listings l SET status='PAUSED' WHERE l.id=test_listing;
 IF EXISTS (SELECT 1 FROM sell.public_listing_parcel_boundaries b WHERE b.listing_id=test_listing)
 THEN RAISE EXCEPTION 'FAIL: paused listing continues to expose geographic boundaries'; END IF;
 IF EXISTS (SELECT 1 FROM sell.atlas_contribution_features c WHERE c.listing_id=test_listing)
 THEN RAISE EXCEPTION 'FAIL: stale outbox still exported a paused listing'; END IF;
 PERFORM sell.enqueue_atlas_parcel_event(test_listing,geometry_id,'WITHDRAW');
END
$test$;
ROLLBACK;
