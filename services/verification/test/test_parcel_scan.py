"""Geometry acceptance tests use raster drawings with known origination, not titles."""
import sys
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ocr'))
from parcel_scan import extract_parcel_scan, scan_metadata_for_page


class ParcelScanTest(unittest.TestCase):
    def canvas(self):
        image = Image.new('L', (1000, 850), 245)
        return image, ImageDraw.Draw(image)

    def test_closed_concave_ink_retains_plan_space_vertices(self):
        image, draw = self.canvas()
        expected = [(180, 200), (680, 200), (680, 400), (460, 400), (460, 650), (180, 650)]
        draw.line([*expected, expected[0]], fill=35, width=5)
        draw.rectangle((16, 16, 980, 832), outline=70, width=3)
        draw.text((275, 240), 'LOT 043', fill=35)
        result = extract_parcel_scan(image, 2)
        self.assertEqual(result['status'], 'CANDIDATE')
        self.assertEqual((result['width'], result['height']), (1000, 850))
        self.assertEqual(result['outline']['unit'], 'PIXEL')
        self.assertEqual(result['outline']['page'], 2)
        ring = result['outline']['ring']
        self.assertEqual(ring[0], ring[-1])
        self.assertEqual(len(ring), 7)
        for x, y in expected:
            self.assertTrue(any(abs(x-i) <= 9 and abs(y-j) <= 9 for i, j in ring), (x, y, ring))
        self.assertNotIn('latitude', result)

    def test_comparable_separate_outlines_require_selection(self):
        image, draw = self.canvas()
        draw.rectangle((125, 245, 425, 605), outline=20, width=6)
        draw.rectangle((568, 185, 875, 530), outline=20, width=6)
        result = extract_parcel_scan(image, 1)
        self.assertEqual(result['status'], 'AMBIGUOUS')
        self.assertIsNone(result['outline'])

    def test_open_shape_and_border_are_not_parcels(self):
        image, draw = self.canvas()
        draw.rectangle((16, 16, 980, 832), outline=35, width=5)
        draw.line([(210, 200), (710, 230), (640, 610), (240, 610)], fill=35, width=5)
        result = extract_parcel_scan(image, 1)
        self.assertEqual(result['status'], 'NO_OUTLINE')
        self.assertIsNone(result['outline'])

    def test_normal_certificate_with_a_box_is_not_extracted_automatically(self):
        image, draw = self.canvas()
        draw.rectangle((200, 245, 700, 605), outline=20, width=5)
        self.assertEqual(scan_metadata_for_page(image, 1, [{'text':'Certificat de propriété'}]), {})
        metadata = scan_metadata_for_page(image, 1, [{'text':'Plan de lotissement'}])
        self.assertEqual(metadata['parcelPlanScan']['status'], 'CANDIDATE')

    def test_selected_crop_restores_full_page_frame(self):
        from parcel_scan import extract_parcel_scan_crop
        image, draw = self.canvas()
        draw.rectangle((430, 280, 500, 400), outline=15, width=4)
        self.assertEqual(extract_parcel_scan(image, 1)['status'], 'NO_OUTLINE')
        result = extract_parcel_scan_crop(image, 1, (370, 230, 560, 480))
        self.assertEqual(result['status'], 'CANDIDATE')
        self.assertEqual((result['width'],result['height']), (1000,850))
        xs,ys=zip(*result['outline']['ring'])
        self.assertGreater(min(xs), 419)
        self.assertGreater(min(ys), 269)
        self.assertLess(max(xs), 511)
        self.assertLess(max(ys), 411)
        with self.assertRaises(ValueError):
            extract_parcel_scan_crop(image, 1, (-1, 5, 650, 720))
