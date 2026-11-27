import unittest
from scripts.atlas_focused_page_review import select
class FocusedReviewTests(unittest.TestCase):
    def test_priority_and_budget(self):
        q={"rows":[{"sha":"a"*64,"observations":[{"page":3,"titles":["123"],"cadastral_hint":False}]},
                   {"sha":"b"*64,"observations":[{"page":7,"titles":[],"cadastral_hint":True}]}]}
        chosen,omitted=select(q,None,1)
        self.assertEqual(chosen[0]["sha"],"b"*64)
        self.assertEqual(omitted[0]["sha"],"a"*64)
        chosen,omitted=select(q,None,4)
        self.assertEqual({(r["sha"],r["page"]) for r in chosen},{("a"*64,3),("a"*64,1),("b"*64,7),("b"*64,1)})
    def test_weak_is_not_title(self):
        q={"rows":[{"sha":"a"*64,"observations":[]}]}
        b={"kind":"PRIVATE_ATLAS_EXISTING_SOURCE_BENCHMARK","public_export_permitted":False,"rows_private":[{"untyped_numeric_occurrences_private":[{"sha256":"a"*64,"page":2}]}]}
        chosen,_=select(q,b)
        self.assertEqual(chosen[0]["reasons"],["UNTYPED_NUMBER_ONLY"])
