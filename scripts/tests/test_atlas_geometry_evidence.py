"""Synthetic contract tests for Atlas private georeference QA."""
from __future__ import annotations

import math
from pathlib import Path
import tempfile
import unittest

from scripts.atlas_geometry_evidence import assess_georeferencing
from scripts.atlas_cadastre_evidence_audit import write_private_json


def fixture():
    def lonlat(x,y):
        return [10 + x * 0.00001, 36 + y * 0.00001]
    return {
        "page_unit":"PIXEL",
        "target_crs":"EPSG:4326",
        "page_ring":[[0,0],[10,0],[10,10],[0,10],[0,0]],
        "control_points":[
            {"page":[0,0], "wgs84":lonlat(0,0), "role":"FIT"},
            {"page":[100,0], "wgs84":lonlat(100,0), "role":"FIT"},
            {"page":[0,100], "wgs84":lonlat(0,100), "role":"FIT"},
            {"page":[100,100], "wgs84":lonlat(100,100), "role":"CHECK"},
        ],
        "maximum_holdout_error_m":2,
    }


class AtlasGeometryQaTest(unittest.TestCase):
    def test_heldout_gcp_produces_candidate_never_verified(self):
        result=assess_georeferencing(fixture())
        self.assertEqual(result["geometry_status"],"GEOREFERENCED_CANDIDATE")
        self.assertFalse(result["ground_verified"])
        self.assertEqual(result["control_holdout_count"],1)
        self.assertLess(result["holdout_max_error_m"],0.0001)
        self.assertEqual(result["geometry4326"]["type"],"Polygon")
        self.assertGreater(result["mapped_area_m2"],0)

    def test_incorrect_independent_check_disqualifies(self):
        input=fixture()
        input["control_points"][3]["wgs84"]=[10.01,36.01]
        result=assess_georeferencing(input)
        self.assertEqual(result["geometry_status"],"GEOREFERENCE_REVIEW_REQUIRED")
        self.assertFalse(result["ground_verified"])

    def test_only_three_control_points_disallowed(self):
        input=fixture()
        input["control_points"]=input["control_points"][:3]
        with self.assertRaisesRegex(ValueError,"FOUR_OR_MORE_GCPS"):
            assess_georeferencing(input)

    def test_collinear_fit_disallowed(self):
        input=fixture()
        input["control_points"][2]["page"]=[200,0]
        with self.assertRaisesRegex(ValueError,"COLLINEAR"):
            assess_georeferencing(input)

    def test_missing_holdout_disallowed(self):
        input=fixture()
        input["control_points"][3]["role"]="FIT"
        with self.assertRaisesRegex(ValueError,"INDEPENDENT"):
            assess_georeferencing(input)

    def test_reused_fit_point_not_independent_holdout(self):
        input=fixture()
        input["control_points"][3]={**input["control_points"][0],"role":"CHECK"}
        with self.assertRaisesRegex(ValueError,"REUSED_CONTROL"):
            assess_georeferencing(input)

    def test_self_intersection_rejected(self):
        input=fixture()
        input["page_ring"]=[[0,0],[10,10],[10,0],[0,10],[0,0]]
        with self.assertRaises(ValueError):
            assess_georeferencing(input)

    def test_coordinates_from_outside_tunisia_rejected(self):
        input=fixture()
        input["control_points"][0]["wgs84"]=[0,0]
        with self.assertRaisesRegex(ValueError,"OUTSIDE_TUNISIA"):
            assess_georeferencing(input)

    def test_requires_explicit_crs(self):
        input=fixture()
        input["target_crs"]="EPSG:3857"
        with self.assertRaisesRegex(ValueError,"EXPLICIT_WGS84"):
            assess_georeferencing(input)

    def test_printed_area_disagreement_keeps_review(self):
        input=fixture()
        input["printed_area_m2"]=20000
        result=assess_georeferencing(input)
        self.assertEqual(result["geometry_status"],"GEOREFERENCE_REVIEW_REQUIRED")
        self.assertGreater(result["printed_area_difference_ratio"],0.8)

    def test_nan_or_unbounded_tolerance_rejected(self):
        input=fixture()
        input["maximum_holdout_error_m"]=math.inf
        with self.assertRaisesRegex(ValueError,"BOUNDED_TOLERANCE"):
            assess_georeferencing(input)

    def test_only_private_output_and_never_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            result=assess_georeferencing(fixture())
            target=Path(tmp)/"private"/"qa.json"
            write_private_json(target,result)
            self.assertEqual(target.stat().st_mode & 0o777,0o600)
            with self.assertRaises(FileExistsError):
                write_private_json(target,result)


if __name__=="__main__":
    unittest.main()
