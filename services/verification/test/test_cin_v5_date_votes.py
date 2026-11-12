import unittest
from pathlib import Path
import sys
from datetime import date

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"ocr"))
from cin_v5_date import date_from_v5_votes


class NumericDateVoteTest(unittest.TestCase):
    def test_two_matching_date_crops(self):
        self.assertEqual(date_from_v5_votes(
            [("40",.91),("40",.86)],
            [("جويلية",.92),("جويلية",.90)],
            [("4991",.89),("4991",.87)],today=date(2026,11,12)),"1994-07-04")
    def test_missing_year_never_inferred(self):
        self.assertIsNone(date_from_v5_votes(
            [("40",.95),("40",.95)],[("جانفي",.95),("جانفي",.95)],
            [("",0),("",0)]))
    def test_conflicting_digits_fail_closed(self):
        self.assertIsNone(date_from_v5_votes(
            [("40",.94),("50",.94)],[("جانفي",.94),("جانفي",.94)],
            [("4991",.94),("4991",.94)]))
    def test_non_calendar_and_future_fail(self):
        self.assertIsNone(date_from_v5_votes(
            [("13",.94),("13",.94)],[("فيفري",.94),("فيفري",.94)],
            [("4991",.94),("4991",.94)]))
        self.assertIsNone(date_from_v5_votes(
            [("10",.94),("10",.94)],[("جانفي",.94),("جانفي",.94)],
            [("0302",.94),("0302",.94)],today=date(2026,11,12)))
    def test_low_probability_never_accepted(self):
        self.assertIsNone(date_from_v5_votes(
            [("40",.10),("40",.95)],[("جويلية",.95),("جويلية",.95)],
            [("4991",.95),("4991",.95)]))


if __name__=="__main__":unittest.main()
