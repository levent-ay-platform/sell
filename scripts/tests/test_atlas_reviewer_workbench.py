import json
import hashlib
from pathlib import Path
import tempfile
import unittest
from scripts.atlas_reviewer_workbench import build_queue,preview_outlines,run
from scripts.atlas_cadastre_evidence_audit import write_private_json

A="a"*64
B="b"*64
def index():
    return {"kind":"PRIVATE_ATLAS_PROPERTY_INDEX","public_export_permitted":False,"official_plan_count":None,
      "counts":{"document_hashes":2,"unhashed_resource_rows":1},
      "candidate_graph_private":{"kind":"PRIVATE_CANDIDATE_GRAPH","public_export_permitted":False,"edges":[]},
      "documents_private":[{"document_sha256":A,"official_plan_verified":None,"ground_verified":False,
         "registry_scope":None,"vector_candidate_count":1,"observations_private":[
          {"page":2,"origin":"DEEP_NATIVE_LAYOUT","qualified_title_candidates_private":[{"number":"00123","suffix":"N"}],
           "qualified_parcel_candidates_private":[{"number":"02","suffix":None}],"area_candidates_private":[{"value":"300.5","unit":"m2"}],"cadastral_term_present":True}],
         "source_url":"https://private.invalid/secret","owner":"PRIVATE_PERSON"},
        {"document_sha256":B,"official_plan_verified":None,"ground_verified":False,
          "observations_private":[],"deep_review_private":{"state":"PENDING"}}]}
class WorkbenchTests(unittest.TestCase):
    def test_lossless_queue_and_private_allowlist(self):
        q=build_queue(index())
        self.assertEqual(q["counts"]["documents"],2)
        self.assertEqual(q["counts"]["with_title"],1)
        self.assertEqual(q["rows"][0]["observations"][0]["titles"],[{"number":"00123","suffix":"N"}])
        self.assertEqual(q["rows"][1]["deep"],"PENDING")
        self.assertNotIn("PRIVATE_PERSON",json.dumps(q))
        self.assertNotIn("private.invalid",json.dumps(q))
        self.assertIsNone(q["official_plan_count"])
    def test_local_page_preview_does_not_accept_wrong_hash_or_ground(self):
        ring=[[10,10],[50,10],[50,50],[10,10]]
        e={"vector_status":"COMPLETED","vector_evidence_private":{"kind":"ATLAS_PRIVATE_PDF_PAGE_OUTLINE_CANDIDATES",
           "document_sha256_private":A,"ground_verified":False,"pages_private":[
           {"page":1,"page_unit":"PAGE_POINT","display_width_points":100,"display_height_points":100,
            "outlines_private":[{"page_ring":ring,"ground_verified":False,"geometry_status":"PAGE_OUTLINE_CANDIDATE"}]}]}}
        self.assertEqual(len(preview_outlines(e,A)),1)
        self.assertEqual(preview_outlines(e,B),[])
        e["vector_evidence_private"]["ground_verified"]=True
        self.assertEqual(preview_outlines(e,A),[])
    def test_private_html_no_raw_source_or_overwrite(self):
        with tempfile.TemporaryDirectory() as t:
            base=Path(t).resolve();p=base/"index.json";write_private_json(p,index())
            counts=run(p,base/"review")
            self.assertEqual(counts["documents"],2)
            out=base/"review"/"review.html";html=out.read_text()
            self.assertEqual(out.stat().st_mode&0o777,0o600)
            self.assertIn("connect-src 'none'",html)
            self.assertNotIn("PRIVATE_PERSON",html)
            self.assertNotIn("private.invalid",html)
            self.assertIn("00123",html)
            with self.assertRaisesRegex(ValueError,"NEW_PRIVATE"):
                run(p,base/"review")
    def test_verified_input_rejected(self):
        i=index();i["documents_private"][0]["ground_verified"]=True
        with self.assertRaisesRegex(ValueError,"PROMOTED"):build_queue(i)

    def test_extended_context_display_and_conflicts(self):
        i=index()
        k=["atlas","cad","lots","1","v1"]
        i["candidate_graph_private"]["edges"]=[{"document_sha256":A,"source_scoped_key":k,
            "legally_verified":False,"status":"CANDIDATE"}]
        fp=hashlib.sha256(json.dumps(i["candidate_graph_private"],sort_keys=True,
            ensure_ascii=False,separators=(",",":"),allow_nan=False).encode("utf-8")).hexdigest()
        i["federated_graph_sha256_private"]=fp
        spatial={"kind":"PRIVATE_ATLAS_SPATIAL_CONTEXT","public_export_permitted":False,
            "federated_graph_sha256_private":fp,"rows_private":[{
            "source_scoped_key":k,"legal_boundary_verified":False,"link_state":"SCOPED_DOCUMENT_CANDIDATES",
            "candidate_document_hashes_private":[A],
            "governorate_intersections":[{"admin_id":"TN-15"}],
            "delegation_intersections":[{"admin_id":"delegation-1551"}],
            "sector_intersections":[{"admin_id":"sector-155151"},{"admin_id":"<invalid>"}],
            "district_intersections":[{"admin_id":"district-2"}],
            "sector_parent_conflicts":["sector-155151"]}]}
        q=build_queue(i,spatial)
        r=q["rows"][0]
        self.assertEqual(r["admin_context"],["TN-15","delegation-1551","district-2","sector-155151"])
        self.assertEqual(r["admin_parent_conflicts"],["sector-155151"])
        self.assertIn("REVIEW_ADMIN_PARENT_CONFLICT",r["tasks"])
        self.assertEqual(q["counts"]["with_admin_conflicts"],1)
        self.assertIsNone(r["official_plan_verified"])
        spatial["rows_private"][0]["source_scoped_key"]=["atlas","cad","lots","2","v1"]
        with self.assertRaisesRegex(ValueError,"SCOPED_EDGE"):
            build_queue(i,spatial)
