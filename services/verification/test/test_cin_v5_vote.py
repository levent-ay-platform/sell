"""Fictional Arabic strings only; no actual Tunisian identity cards in CI."""
import math
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ocr"))
from cin_v5_vote import select_name


class CinV5ConsensusTest(unittest.TestCase):
    def test_two_agreeing_readings_are_sufficient(self):
        name, confidence = select_name([
            ("سالم", .94), ("سالم", .85), ("مقنع", .99)])
        self.assertEqual(name, "سالم")
        self.assertEqual(confidence, .85)

    def test_hallucinated_english_and_unlabelled_numbers_never_win(self):
        self.assertEqual(select_name([
            ("RATIO", .99), ("RATIO", .99), ("12345678", .99)]),
            (None, 0.))

    def test_disagreement_fails_closed_without_guess(self):
        self.assertEqual(select_name([
            ("نجم", .97), ("حسام", .98), ("فراس", .99)]), (None, 0.))

    def test_low_confidence_cannot_create_consensus(self):
        self.assertEqual(select_name([
            ("سالم", .50), ("سالم", .66), ("فراس", .99)]), (None, 0.))

    def test_alef_maqsura_and_ya_are_only_equivalent_for_voting(self):
        name, score = select_name([
            ("مصطفى", .94), ("مصطفي", .88), ("فراس", .79)])
        self.assertEqual(name, "مصطفى")  # unmodified winning OCR form
        self.assertEqual(score, .88)

    def test_invalid_numerical_confidence_is_rejected(self):
        self.assertEqual(select_name([
            ("سالم", float("nan")), ("سالم", float("inf")),
            ("فراس", .92)]), (None, 0.))

    def test_three_independent_crops_required(self):
        self.assertEqual(select_name([("سالم", .98)]), (None, 0.))


if __name__ == "__main__":
    unittest.main()
