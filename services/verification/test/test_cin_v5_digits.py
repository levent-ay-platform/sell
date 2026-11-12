import unittest
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"ocr"))
from cin_v5_eval_scope import scope_for,score_availability
# The repository-wide synthetic test runner does not install ONNX/numpy.
# The isolated OCR image has numpy and runs these numeric decoder tests.
try:
    import numpy as np
    from cin_v5_digits import decode_numeric_ctc
except ImportError:
    np=None
    decode_numeric_ctc=None


@unittest.skipUnless(np is not None, "numeric decoder tested in OCR image")
class DigitDecodeTest(unittest.TestCase):
    def setUp(self):
        self.chars=list("0123456789")+["O","ع"," "]
        self.width=len(self.chars)+1
    def frames(self,raw,overrides=None):
        frames=[]
        for i,ch in enumerate(raw):
            row=np.zeros(self.width,dtype=np.float32)
            row[self.chars.index(ch)+1]=.95
            row[0]=.05
            if overrides and i in overrides:
                for label,prob in overrides[i].items():
                    row[self.chars.index(label)+1]=prob
            frames.append(row)
            blank=np.zeros(self.width,dtype=np.float32)
            blank[0]=1.
            frames.append(blank)
        return np.array(frames)
    def test_complete_eight_digits_only(self):
        text,confidence=decode_numeric_ctc(self.frames("12345678"),self.chars)
        self.assertEqual(text,"87654321")
        self.assertGreater(confidence,.9)
    def test_zero_can_win_against_ambiguous_letter_with_real_support(self):
        src=self.frames("2004",{1:{"O":.7,"0":.4},2:{"O":.7,"0":.4}})
        text,confidence=decode_numeric_ctc(src,self.chars)
        self.assertEqual(text,"4002")
        self.assertGreater(confidence,.6)
    def test_no_hallucinated_digits_on_text(self):
        src=self.frames("OOOOOOOO",{i:{"0":.01} for i in range(8)})
        self.assertEqual(decode_numeric_ctc(src,self.chars),("",0.0))
    def test_blank_frame_fails(self):
        src=np.zeros((20,self.width),dtype=np.float32)
        src[:,0]=1.
        self.assertEqual(decode_numeric_ctc(src,self.chars),("",0.0))
    def test_nan_fails(self):
        src=self.frames("12345678")
        src[0,1]=float("nan")
        self.assertEqual(decode_numeric_ctc(src,self.chars),("",0.0))


class FivePrivateScopeTest(unittest.TestCase):
    def test_cin_only_one_two_five_but_birth_all(self):
        self.assertEqual([i for i in range(1,6) if scope_for(i)["cin"]],[1,2,5])
        self.assertEqual([i for i in range(1,6) if scope_for(i)["birthDate"]],[1,2,3,4,5])
    def test_availability_does_not_count_masked_numbers(self):
        rows=[{"index":i,"fields":{"cinLast4":"1234","birthDate":"1990-01-02"}}
              for i in range(1,6)]
        self.assertEqual(score_availability(rows),{
            "cin_visible_returned":3,"cin_visible_total":3,
            "birth_returned":5,"birth_total":5,
            "accuracy":"UNMEASURED_WITHOUT_PRIVATE_GROUND_TRUTH"})
    def test_zero_readouts_are_not_accuracy_claims(self):
        rows=[{"index":i,"fields":{"cinLast4":None,"birthDate":None}}
              for i in range(1,6)]
        result=score_availability(rows)
        self.assertEqual((result["cin_visible_returned"],result["birth_returned"]),(0,0))
        self.assertEqual(result["accuracy"],"UNMEASURED_WITHOUT_PRIVATE_GROUND_TRUTH")
    def test_duplicate_sample_rejected(self):
        rows=[{"index":1,"fields":{}}]*5
        with self.assertRaises(ValueError):score_availability(rows)


if __name__=="__main__":unittest.main()
