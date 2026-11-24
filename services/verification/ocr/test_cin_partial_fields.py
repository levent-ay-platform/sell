"""Synthetic per-field regression: no personal documents or model weights."""
import unittest
from unittest.mock import patch
import numpy as np
import cin_v5


class PartialCINFields(unittest.TestCase):
    def run_read(self, names, centers=(390,460)):
        image=np.zeros((760,1200,3),np.uint8)
        with patch.object(cin_v5,"normalize_card",return_value=image), \
             patch.object(cin_v5,"find_name_rows",return_value=centers), \
             patch.object(cin_v5,"consensus_name",side_effect=names), \
             patch.object(cin_v5,"birth_date",return_value="2000-01-01") as dob, \
             patch.object(cin_v5,"_cin_full_front",return_value="00000000") as cin:
            result=cin_v5.extract_fields(image,None,None)
            if centers is None:
                dob.assert_not_called()
                cin.assert_not_called()
            else:
                dob.assert_called_once()
                cin.assert_called_once()
        return result

    def test_one_missing_name_does_not_drop_number_or_birth(self):
        result=self.run_read([("TEST_A",.90),(None,0)])
        self.assertEqual(result["surname"],"TEST_A")
        self.assertIsNone(result["givenName"])
        self.assertEqual(result["cinFull"],"00000000")
        self.assertEqual(result["birthDate"],"2000-01-01")
        self.assertEqual(result["confidencePercent"],0)

    def test_low_confidence_name_stays_missing(self):
        result=self.run_read([("TEST_A",.60),("TEST_B",.95)])
        self.assertIsNone(result["surname"])
        self.assertEqual(result["givenName"],"TEST_B")
        self.assertEqual(result["cinLast4"],"0000")
        self.assertEqual(result["confidencePercent"],0)

    def test_two_good_names_keep_existing_confidence(self):
        result=self.run_read([("TEST_A",.90),("TEST_B",.85)])
        self.assertEqual(result["confidencePercent"],85)

    def test_missing_layout_does_not_read_arbitrary_digits(self):
        self.assertEqual(self.run_read([],None),cin_v5.OUTPUT_EMPTY)


if __name__=="__main__":
    unittest.main()
