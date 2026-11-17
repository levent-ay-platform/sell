import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ocr'))
from quality import inspect_photo_quality, UnsafeQualityInput
from PIL import Image, ImageDraw, ImageFilter

class TestPhotoQuality(unittest.TestCase):
    def test_well_resolved_text_photo_is_advisory_only(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'fictional.png'
            im=Image.new('L', (1000,1500), 'white')
            draw=ImageDraw.Draw(im)
            for i in range(30):
                draw.text((110,150+i*36), 'FAKE TITLE 0000    123  M2', fill='black')
            im.save(path)
            x=inspect_photo_quality(path)
            self.assertEqual(x['pixelWidth'],1000)
            self.assertEqual(x['diagnostic'],'ADVISORY_ONLY')
            self.assertEqual(x['ownershipVerified'],False)
            self.assertEqual(x['pageEdgesConfirmed'],False)
            self.assertNotIn('LOW_RESOLUTION', x['flags'])

    def test_blank_image_requests_quality_review(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'blank.png'; Image.new('L',(1000,1400),245).save(path)
            x=inspect_photo_quality(path)
            self.assertIn('LOW_CONTRAST',x['flags'])
            self.assertIn('POSSIBLE_BLUR',x['flags'])

    def test_small_camera_image_warns_without_claiming_forgery(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'small.png';Image.new('RGB',(600,800),'white').save(path)
            x=inspect_photo_quality(path)
            self.assertIn('LOW_RESOLUTION',x['flags'])
            self.assertEqual(x['ownershipVerified'],False)

    def test_bad_image_and_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)
            invalid=p/'bad.png';invalid.write_bytes(b'not an image')
            with self.assertRaisesRegex(UnsafeQualityInput,'UNREADABLE_IMAGE'):
                inspect_photo_quality(invalid)
            actual=p/'valid.png';Image.new('RGB',(800,1000),'white').save(actual)
            alias=p/'alias.png';alias.symlink_to(actual)
            with self.assertRaisesRegex(UnsafeQualityInput,'INVALID_IMAGE_SOURCE'):
                inspect_photo_quality(alias)

    def test_synthetic_exif_orientation_warns(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'turned.jpg'; img=Image.new('RGB',(850,1200),'white')
            exif=Image.Exif();exif[274]=6;img.save(path,exif=exif)
            x=inspect_photo_quality(path)
            self.assertIn('REVIEW_ORIENTATION',x['flags'])
