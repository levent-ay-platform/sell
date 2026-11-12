import unittest
from pathlib import Path
import sys
from datetime import date

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"ocr"))
from cin_v5_date import date_from_v5_votes


class NumericDateVoteTest(unittest.TestCase):
    def test_two_matching_date_crops(self):
        self.assertEqual(date_from_v5_votes(
            [("30",.91),("30",.86)],
            [("جوان",.92),("جوان",.90)],
            [("8991",.89),("8991",.87)],today=date(2026,11,12)),"1998-06-03")
    def test_missing_year_never_inferred(self):
        self.assertIsNone(date_from_v5_votes(
            [("40",.95),("40",.95)],[("جانفي",.95),("جانفي",.95)],
            [("",0),("",0)]))
    def test_conflicting_digits_fail_closed(self):
        self.assertIsNone(date_from_v5_votes(
            [("40",.94),("50",.94)],[("جانفي",.94),("جانفي",.94)],
            [("8991",.94),("8991",.94)]))
    def test_non_calendar_and_future_fail(self):
        self.assertIsNone(date_from_v5_votes(
            [("13",.94),("13",.94)],[("فيفري",.94),("فيفري",.94)],
            [("8991",.94),("8991",.94)]))
        self.assertIsNone(date_from_v5_votes(
            [("10",.94),("10",.94)],[("جانفي",.94),("جانفي",.94)],
            [("0302",.94),("0302",.94)],today=date(2026,11,12)))
    def test_low_probability_never_accepted(self):
        self.assertIsNone(date_from_v5_votes(
            [("40",.10),("40",.95)],[("جوان",.95),("جوان",.95)],
            [("8991",.95),("8991",.95)]))


if __name__=="__main__":unittest.main()
