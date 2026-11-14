-- Disposable PostgreSQL/PostGIS synthetic-only proof, never production data.
BEGIN;
DO $test$
DECLARE
 owner_id uuid; stranger_id uuid; company_id uuid; seller_party uuid; company_party uuid;
 property_id uuid; revision_id uuid; claim_id uuid; case_id uuid;
 file_id uuid; file2_id uuid; digest text:=repeat('a',64); blocked boolean;
BEGIN
 INSERT INTO sell.users(phone_e164) VALUES ('+21690000010') RETURNING id INTO owner_id;
 INSERT INTO sell.users(phone_e164) VALUES ('+21690000011') RETURNING id INTO stranger_id;
 INSERT INTO sell.users(phone_e164) VALUES ('+21690000012') RETURNING id INTO company_id;
 INSERT INTO sell.parties(kind) VALUES('PERSON') RETURNING id INTO seller_party;
 INSERT INTO sell.party_memberships(party_id,user_id,capacity) VALUES(seller_party,owner_id,'OWNER');
 INSERT INTO sell.properties(kind) VALUES('LAND') RETURNING id INTO property_id;
 INSERT INTO sell.property_revisions(property_id,revision_number,snapshot)
   VALUES(property_id,1,'{"synthetic":true}') RETURNING id INTO revision_id;
 INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity)
   VALUES(property_id,seller_party,'SOLE_OWNER') RETURNING id INTO claim_id;
 INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,status)
   VALUES(revision_id,claim_id,'PENDING') RETURNING id INTO case_id;

 IF sell.case_accepts_individual_evidence(case_id,owner_id) THEN RAISE EXCEPTION 'FAIL: no consent allowed'; END IF;
 INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
   VALUES(case_id,owner_id,'GRANTED','synthetic-v1');
 IF NOT sell.case_accepts_individual_evidence(case_id,owner_id) THEN RAISE EXCEPTION 'FAIL: authorized consent denied'; END IF;
 IF sell.case_accepts_individual_evidence(case_id,stranger_id) THEN RAISE EXCEPTION 'FAIL: stranger allowed'; END IF;
 blocked:=false;
 BEGIN
   INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
     VALUES(case_id,stranger_id,'GRANTED','synthetic-v1');
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM LIKE 'INDIVIDUAL_CASE_CONSENT_REQUIRED%' THEN blocked:=true; ELSE RAISE; END IF;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'FAIL: stranger consent accepted'; END IF;

 INSERT INTO sell.evidence_files(storage_key,sha256,media_kind)
   VALUES('private/synthetic-only-object-1',digest,'application/pdf') RETURNING id INTO file_id;
 INSERT INTO sell.case_evidence(case_id,evidence_id) VALUES(case_id,file_id);
 blocked:=false;
 BEGIN
   INSERT INTO sell.evidence_upload_receipts(case_id,uploader_user_id,evidence_id,
     idempotency_key,sha256,wrapped_data_key,key_version,media_kind,file_size,evidence_kind,page_number)
   VALUES(case_id,stranger_id,file_id,'SYNTHETIC_KEY_001',digest,'wrapped:nonexistent-demo','v1',
     'application/pdf',500,'OWNERSHIP_CERTIFICATE',1);
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM LIKE 'PRIVATE_EVIDENCE_SCOPE_OR_CONSENT_REQUIRED%' THEN blocked:=true; ELSE RAISE; END IF;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'FAIL: stranger uploaded'; END IF;
 INSERT INTO sell.evidence_upload_receipts(case_id,uploader_user_id,evidence_id,
     idempotency_key,sha256,wrapped_data_key,key_version,media_kind,file_size,evidence_kind,page_number)
 VALUES(case_id,owner_id,file_id,'SYNTHETIC_KEY_001',digest,'wrapped:nonexistent-demo','v1',
   'application/pdf',500,'OWNERSHIP_CERTIFICATE',1);
 blocked:=false;
 BEGIN
   INSERT INTO sell.evidence_upload_receipts(case_id,uploader_user_id,evidence_id,
     idempotency_key,sha256,wrapped_data_key,key_version,media_kind,file_size,evidence_kind,page_number)
   VALUES(case_id,owner_id,file_id,'SYNTHETIC_KEY_001',digest,'wrapped:nonexistent-demo','v1',
    'application/pdf',500,'OWNERSHIP_CERTIFICATE',1);
 EXCEPTION WHEN unique_violation THEN blocked:=true;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'FAIL: replay accepted'; END IF;

 INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
   VALUES(case_id,owner_id,'REVOKED','synthetic-v1');
 IF sell.case_accepts_individual_evidence(case_id,owner_id) THEN RAISE EXCEPTION 'FAIL: revocation not effective'; END IF;
 INSERT INTO sell.evidence_files(storage_key,sha256,media_kind)
   VALUES('private/synthetic-only-object-2',repeat('b',64),'application/pdf') RETURNING id INTO file2_id;
 INSERT INTO sell.case_evidence(case_id,evidence_id) VALUES(case_id,file2_id);
 blocked:=false;
 BEGIN
   INSERT INTO sell.evidence_upload_receipts(case_id,uploader_user_id,evidence_id,
     idempotency_key,sha256,wrapped_data_key,key_version,media_kind,file_size,evidence_kind,page_number)
   VALUES(case_id,owner_id,file2_id,'SYNTHETIC_KEY_002',repeat('b',64),'wrapped:nonexistent-demo','v1',
    'application/pdf',500,'TITLE_EXTRACT',1);
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM LIKE 'PRIVATE_EVIDENCE_SCOPE_OR_CONSENT_REQUIRED%' THEN blocked:=true; ELSE RAISE; END IF;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'FAIL: revoked user uploaded'; END IF;

 INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
   VALUES(case_id,owner_id,'GRANTED','synthetic-v2');
 blocked:=false;
 BEGIN
   INSERT INTO sell.evidence_upload_receipts(case_id,uploader_user_id,evidence_id,
     idempotency_key,sha256,wrapped_data_key,key_version,media_kind,file_size,evidence_kind,page_number)
   VALUES(case_id,owner_id,file2_id,'SYNTHETIC_KEY_002',repeat('a',64),'wrapped:nonexistent-demo','v2',
    'application/pdf',500,'TITLE_EXTRACT',1);
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM LIKE 'EVIDENCE_BINDING_MISMATCH%' THEN blocked:=true; ELSE RAISE; END IF;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'FAIL: mismatched evidence hash linked'; END IF;

 -- Revocation must remain possible even after case closure; re-grant must not.
 UPDATE sell.verification_cases SET status='REJECTED' WHERE id=case_id;
 INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
   VALUES(case_id,owner_id,'REVOKED','synthetic-v2');
 blocked:=false;
 BEGIN
   INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
      VALUES(case_id,owner_id,'GRANTED','synthetic-v3');
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM LIKE 'INDIVIDUAL_CASE_CONSENT_REQUIRED%' THEN blocked:=true; ELSE RAISE; END IF;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'FAIL: closed case accepted consent'; END IF;

 INSERT INTO sell.parties(kind) VALUES('COMPANY') RETURNING id INTO company_party;
 INSERT INTO sell.party_memberships(party_id,user_id,capacity) VALUES(company_party,company_id,'OWNER');
 INSERT INTO sell.ownership_claims(property_id,claimant_party_id,declared_capacity)
  VALUES(property_id,company_party,'COMPANY_SIGNATORY') RETURNING id INTO claim_id;
 INSERT INTO sell.verification_cases(property_revision_id,ownership_claim_id,status)
  VALUES(revision_id,claim_id,'PENDING') RETURNING id INTO case_id;
 blocked:=false;
 BEGIN
   INSERT INTO sell.evidence_consent_events(case_id,person_user_id,decision,policy_version)
    VALUES(case_id,company_id,'GRANTED','synthetic-v1');
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM LIKE 'INDIVIDUAL_CASE_CONSENT_REQUIRED%' THEN blocked:=true; ELSE RAISE; END IF;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'FAIL: company flow allowed'; END IF;

 RAISE NOTICE 'PASS: consent authorization, revocation, replay, digest binding, companies excluded';
END
$test$;
ROLLBACK;
