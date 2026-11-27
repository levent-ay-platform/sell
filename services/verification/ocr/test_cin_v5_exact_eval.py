"""Fictional-only exact CIN and DOB evaluation. No real identity data."""
import unittest
from cin_v5_eval_scope import score_private_exact


def sample(idx,cin,expected_birth,observed_cin=None,observed_birth=None):
    return {"index":idx,
            "expected":{"cinFull":cin,"birthDate":expected_birth},
            "observed":{"cinFull":observed_cin,"birthDate":observed_birth}}


class PrivateAccuracyScoreTest(unittest.TestCase):
    def records(self):
        return [
            sample(1,"12345678","2000-06-13","12345678","2000-06-13"),
            sample(2,"87654321","1999-05-04","87654322","1999-05-04"),
            sample(3,None,"1970-03-16",None,"1970-03-16"),
            sample(4,None,"1980-08-11",None,None),
            sample(5,"13572468","1960-09-10",None,"1960-09-11")]
    def test_separate_evaluable_denominators_and_full_eight_digit_comparison(self):
        self.assertEqual(score_private_exact(self.records()),{
            "cin_exact_matches":1,"cin_readable":2,"cin_evaluable":3,
            "birth_exact_matches":3,"birth_readable":4,"birth_evaluable":5})
    def test_last_four_agreement_is_not_full_id_match(self):
        rows=self.records()
        rows[1]["observed"]["cinFull"]="11114321"
        counts=score_private_exact(rows)
        self.assertEqual(counts["cin_exact_matches"],1)
        self.assertEqual(counts["cin_readable"],2)
    def test_masked_cin_must_not_become_a_target(self):
        rows=self.records()
        rows[3]["expected"]["cinFull"]="55554444"
        with self.assertRaises(ValueError):
            score_private_exact(rows)
    def test_wrong_or_missing_private_ground_truth_rejected(self):
        rows=self.records()
        rows[0]["expected"]["cinFull"]="1234"
        with self.assertRaises(ValueError):
            score_private_exact(rows)
        rows=self.records()
        rows[2]["expected"]["birthDate"]="1970-02-31"
        with self.assertRaises(ValueError):
            score_private_exact(rows)
        rows=self.records()[:-1]
        with self.assertRaises(ValueError):
            score_private_exact(rows)
    def test_duplicate_indices_rejected(self):
        rows=self.records()
        rows[3]["index"]=3
        with self.assertRaises(ValueError):
            score_private_exact(rows)


if __name__=="__main__":
    unittest.main()
