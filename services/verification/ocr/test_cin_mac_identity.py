import unittest
from cin_mac_identity import candidates, consensus, merge_fields

def line(text,y,confidence=.95):
    return {"text":text,"confidence":confidence,"bbox":{"left":300,"right":800,"top":y-10,"bottom":y+10}}
def doc(*lines):return {"pages":[{"lines":list(lines)}]}
class DualEngine(unittest.TestCase):
    def test_four_labelled_fields(self):
        d=doc(line("بطاقة تعريف وطنية",100),line("00001122",270),line("اللقب بن سالم",395),line("الاسم ليلى",470),line("الولادة 02 جانفي 1990",600))
        c=candidates(d,1200,760)
        self.assertEqual(consensus(c,c),{"surname":"بن سالم","givenName":"ليلى","cinFull":"00001122","birthDate":"1990-01-02"})
    def test_no_unrelated_document(self):
        c=candidates(doc(line("00001122",270)),1200,760)
        self.assertTrue(all(not v for v in c.values()))
    def test_two_views_and_conflicts(self):
        a={k:{"x"} for k in ("surname","givenName","cinFull","birthDate")}
        b={**a,"surname":{"y"}}
        self.assertIsNone(consensus(a,b)["surname"])
        v={"surname":"سالم","givenName":None,"cinFull":"00001122","cinLast4":"1122","birthDate":None,"confidencePercent":90}
        m=merge_fields(v,{"surname":"خالد","givenName":"ليلى","cinFull":"00001123","birthDate":"1990-01-02"})
        self.assertIsNone(m["surname"]);self.assertIsNone(m["cinFull"]);self.assertIsNone(m["cinLast4"])
        self.assertEqual(m["givenName"],"ليلى");self.assertEqual(m["birthDate"],"1990-01-02")
    def test_no_confidence_inflation(self):
        v={"surname":None,"givenName":None,"cinFull":None,"cinLast4":None,"birthDate":None,"confidencePercent":0}
        m=merge_fields(v,{"surname":"سالم","givenName":"ليلى"})
        self.assertEqual(m["confidencePercent"],0)
