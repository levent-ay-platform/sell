import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ocr'))
from ingest import extract_document
from PIL import Image, ImageDraw

class TestQualityMetadataIntegration(unittest.TestCase):
    def test_ocr_page_contains_only_advisory_quality_flags(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'fictional.png'
            image=Image.new('RGB',(1000,1300),'white')
            draw=ImageDraw.Draw(image)
            draw.text((100,200),'PUBLIC-SYNTHETIC-ONLY 800 M2',fill='black')
            image.save(path)
            r=extract_document(str(path))
            self.assertEqual(r['pages'][0]['method'],'ocr')
            self.assertIn('qualityWarnings',r['pages'][0])
            self.assertIsInstance(r['pages'][0]['qualityWarnings'],list)
            self.assertFalse(r['mayApproveOwnership'])
