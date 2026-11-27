import unittest
from scripts.atlas_geometry_triage import shape_metrics,rank,choose
class GeometryTriageTests(unittest.TestCase):
    def test_border_rejected_and_nonrectangular_ranked(self):
        page={"display_width_points":100,"display_height_points":100,"outlines_private":[
            {"ground_verified":False,"geometry_status":"PAGE_OUTLINE_CANDIDATE","possible_frame_or_border":True,"page_area_points_squared":9900,"page_ring":[[0,0],[100,0],[100,99],[0,99],[0,0]]},
            {"ground_verified":False,"geometry_status":"PAGE_OUTLINE_CANDIDATE","possible_frame_or_border":False,"page_area_points_squared":100,"page_ring":[[1,1],[20,1],[20,5],[5,10],[1,10],[1,1]]}]}
        m=shape_metrics(page)
        self.assertEqual(m["outline_count"],1)
        self.assertEqual(m["nonrectangular_count"],1)
    def test_otc_cover_without_vector_and_previous_cover(self):
        meta={"a"*64:{"origins":["OTC"]},"b"*64:{"origins":["OTC"]},"c"*64:{"origins":["AFH"]}}
        rows=rank({},meta,{("a"*64,1)})
        selected=choose(rows,1,1)
        self.assertEqual([r["sha"] for r in selected],["b"*64])
