import unittest
from scripts.atlas_property_dossier import lookup
A="a"*64
KEY=["atlas","cad","lots","1","v1"]
class DossierTest(unittest.TestCase):
    def setUp(self):
        self.idx={"kind":"PRIVATE_ATLAS_PROPERTY_INDEX","public_export_permitted":False,"counts":{"document_hashes":1},
            "documents_private":[{"document_sha256":A,"registry_scope":"N","observations_private":[
              {"page":1,"origin":"CATALOGUE_INDEX","title_reference_candidates":["00123"],"parcel_reference_candidates":["02"]},
              {"page":2,"origin":"DEEP_NATIVE_LAYOUT","qualified_title_candidates_private":[{"number":"00123","suffix":"A"}],
               "qualified_parcel_candidates_private":[{"number":"03","suffix":None}]}]}],
            "candidate_graph_private":{"kind":"PRIVATE_CANDIDATE_GRAPH","public_export_permitted":False,
                "edges":[{"document_sha256":A,"source_scoped_key":KEY,"status":"CANDIDATE","legally_verified":False,
                          "page_evidence":[{"page":1,"title_reference":"00123","parcel_reference":"02"}]}]}}
        self.spatial={"kind":"PRIVATE_ATLAS_SPATIAL_CONTEXT","public_export_permitted":False,
           "rows_private":[{"source_scoped_key":KEY,"link_state":"SCOPED_DOCUMENT_CANDIDATES",
                "candidate_document_hashes_private":[A],"geometry_kind":"PLANNING_SOURCE","legal_boundary_verified":False,
                "governorate_intersections":[{"admin_id":"TN-21"}],"delegation_intersections":[]}]}
    def test_candidate_context_not_legal(self):
        r=lookup(self.idx,{"title_reference":"٠٠١٢٣","parcel_reference":"02","registry_scope":"N"},self.spatial)
        self.assertEqual(r["counts"]["documents_with_spatial_context"],1)
        self.assertFalse(r["ground_verified"])
    def test_suffix_cannot_borrow_other_link(self):
        r=lookup(self.idx,{"title_reference":"00123","title_suffix":"a","parcel_reference":"03"},self.spatial)
        self.assertEqual(r["counts"]["matched_documents"],1)
        self.assertEqual(r["counts"]["documents_with_candidate_links"],0)
    def test_cross_page_and_scope(self):
        r=lookup(self.idx,{"title_reference":"00123","title_suffix":"A","parcel_reference":"02"},self.spatial)
        self.assertEqual(r["counts"]["matched_documents"],0)
        r=lookup(self.idx,{"title_reference":"00123","registry_scope":"other"},self.spatial)
        self.assertEqual(r["counts"]["matched_documents"],1)
        self.assertEqual(r["counts"]["documents_with_candidate_links"],0)
    def test_zeroes_preserved(self):
        self.assertEqual(lookup(self.idx,{"title_reference":"123"})["counts"]["matched_documents"],0)
