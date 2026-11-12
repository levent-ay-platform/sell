"""Synthetic geometry only: NO real people, scans, CIN numbers or DOB."""
import unittest

import cv2
import numpy as np
from cin_v5_region import find_digit_crop,crop_printed_number


class NumericFieldRegionTest(unittest.TestCase):
    def create(self,cin="98765432",year="2000",day="13",shift=0):
        card=np.full((760,1200,3),244,dtype=np.uint8)
        if cin:
            cv2.putText(card,cin,(455,318+shift),cv2.FONT_HERSHEY_SIMPLEX,
                        1.6,(12,12,12),3,cv2.LINE_AA)
        if year:
            cv2.putText(card,year,(445,625),cv2.FONT_HERSHEY_SIMPLEX,
                        1.45,(18,18,18),3,cv2.LINE_AA)
        if day:
            cv2.putText(card,day,(914,625),cv2.FONT_HERSHEY_SIMPLEX,
                        1.45,(18,18,18),3,cv2.LINE_AA)
        return card

    def test_each_complete_field_has_tight_candidate(self):
        card=self.create()
        for box,count in [((.29,.22,.80,.48),8),
                          ((.30,.69,.60,.89),4),
                          ((.69,.69,.90,.89),2)]:
            with self.subTest(count=count):
                rect=find_digit_crop(card,box,count)
                self.assertIsNotNone(rect)
                x0,y0,x1,y1=rect
                self.assertGreater(x1-x0,20)
                self.assertGreater(y1-y0,20)
                self.assertLess(x1-x0,1200*.45)

    def test_moderate_vertical_shift(self):
        for shift in (-13,0,15):
            with self.subTest(shift=shift):
                self.assertIsNotNone(find_digit_crop(
                    self.create(shift=shift),(.29,.22,.80,.48),8))

    def test_missing_digit_must_not_generate_complete_cin(self):
        card=self.create(cin="9876543")
        # Other synthetic fields are outside this allowed CIN region.
        self.assertIsNone(find_digit_crop(card,(.29,.22,.80,.48),8))

    def test_empty_and_noisy_fields_are_not_accepted(self):
        self.assertIsNone(find_digit_crop(
            np.full((760,1200,3),255,np.uint8),(.29,.22,.80,.48),8))
        self.assertIsNone(find_digit_crop(
            self.create(cin=None),(.29,.22,.80,.48),8))
        with self.assertRaises(ValueError):
            find_digit_crop(self.create(),(.29,.22,.80,.48),5)

    def test_returns_roi_view_not_full_frame(self):
        card=self.create()
        crop=crop_printed_number(card,(.29,.22,.80,.48),8)
        self.assertIsNotNone(crop)
        self.assertLess(crop.shape[0],100)
        self.assertLess(crop.shape[1],400)


if __name__=="__main__":unittest.main()
