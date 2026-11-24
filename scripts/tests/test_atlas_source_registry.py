import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from scripts.atlas_source_registry import digest, inspect_schema, export_registry
from scripts.atlas_document_linkage import build_candidate_graph

class RegistryTest(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory(); self.addCleanup(tmp.cleanup)
        self.db=Path(tmp.name).resolve()/"copy.sqlite"
        with sqlite3.connect(self.db) as c:
            c.execute("CREATE TABLE gis_records (oid INTEGER, attrs TEXT)")
            for oid,tf,lot,scope in [(1,"٠٠١٢٣","002","N"),(2,123,"2","N"),
                                     (3,"00123","002","unknown"),(4,"00123","002","N"),
                                     (4,"00123","002","N")]:
                c.execute("INSERT INTO gis_records VALUES (?,?)",(oid,json.dumps({"attributes":{"tf":tf,"lot":lot,"zone":scope,"secret":"PRIVATE_OWNER"}})))
        self.sha=digest(self.db)
        def nested(key): return {"column":"attrs","json_path":["attributes",key]}
        self.mapping={"kind":"PRIVATE_REVIEWED_SOURCE_MAPPING","snapshot_kind":"mainfile_diagnostic",
                      "snapshot_sha256_private":self.sha,"mappings":[{"table":"gis_records",
                      "fields":{"namespace":{"literal":"ATLAS"},"service_id":{"literal":"CAD"},
                      "layer_id":{"literal":"LOTS"},"object_id":{"column":"oid"},
                      "version":{"literal":"snapshot-v1"},"registry_scope":nested("zone"),
                      "title_reference":nested("tf"),"parcel_reference":nested("lot")},
                      "reviewed_registry_scope_values":{"N":"REVIEWED-NABEUL"}}]}
    def test_schema_no_values_and_readonly(self):
        r=inspect_schema(self.db,self.sha,"mainfile_diagnostic")
        self.assertEqual(r["tables_private"][0]["row_count"],5)
        self.assertIn("attributes.tf",r["tables_private"][0]["sampled_json_key_hints_private"]["attrs"])
        self.assertNotIn("PRIVATE_OWNER",json.dumps(r))
        self.assertEqual(digest(self.db),self.sha)
    def test_lossless_conflict_and_numeric_reference(self):
        r=export_registry(self.db,self.mapping)
        self.assertEqual(r["counts"]["mapped_rows"],5)
        self.assertEqual(r["counts"]["candidate_source_rows"],1)
        self.assertEqual(r["counts"]["unresolved_rows"],4)
        self.assertEqual(r["counts"]["unresolved_reasons"]["DUPLICATE_SCOPED_IDENTITY"],2)
        self.assertEqual(r["candidate_source_records_private"][0]["title_reference"],"00123")
        self.assertNotIn("PRIVATE_OWNER",json.dumps(r))
        self.assertEqual(digest(self.db),self.sha)
    def test_registry_to_graph(self):
        r=export_registry(self.db,self.mapping)
        doc={"document_sha256":"a"*64,"page_reference_evidence_private":[
             {"page":1,"title_reference_candidates":["00123"],"parcel_reference_candidates":["002"]}]}
        audit={"schema_version":3,"counts":{"distinct_pdf_sha256":1},"review_queue":[doc],"unresolved_pdf_documents":[]}
        graph=build_candidate_graph(audit,{"a"*64:"REVIEWED-NABEUL"},r["candidate_source_records_private"])
        self.assertEqual(graph["counts"]["candidate_edges"],1)
        self.assertFalse(graph["edges"][0]["legally_verified"])
    def test_no_scope_inference(self):
        self.mapping["mappings"][0]["reviewed_registry_scope_values"]={}
        self.assertEqual(export_registry(self.db,self.mapping)["counts"]["candidate_source_rows"],0)
    def test_changed_digest_refused(self):
        self.mapping["snapshot_sha256_private"]="0"*64
        with self.assertRaisesRegex(ValueError,"SNAPSHOT_SHA_MISMATCH"):
            export_registry(self.db,self.mapping)
    def test_cannot_constant_title(self):
        self.mapping["mappings"][0]["fields"]["title_reference"]={"literal":"00123"}
        with self.assertRaisesRegex(ValueError,"ROW_SPECIFIC"):
            export_registry(self.db,self.mapping)
