"""Private Atlas->Amlak contract tests; never publishes title references."""
from __future__ import annotations

import unittest

from scripts.atlas_amlak_private_bridge import build_private_import_batch
from scripts.atlas_public_projection import make_public_projection
from scripts.tests.test_atlas_public_projection import fixture, revocations, KEY


def review():
    item=fixture()
    item.update({
        "source":{
            "namespace":"ATLAS",
            "source_id":"OTC",
            "layer_id":"PLAN_DE_CADASTRE",
            "external_id":"source-001",
            "version":"2026-11-24",
        },
        "registry_scope":"TN-NABEUL",
        "title_reference":"00000555941",
        "plot_reference":"0021",
        "locality_key":"KELIBIA",
    })
    return item


class PrivateAmlakBridgeTest(unittest.TestCase):
    def test_approved_maps_to_existing_importer_contract(self):
        batch=build_private_import_batch([review()],revocations())
        self.assertEqual(batch["apiContract"],"ReviewedAtlasParcel")
        self.assertEqual(batch["distribution"],"PRIVATE_DERIVED_AUTHORIZED_IMPORT_ONLY")
        self.assertEqual(len(batch["records"]),1)
        record=batch["records"][0]
        self.assertEqual(set(record),{
            "namespace","sourceId","layerId","externalId","version","sha256",
            "registryScope","titleReference","plotReference","localityKey",
            "classification","allowBoundaryRedistribution","geometry4326"
        })
        self.assertEqual(record["titleReference"],"00000555941")
        self.assertEqual(record["plotReference"],"0021")
        self.assertEqual(record["classification"],"OFFICIAL_CADASTRAL")
        self.assertTrue(record["allowBoundaryRedistribution"])
        public=make_public_projection([review()],revocations(),hmac_key=KEY)
        self.assertNotIn("555941",str(public))
        self.assertNotIn("0021",str(public))

    def test_revoked_record_never_imported(self):
        batch=build_private_import_batch([review()],
            revocations(revoked_record_ids=["private-provenance-91"]))
        self.assertEqual(batch["records"],[])

    def test_missing_scoped_provenance_rejected(self):
        for key in ("source_id","layer_id","external_id","version"):
            row=review()
            row["source"][key]=""
            with self.assertRaisesRegex(ValueError,"INVALID_PRIVATE_IMPORT_FIELD"):
                build_private_import_batch([row],revocations())

    def test_missing_title_reference_fails_closed(self):
        row=review()
        row["title_reference"]=None
        with self.assertRaisesRegex(ValueError,"INVALID_PRIVATE_IMPORT_FIELD"):
            build_private_import_batch([row],revocations())

    def test_unverified_plan_is_not_staged(self):
        for kind in ("CANDIDATE","PLAN_NOT_GEOREFERENCED","VERIFIED_PLANNING"):
            row=review()
            row["classification"]=kind
            self.assertEqual(build_private_import_batch(
                [row],revocations())["records"],[])

    def test_colliding_version_is_detected(self):
        row=review()
        other=review()
        other["record_id"]="different-review"
        with self.assertRaisesRegex(ValueError,"DUPLICATE_SOURCE_VERSION"):
            build_private_import_batch([row,other],revocations())

    def test_incomplete_revocation_snapshot_blocks(self):
        with self.assertRaisesRegex(ValueError,"COMPLETE_REVOCATION"):
            build_private_import_batch([review()],revocations(complete=False))


if __name__=="__main__":
    unittest.main()
