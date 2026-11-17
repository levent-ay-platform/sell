"""Synthetic portrait camera scenes only, never genuine documents or PII."""
import unittest
import cv2
import numpy as np

from cin_v5 import normalize_card


class PortraitNormalizeTest(unittest.TestCase):
    def scene(self,top):
        # Very wide portrait capture with a landscape front-card-like
        # rectangle intentionally OUTSIDE the old hard-coded top 49%.
        canvas=np.full((1600,900,3),42,np.uint8)
        x0,y0=110,top
        x1,y1=800,top+430
        canvas[y0:y1,x0:x1]=(220,225,228)
        cv2.rectangle(canvas,(x0,y0),(x1,y1),(0,0,0),6)
        # Synthetic coloured markers make top/bottom/left/right position
        # and image mirroring distinguishable. Not an ID or recognizable PII.
        cv2.rectangle(canvas,(x0+42,y0+42),(x0+95,y0+95),(0,0,245),-1)
        cv2.rectangle(canvas,(x1-95,y1-95),(x1-42,y1-42),(242,0,0),-1)
        return canvas
    def test_lower_portrait_card_is_not_discarded(self):
        frame=self.scene(920)
        card=normalize_card(frame)
        self.assertIsNotNone(card)
        self.assertEqual(card.shape,(760,1200,3))
        # The upper-left red marker and lower-right blue marker survive.
        self.assertTrue(np.any((card[:,:,2]>170)&(card[:,:,0]<90)))
        self.assertTrue(np.any((card[:,:,0]>170)&(card[:,:,2]<90)))
    def test_upper_portrait_card_is_also_detected(self):
        frame=self.scene(145)
        card=normalize_card(frame)
        self.assertIsNotNone(card)
        self.assertEqual(card.shape,(760,1200,3))
    def test_blank_portrait_not_invented_into_landscape_card(self):
        blank=np.full((1600,900,3),128,np.uint8)
        self.assertIsNone(normalize_card(blank))
    def test_preserves_original_sensor_pixels_for_repeatable_qc(self):
        frame=self.scene(920)
        baseline=frame.copy()
        _=normalize_card(frame)
        self.assertTrue(np.array_equal(frame,baseline))


if __name__=="__main__":
    unittest.main()
