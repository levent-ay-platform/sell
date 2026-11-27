"""Synthetic-only numeric preprocessing regression; no personal documents."""
import unittest
from types import SimpleNamespace

import cv2
import numpy as np

from cin_v5 import numeric_pair


class FakeSession:
    def __init__(self):
        self.samples=[]
    def get_inputs(self):
        return [SimpleNamespace(name="input")]
    def run(self, _unused, bindings):
        pixels=bindings["input"].copy()
        self.samples.append(pixels)
        # A blank CTC distribution (no OCR assertions in this geometry test).
        blank=np.zeros((1,4,11),np.float32)
        blank[:,:,0]=1.0
        return [blank]


class NumericPreprocessTests(unittest.TestCase):
    def test_second_vote_preserves_left_glyphs_and_changes_padding(self):
        session=FakeSession()
        image=np.full((54,90,3),240,np.uint8)
        cv2.putText(image,"42",(12,39),cv2.FONT_HERSHEY_SIMPLEX,1.1,
                    (15,15,15),2,cv2.LINE_AA)
        result=numeric_pair(session,list("0123456789"),image)
        self.assertEqual(len(result),2)
        self.assertEqual(len(session.samples),2)
        a,b=session.samples
        self.assertEqual(a.shape,(1,3,48,320))
        self.assertEqual(b.shape,(1,3,48,320))
        # At the far right, the model sees different backgrounds.
        self.assertTrue(np.all(a[:,:,:,315:]==-1))
        self.assertTrue(np.all(b[:,:,:,315:]==1))
        # The glyphs remain left-aligned and still have ink pixels.
        self.assertTrue(np.any(a[:,:,:,:100]<-.75))
        self.assertTrue(np.any(b[:,:,:,:100]<-.75))
        self.assertTrue(np.any(a[:,:,:,:100]>.50))
        self.assertTrue(np.any(b[:,:,:,:100]>.50))
    def test_invalid_empty_crop_fails_closed_without_inference(self):
        session=FakeSession()
        result=numeric_pair(session,list("0123456789"),
                            np.zeros((0,0,3),np.uint8))
        self.assertEqual(result,[("",0.),("",0.)])
        self.assertEqual(len(session.samples),0)


if __name__=="__main__":
    unittest.main()
