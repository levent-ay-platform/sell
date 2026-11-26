import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from scripts.atlas_spatial_context import AdminLayer,build_context,load_reference,load_extended_reference

def rect(x,y,w=.1,h=.1):
    return {"type":"Polygon","coordinates":[[[x,y],[x+w,y],[x+w,y+h],[x,y+h],[x,y]]]}
def feat(code,geo,parent=None):
    return {"properties":{"id":code,"governorate":parent},"geometry":geo}
K=["atlas","cad","lots","1","v1"]
SHA="a"*64
class SpatialTests(unittest.TestCase):
    def setUp(self):
        self.gov=AdminLayer([feat("G",rect(10,35,1,1))],"governorate")
        self.deleg=AdminLayer([feat("D1",rect(10,35,.5,1),"G"),
                               feat("D2",rect(10.5,35,.5,1),"G")],"delegation")
        self.registry={"kind":"PRIVATE_ATLAS_SOURCE_REGISTRY","public_export_permitted":False,
                       "snapshot_sha256_private":SHA,"candidate_source_records_private":[dict(zip(
                           ("namespace","service_id","layer_id","object_id","version"),K))]}
        self.graph={"kind":"PRIVATE_CANDIDATE_GRAPH","public_export_permitted":False,
                    "edges":[{"source_scoped_key":K,"document_sha256":"b"*64,
                              "legally_verified":False,"status":"CANDIDATE"}]}
        self.source={"kind":"PRIVATE_ATLAS_SOURCE_GEOMETRIES","crs":"EPSG:4326",
                     "public_export_permitted":False,"snapshot_sha256_private":SHA,
                     "records_private":[{"source_scoped_key":K,"geometry_kind":"PLANNING_SOURCE","geometry":rect(10.4,35.2,.2,.2)}]}
    def run_context(self):
        return build_context(self.source,self.registry,self.graph,self.gov,self.deleg)
    def test_intersects_two_delegations_keeps_candidate_only(self):
        r=self.run_context()
        row=r["rows_private"][0]
        self.assertEqual(len(row["delegation_intersections"]),2)
        self.assertAlmostEqual(sum(x["source_area_fraction"] for x in row["delegation_intersections"]),1,places=6)
        self.assertEqual(row["candidate_document_hashes_private"],["b"*64])
        self.assertFalse(row["legal_boundary_verified"])
        self.assertIsNone(r["official_plan_count"])
    def test_boundary_touch_not_area(self):
        self.source["records_private"][0]["geometry"]=rect(10.25,35.2,.25,.2)
        row=self.run_context()["rows_private"][0]
        self.assertEqual([x["admin_id"] for x in row["delegation_intersections"]],["D1"])
        self.assertEqual(row["delegation_boundary_contacts"],["D2"])
    def test_duplicate_keys_never_link_documents(self):
        self.source["records_private"]*=2
        r=self.run_context()
        self.assertEqual(r["counts"]["source_geometry_rows"],2)
        self.assertEqual(r["counts"]["link_states"],{"DUPLICATE_GEOMETRY_KEY":2})
        self.assertEqual(r["counts"]["rows_with_candidate_documents"],0)
    def test_invalid_and_unmatched_preserved(self):
        self.source["records_private"] += [{"geometry":rect(50,50)},{"geometry":rect(9,34)}]
        r=self.run_context()
        self.assertEqual(r["counts"]["source_geometry_rows"],3)
        self.assertEqual(r["rows_private"][1]["status"],"GEOMETRY_REVIEW_REQUIRED")
        self.assertEqual(r["rows_private"][2]["status"],"NO_POSITIVE_AREA_INTERSECTION_OBSERVED")
    def test_wrong_snapshot_and_crs(self):
        self.source["snapshot_sha256_private"]="b"*64
        with self.assertRaisesRegex(ValueError,"SNAPSHOT_BINDING"):self.run_context()
        self.source["crs"]="EPSG:3857"
        with self.assertRaisesRegex(ValueError,"WGS84"):self.run_context()
    def test_actual_pinned_admin_reference(self):
        gov,deleg=load_reference()
        self.assertEqual(len(gov.codes),24)
        self.assertEqual(len(deleg.codes),279)
        self.assertEqual(len(gov.rows)+len(gov.invalid),24)
        self.assertEqual(len(deleg.rows)+len(deleg.invalid),279)

    def test_extended_context_preserves_multiple_intersections(self):
        sectors=AdminLayer([
            {"properties":{"id":"S1","delegation":"D1"},"geometry":rect(10,35,.5,1)},
            {"properties":{"id":"S2","delegation":"D2"},"geometry":rect(10.5,35,.5,1)}
        ],"sector")
        district=AdminLayer([feat("R",rect(10,35,1,1))],"district")
        result=build_context(self.source,self.registry,self.graph,self.gov,self.deleg,sectors,district)
        row=result["rows_private"][0]
        self.assertEqual([x["admin_id"] for x in row["sector_intersections"]],["S1","S2"])
        self.assertEqual(row["sector_parent_conflicts"],[])
        self.assertEqual(row["district_intersections"][0]["admin_id"],"R")
        self.assertEqual(row["candidate_document_hashes_private"],["b"*64])
        self.assertFalse(row["legal_boundary_verified"])
        self.assertIsNone(result["official_plan_count"])
        self.assertEqual(result["reference_quality"]["extended_layers"]["sector"]["units"],2)

    def test_sector_parent_conflict_is_not_reassigned(self):
        sectors=AdminLayer([{"properties":{"id":"S","delegation":"OTHER"},
                             "geometry":rect(10,35,1,1)}],"sector")
        result=build_context(self.source,self.registry,self.graph,self.gov,self.deleg,sectors)
        self.assertEqual(result["rows_private"][0]["sector_parent_conflicts"],["S"])
        self.assertFalse(result["public_export_permitted"])

    def test_bad_extended_reference_fails_closed(self):
        with TemporaryDirectory() as folder:
            p=Path(folder)/"sectors.geojson"
            p.write_text("{}")
            with self.assertRaisesRegex(ValueError,"EXTENDED_REFERENCE_SIZE_MISMATCH"):
                load_extended_reference(p,"sector",self.gov,self.deleg)

    def test_legacy_context_does_not_invent_sector(self):
        row=self.run_context()["rows_private"][0]
        self.assertNotIn("sector_intersections",row)
