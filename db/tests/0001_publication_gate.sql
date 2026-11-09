-- Synthetic facts ONLY. Fails closed and rolls back all inserted test records.
BEGIN;
DO $test$
DECLARE
 seller uuid; reviewer uuid; p1 uuid; p2 uuid; prop1 uuid; prop2 uuid;
 r1 uuid; r2 uuid; r3 uuid; c1 uuid; c2 uuid; c3 uuid;
 v1 uuid; v2 uuid; v3 uuid; dsha text; listing_id uuid;
 blocked boolean;
BEGIN
 INSERT INTO sell.parties(kind) VALUES ('PERSON') RETURNING id INTO seller;
 INSERT INTO sell.users(phone_e164) VALUES ('+21699123456') RETURNING id INTO reviewer;
 INSERT INTO sell.properties(kind,country_code) VALUES ('LAND','TN') RETURNING id INTO prop1;
 INSERT INTO sell.property_revisions(property_id,revision_number,snapshot)
 VALUES (prop1,1,'{"land":"synthetic","right":"sole"}') RETURNING id,snapshot_sha256 INTO r1,dsha;
 INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity)
 VALUES (prop1,seller,'SOLE_OWNER') RETURNING id INTO c1;
 INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,assigned_reviewer_user_id,status,expires_at)
 VALUES (r1,c1,reviewer,'PENDING',now()+interval '7 days') RETURNING id INTO v1;
 INSERT INTO sell.listings(seller_party_id,title,price_tnd)
 VALUES (seller,'Synthetic parcel',125000.000) RETURNING id INTO listing_id;
 INSERT INTO sell.listing_items(listing_id,property_id,property_revision_id,verification_case_id)
 VALUES(listing_id,prop1,r1,v1);
 IF EXISTS (SELECT 1 FROM sell.public_listing_cards WHERE id=listing_id) THEN
   RAISE EXCEPTION 'FAIL: pending item published';
 END IF;

 blocked:=false;
 BEGIN
   UPDATE sell.listings SET status='PUBLISHED' WHERE id=listing_id;
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM LIKE 'VERIFICATION_REQUIRED:%' THEN blocked:=true; ELSE RAISE; END IF;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'FAIL: publication accepted without decision'; END IF;

 INSERT INTO sell.verification_decisions(case_id,reviewer_user_id,result,snapshot_sha256,reason_code)
 VALUES (v1,reviewer,'APPROVED',dsha,'SYNTHETIC_OK');
 UPDATE sell.verification_cases SET status='VERIFIED' WHERE id=v1;
 UPDATE sell.listings SET status='PUBLISHED' WHERE id=listing_id;
 IF (SELECT count(*) FROM sell.public_listing_cards WHERE id=listing_id)<>1 THEN
   RAISE EXCEPTION 'FAIL: approved listing is invisible';
 END IF;

 UPDATE sell.verification_cases SET status='SUSPENDED' WHERE id=v1;
 IF EXISTS (SELECT 1 FROM sell.public_listing_cards WHERE id=listing_id) THEN
   RAISE EXCEPTION 'FAIL: suspended case leaked to public';
 END IF;
 UPDATE sell.verification_cases SET status='VERIFIED' WHERE id=v1;

 -- New legally relevant revision invalidates the previous approved public snapshot.
 INSERT INTO sell.property_revisions(property_id,revision_number,snapshot)
 VALUES (prop1,2,'{"land":"synthetic","right":"revised"}')
 RETURNING id,snapshot_sha256 INTO r2,dsha;
 IF EXISTS (SELECT 1 FROM sell.public_listing_cards WHERE id=listing_id) THEN
   RAISE EXCEPTION 'FAIL: stale revision remained public';
 END IF;

 INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,assigned_reviewer_user_id,status,expires_at)
 VALUES(r2,c1,reviewer,'PENDING',now()+interval '7 days') RETURNING id INTO v2;
 INSERT INTO sell.verification_decisions(case_id,reviewer_user_id,result,snapshot_sha256,reason_code)
 VALUES(v2,reviewer,'APPROVED',dsha,'SYNTHETIC_RECHECK');
 UPDATE sell.verification_cases SET status='VERIFIED' WHERE id=v2;
 UPDATE sell.listing_items SET property_revision_id=r2,verification_case_id=v2
 WHERE listing_id=listing_id AND property_id=prop1;
 IF (SELECT status FROM sell.listings WHERE id=listing_id)<>'PAUSED' THEN
   RAISE EXCEPTION 'FAIL: scope mutation did not pause listing';
 END IF;
 UPDATE sell.listings SET status='PUBLISHED' WHERE id=listing_id;

 -- Multiple property listing must not leak when one item is not verified.
 INSERT INTO sell.properties(kind,country_code) VALUES ('LAND','TN') RETURNING id INTO prop2;
 INSERT INTO sell.property_revisions(property_id,revision_number,snapshot)
 VALUES(prop2,1,'{"parcel":"second synthetic"}') RETURNING id,snapshot_sha256 INTO r3,dsha;
 INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity)
 VALUES(prop2,seller,'SOLE_OWNER') RETURNING id INTO c2;
 INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,assigned_reviewer_user_id,status,expires_at)
 VALUES(r3,c2,reviewer,'PENDING',now()+interval '7 days') RETURNING id INTO v3;
 INSERT INTO sell.listing_items(listing_id,property_id,property_revision_id,verification_case_id)
 VALUES (listing_id,prop2,r3,v3);
 blocked:=false;
 BEGIN
   UPDATE sell.listings SET status='PUBLISHED' WHERE id=listing_id;
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM LIKE 'VERIFICATION_REQUIRED:%' THEN blocked:=true; ELSE RAISE; END IF;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'FAIL: multi-item unverified listing published'; END IF;

 INSERT INTO sell.verification_decisions(case_id,reviewer_user_id,result,snapshot_sha256,reason_code)
 VALUES(v3,reviewer,'APPROVED',dsha,'SYNTHETIC_OK');
 UPDATE sell.verification_cases SET status='VERIFIED' WHERE id=v3;
 UPDATE sell.listings SET status='PUBLISHED' WHERE id=listing_id;
 IF (SELECT item_count FROM sell.public_listing_cards WHERE id=listing_id)<>2 THEN
   RAISE EXCEPTION 'FAIL: multi-item approved listing missing';
 END IF;
 UPDATE sell.verification_cases SET expires_at=now()-interval '1 minute' WHERE id=v3;
 IF EXISTS (SELECT 1 FROM sell.public_listing_cards WHERE id=listing_id) THEN
   RAISE EXCEPTION 'FAIL: expired case remained public';
 END IF;
 RAISE NOTICE 'PASS: verification/publication/suspension/revision/multi-item/expiry';
END
$test$;
ROLLBACK;
