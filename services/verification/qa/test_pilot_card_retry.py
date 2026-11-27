"""Synthetic, privacy-safe regression tests for the bounded CIN crop retry."""
import unittest
import numpy as np
import cv2
from mac_pilot_ocr_http import card_retry_image, prefer_card_read, NULL_FIELDS


class CardRetryTests(unittest.TestCase):
    def test_detects_card_inside_full_portrait_frame(self):
        image=np.full((1200,800,3),20,np.uint8)
        quad=np.array([[125,455],[665,385],[702,735],[158,790]],np.int32)
        cv2.fillConvexPoly(image,quad,(220,220,220))
        crop=card_retry_image(image)
        self.assertIsNotNone(crop)
        self.assertEqual(crop.shape,(1006,1600,3))
        self.assertGreater(float(crop[100:900,100:1500].mean()),190)

    def test_blank_scene_has_no_candidate(self):
        self.assertIsNone(card_retry_image(np.zeros((1200,800,3),np.uint8)))

    def test_retry_preserves_conflicting_fields(self):
        original=dict(NULL_FIELDS,surname="TEST_A",confidencePercent=80)
        alternate=dict(NULL_FIELDS,surname="TEST_B",givenName="TEST_C",confidencePercent=90)
        self.assertIs(prefer_card_read(original,alternate),original)

    def test_improved_candidate_selected_without_splicing(self):
        original=dict(NULL_FIELDS)
        alternate=dict(NULL_FIELDS,surname="TEST_A",givenName="TEST_B",confidencePercent=90)
        self.assertIs(prefer_card_read(original,alternate),alternate)

    def test_low_confidence_candidate_rejected(self):
        original=dict(NULL_FIELDS)
        alternate=dict(NULL_FIELDS,surname="TEST_A",confidencePercent=30)
        self.assertIs(prefer_card_read(original,alternate),original)

    def test_no_loss_of_original_field(self):
        original=dict(NULL_FIELDS,surname="TEST_A",confidencePercent=90)
        alternate=dict(NULL_FIELDS,givenName="TEST_B",birthDate="2000-01-01",confidencePercent=90)
        self.assertIs(prefer_card_read(original,alternate),original)


if __name__=="__main__":
    unittest.main()
