"""Synthetic public Atlas projection tests: fail closed, do not leak private data."""
from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from scripts.atlas_public_projection import (
    make_public_projection, _safe_file,
)
from scripts.atlas_cadastre_evidence_audit import write_private_json

KEY=b"synthetic-test-only-secret-not-for-real-publication-000001"


def fixture():
    return {
        "record_id":"private-provenance-91",
        "document_sha256":"a"*64,
        "classification":"OFFICIAL_CADASTRAL",
        "authority_verified":True,
        "title_parcel_link":"VERIFIED",
        "geometry_status":"GROUND_VERIFIED",
        "qa":{"target_crs":"EPSG:4326", "fit_points":3,
              "independent_holdout":1,"holdout_max_error_m":0.8},
        "geometry4326":{"type":"Polygon","coordinates":[[
            [10,36],[10.0001,36],[10.0001,36.0001],[10,36.0001],[10,36]
        ]]},
        "publication":{
            "manager_approved":True,
            "geometry_approved":True,
            "rights_cleared":True,
            "owner_consented":True,
            "listing_active":True,
        },
        "owner_name":"PRIVATE NAME DO NOT OUTPUT",
        "cin":"PRIVATE_CIN_VALUE_00001",
        "source_url":"https://private.example.com/private/123",
        "titre_foncier":"00000555941",
        "parcel_number":"0021",
        "original_pdf_text":"DO NOT OUTPUT",
    }


def revocations(**updates):
    manifest={"complete":True,"revision":10,"revoked_record_ids":[]}
    manifest.update(updates)
    return manifest


class AtlasPublicProjectionTest(unittest.TestCase):
    def test_valid_approved_plan_only_safe_allowlist(self):
        raw=fixture()
        result=make_public_projection([raw],revocations(),hmac_key=KEY)
        self.assertEqual(result["layerId"],"ATLAS_OFFICIAL_REVIEWED")
        self.assertEqual(len(result["features"]),1)
        feature=result["features"][0]
        self.assertTrue(feature["id"].startswith("ap_"))
        self.assertEqual(set(feature),{"type","id","properties","geometry"})
        self.assertEqual(set(feature["properties"]),{
            "publicId","atlasLayer","documentStatus","geometryStatus"
        })
        rendered=json.dumps(result,sort_keys=True)
        for private in ("PRIVATE NAME","PRIVATE_CIN","private.example",
                        "555941","0021","provenance-91","DO NOT OUTPUT","aaaaaaaa"):
            self.assertNotIn(private,rendered)
        self.assertEqual(len(feature["geometry"]["coordinates"][0]),5)

    def test_revocation_removes_feature_even_when_review_stale(self):
        result=make_public_projection(
            [fixture()],revocations(revoked_record_ids=["private-provenance-91"]),
            hmac_key=KEY)
        self.assertEqual(result["features"],[])

    def test_incomplete_revocation_snapshot_blocks_entire_export(self):
        with self.assertRaisesRegex(ValueError,"COMPLETE_REVOCATION"):
            make_public_projection([fixture()],revocations(complete=False),hmac_key=KEY)

    def test_missing_manager_approval_rights_consent_rejected(self):
        for missing in ("manager_approved","geometry_approved",
                        "rights_cleared","owner_consented","listing_active"):
            record=fixture()
            record["publication"][missing]=False
            self.assertEqual(make_public_projection(
                [record],revocations(),hmac_key=KEY)["features"],[])

    def test_planning_and_page_outlines_never_public_official(self):
        for classification in ("VERIFIED_PLANNING","UNKNOWN","CANDIDATE"):
            record=fixture()
            record["classification"]=classification
            self.assertEqual(make_public_projection(
                [record],revocations(),hmac_key=KEY)["features"],[])
        record=fixture()
        record["geometry_status"]="GEOREFERENCED_CANDIDATE"
        self.assertEqual(make_public_projection(
            [record],revocations(),hmac_key=KEY)["features"],[])

    def test_invalid_geometry_not_released(self):
        row=fixture()
        row["geometry4326"]["coordinates"][0][2]=[100,100]
        with self.assertRaises(ValueError):
            make_public_projection([row],revocations(),hmac_key=KEY)

    def test_no_duplicate_record_and_minimum_hmac_strength(self):
        record=fixture()
        with self.assertRaisesRegex(ValueError,"DUPLICATE"):
            make_public_projection([record,record],revocations(),hmac_key=KEY)
        with self.assertRaisesRegex(ValueError,"STRONG_SECRET"):
            make_public_projection([record],revocations(),hmac_key=b"weak")

    def test_sha_and_independent_holdout_mandatory(self):
        row=fixture()
        row["document_sha256"]="not-a-valid-hash"
        with self.assertRaisesRegex(ValueError,"VERIFIED_DOCUMENT_SHA256"):
            make_public_projection([row],revocations(),hmac_key=KEY)
        row=fixture()
        row["qa"]["independent_holdout"]=0
        self.assertEqual(make_public_projection(
            [row],revocations(),hmac_key=KEY)["features"],[])

    def test_private_files_only_and_atomic_nonoverwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            review=root/"reviews.json"
            write_private_json(review,[fixture()])
            self.assertEqual(_safe_file(review),[fixture()])
            output=root/"public-stage.json"
            result=make_public_projection(_safe_file(review),revocations(),hmac_key=KEY)
            write_private_json(output,result)
            self.assertEqual(output.stat().st_mode & 0o777,0o600)
            with self.assertRaises(FileExistsError):
                write_private_json(output,result)


if __name__=="__main__":
    unittest.main()
