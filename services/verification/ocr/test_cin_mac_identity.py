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

    def test_birth_survives_missing_given_name(self):
        d=doc(line("بطاقة تعريف وطنية",100),line("اللقب بن سالم",395),line("الولادة 02 جانفي 1990",600))
        c=candidates(d,1200,760)
        self.assertEqual(c["birthDate"],{"1990-01-02"})
        self.assertFalse(c["givenName"])
    def test_split_labels_and_date(self):
        def part(t,x0,x1,y):
            return {"text":t,"confidence":.95,"bbox":{"left":x0,"right":x1,"top":y-10,"bottom":y+10}}
        d=doc(line("بطاقة تعريف وطنية",100),
              part("الاسم",850,950,470),part("ليلى",660,800,470),
              part("الولادة",850,950,600),part("02",760,800,600),
              part("جانفي",600,730,600),part("1990",450,570,600))
        c=candidates(d,1200,760)
        self.assertEqual(c["givenName"],{"ليلى"})
        self.assertEqual(c["birthDate"],{"1990-01-02"})
    def test_ambiguous_split_name_stays_missing(self):
        def part(t,x0,x1):
            return {"text":t,"confidence":.95,"bbox":{"left":x0,"right":x1,"top":460,"bottom":480}}
        d=doc(line("بطاقة تعريف وطنية",100),part("الاسم",850,950),
              part("ليلى",660,800),part("مريم",450,580))
        c=candidates(d,1200,760)
        self.assertIsNone(consensus(c,c)["givenName"])
    def test_issue_date_not_birth_date(self):
        d=doc(line("بطاقة تعريف وطنية",100),line("تاريخ الاصدار 02 جانفي 1990",600))
        self.assertFalse(candidates(d,1200,760)["birthDate"])
