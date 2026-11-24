import copy
import unittest
from scripts.atlas_federated_review import federate
from scripts.atlas_spatial_context import AdminLayer
from scripts.atlas_property_dossier import lookup

SHA="a"*64
K=["atlas","cad","lots","1","v1"]
def rect(x,y,w,h):return {"type":"Polygon","coordinates":[[[x,y],[x+w,y],[x+w,y+h],[x,y+h],[x,y]]]}
def pair(oid="1",snap="b"*64):
    key=K.copy();key[3]=oid
    source=dict(zip(("namespace","service_id","layer_id","object_id","version"),key))
    source.update(registry_scope="N",title_reference="00123",parcel_reference="02")
    r={"kind":"PRIVATE_ATLAS_SOURCE_REGISTRY","snapshot_kind":"mainfile_diagnostic",
       "snapshot_sha256_private":snap,"public_export_permitted":False,
       "counts":{"mapped_rows":1},"rows_private":[{"source_record_private":source,"reasons":[]}],
       "candidate_source_records_private":[source]}
    g={"kind":"PRIVATE_ATLAS_SOURCE_GEOMETRIES","crs":"EPSG:4326","snapshot_sha256_private":snap,
       "public_export_permitted":False,"records_private":[{"source_scoped_key":key,
           "geometry_kind":"CADASTRE_SOURCE","geometry":rect(10.1,35.1,.1,.1)}]}
    return r,g
class FederationTests(unittest.TestCase):
    def setUp(self):
        self.index={"kind":"PRIVATE_ATLAS_PROPERTY_INDEX","public_export_permitted":False,"official_plan_count":None,
            "counts":{"document_hashes":1},"documents_private":[{"document_sha256":SHA,"ground_verified":False,
              "official_plan_verified":None,"observations_private":[{"page":1,"origin":"CATALOGUE_INDEX",
                "title_reference_candidates":["00123"],"parcel_reference_candidates":["02"]}]}]}
        self.gov=AdminLayer([{"properties":{"id":"G"},"geometry":rect(10,35,1,1)}],"governorate")
        self.deleg=AdminLayer([{"properties":{"id":"D","governorate":"G"},"geometry":rect(10,35,1,1)}],"delegation")
    def test_two_sources_and_bound_dossier(self):
        idx,reg,geo,report=federate(self.index,{SHA:"N"},[pair(),pair("2","c"*64)],self.gov,self.deleg)
        self.assertEqual(report["counts"]["mapped_rows"],2)
        self.assertEqual(report["counts"]["candidate_edges"],2)
        self.assertEqual(geo["counts"]["rows_with_candidate_documents"],2)
        d=lookup(idx,{"title_reference":"00123","parcel_reference":"02","registry_scope":"N"},geo)
        self.assertEqual(d["counts"]["documents_with_spatial_context"],1)
        self.assertFalse(d["ground_verified"])
        geo["federated_graph_sha256_private"]="f"*64
        with self.assertRaisesRegex(ValueError,"GRAPH_BINDING"):lookup(idx,{"title_reference":"00123"},geo)
    def test_cross_snapshot_duplicate_quarantined(self):
        idx,reg,geo,report=federate(self.index,{SHA:"N"},[pair(),pair("1","c"*64)],self.gov,self.deleg)
        self.assertEqual(report["counts"]["mapped_rows"],2)
        self.assertEqual(report["counts"]["colliding_keys"],1)
        self.assertEqual(report["counts"]["candidate_edges"],0)
        self.assertEqual(len(reg["rows_private"]),2)
        self.assertEqual(geo["counts"]["link_states"],{"FEDERATED_KEY_COLLISION":2})
        self.assertIsNone(report["official_plan_count"])
    def test_missing_scope_retains_sources_and_geometry(self):
        idx,reg,geo,report=federate(self.index,{},[pair()],self.gov,self.deleg)
        self.assertEqual(report["counts"]["candidate_edges"],0)
        self.assertEqual(geo["counts"]["states"],{"SPATIAL_CONTEXT_CANDIDATE":1})
