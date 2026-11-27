"""Synthetic PDF drawing operators, never real property or private records."""
from __future__ import annotations

import hashlib
from pathlib import Path
import tempfile
import unittest

from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, NameObject, RectangleObject

from scripts.atlas_pdf_vector_candidates import extract_pdf
from scripts.atlas_geometry_evidence import assess_georeferencing


class AtlasPdfVectorCandidateTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.pdf = Path(tmp.name) / "fixture.pdf"

    def fixture(self, streams, *, rotation=0, crop=None):
        writer = PdfWriter()
        for stream in streams:
            page = writer.add_blank_page(width=200, height=150)
            if rotation:
                page.rotate(rotation)
            if crop:
                page.cropbox = RectangleObject(crop)
            content = DecodedStreamObject()
            content.set_data(stream)
            page[NameObject("/Contents")] = writer._add_object(content)
        with self.pdf.open("wb") as output:
            writer.write(output)
        return hashlib.sha256(self.pdf.read_bytes()).hexdigest()

    def test_pdf_sha_and_page_coordinates_are_bound(self):
        sha = self.fixture([b"20 30 m 80 30 l 80 70 l 20 70 l h S"])
        original = self.pdf.read_bytes()
        report = extract_pdf(self.pdf, sha)
        page = report["pages_private"][0]
        ring = page["outlines_private"][0]
        self.assertEqual(report["document_sha256_private"], sha)
        self.assertEqual(report["candidate_outline_count"], 1)
        self.assertEqual(ring["page_ring"], [[20,120],[80,120],[80,80],[20,80],[20,120]])
        self.assertEqual(ring["page_area_points_squared"], 2400)
        self.assertEqual(ring["geometry_status"], "PAGE_OUTLINE_CANDIDATE")
        self.assertFalse(ring["ground_verified"])
        self.assertIsNone(report["verified_official_cadastral_plan"])
        self.assertEqual(self.pdf.read_bytes(), original)
        with self.assertRaisesRegex(ValueError, "PDF_SHA256_MISMATCH"):
            extract_pdf(self.pdf, "0"*64)

    def test_rectangles_and_graphics_state_transform(self):
        sha = self.fixture([b"q 2 0 0 2 10 20 cm 10 10 20 15 re S Q 20 30 20 20 re S"])
        outlines = extract_pdf(self.pdf, sha)["pages_private"][0]["outlines_private"]
        self.assertEqual(len(outlines), 2)
        self.assertEqual(outlines[0]["bbox_points"], [30,80,70,110])
        self.assertEqual(outlines[1]["bbox_points"], [20,100,40,120])

    def test_nested_matrix_order(self):
        sha = self.fixture([b"q 2 0 0 2 10 20 cm 1 0 0 1 5 6 cm 10 10 20 15 re S Q"])
        ring = extract_pdf(self.pdf, sha)["pages_private"][0]["outlines_private"][0]
        self.assertEqual(ring["bbox_points"], [40,68,80,98])

    def test_rotated_cropbox_coordinates(self):
        sha = self.fixture([b"20 30 60 40 re S"], rotation=90, crop=[10,20,190,140])
        page = extract_pdf(self.pdf, sha)["pages_private"][0]
        self.assertEqual(page["display_width_points"], 120)
        self.assertEqual(page["display_height_points"], 180)
        self.assertEqual(page["outlines_private"][0]["bbox_points"], [10,10,50,70])

    def test_does_not_promote_open_curved_or_unpainted_paths(self):
        sha = self.fixture([b"10 10 m 30 10 l 30 30 l S 20 20 m 22 23 24 25 30 30 c h S 40 40 20 20 re n"])
        page = extract_pdf(self.pdf, sha)["pages_private"][0]
        self.assertEqual(page["outlines_private"], [])
        self.assertEqual(page["rejected_path_counts"]["OPEN_PATH_REVIEW"], 1)
        self.assertEqual(page["rejected_path_counts"]["CURVED_PATH_REVIEW"], 1)
        self.assertEqual(page["rejected_path_counts"]["UNPAINTED_PATHS"], 1)
        self.assertEqual(page["page_status"], "RASTER_OR_MANUAL_REVIEW_REQUIRED")

    def test_preserve_multiple_rings_and_flag_frame(self):
        sha = self.fixture([b"0 0 200 150 re S 20 30 60 40 re 110 30 60 40 re S"])
        report = extract_pdf(self.pdf, sha)
        rings = report["pages_private"][0]["outlines_private"]
        self.assertEqual(len(rings), 3)
        self.assertTrue(rings[0]["possible_frame_or_border"])
        self.assertFalse(rings[1]["possible_frame_or_border"])
        self.assertTrue(all(r["requires_visual_review"] for r in rings))

    def test_clipping_and_nested_content_are_reported_not_claimed(self):
        sha = self.fixture([b"0 0 120 100 re W n /X1 Do 20 30 60 40 re S"])
        page = extract_pdf(self.pdf, sha)["pages_private"][0]
        self.assertEqual(page["limitations_detected"]["CLIPPING_PRESENT"], 1)
        self.assertEqual(page["limitations_detected"]["NESTED_OR_RASTER_CONTENT_NOT_INSPECTED"], 1)
        self.assertEqual(len(page["outlines_private"]), 1)

    def test_page_selection_and_no_hidden_complete_claim(self):
        sha = self.fixture([b"", b"20 30 60 40 re S"])
        result = extract_pdf(self.pdf, sha, pages=[2])
        self.assertEqual(result["document_page_count"], 2)
        self.assertEqual(result["inspected_pages"], [2])
        self.assertEqual(result["uninspected_page_count"], 1)
        self.assertEqual(result["pages_private"][0]["page"], 2)
        with self.assertRaisesRegex(ValueError, "INVALID_SELECTED_PAGES"):
            extract_pdf(self.pdf, sha, pages=[3])

    def test_handoff_to_existing_georeferencing_is_candidate_only(self):
        sha = self.fixture([b"20 30 60 40 re S"])
        shape = extract_pdf(self.pdf, sha)["pages_private"][0]["outlines_private"][0]
        def ground(p):
            return [11.0 + p[0]*0.00001, 36.8 - p[1]*0.00001]
        controls = [{"page": p, "wgs84": ground(p), "role": role}
                    for p, role in [([0,0],"FIT"),([100,0],"FIT"),
                                    ([0,100],"FIT"),([100,100],"CHECK")]]
        qa = assess_georeferencing({
            "page_unit": shape["page_unit"],
            "page_ring": shape["page_ring"],
            "target_crs": "EPSG:4326",
            "control_points": controls,
            "maximum_holdout_error_m": 1,
        })
        self.assertEqual(qa["geometry_status"], "GEOREFERENCED_CANDIDATE")
        self.assertFalse(qa["ground_verified"])


if __name__ == "__main__":
    unittest.main()
