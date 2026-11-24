import unittest
from scripts.atlas_existing_source_benchmark import exact_ref,compare_ref,identity,add_matches
from collections import defaultdict
class ExistingSourceBenchmarkTest(unittest.TestCase):
    def test_reference_exact(self):
        self.assertEqual(exact_ref("٠٠١٢٣ / Tunis"),("00123","tunis"))
        self.assertIsNone(exact_ref("123 + 456"))
        self.assertIsNone(compare_ref(("00123",None),"123",None))
        self.assertIsNone(compare_ref(("00123","tunis"),"00123","nabeul"))
        self.assertEqual(compare_ref(("00123","tunis"),"00123",None),"NUMBER_ONLY_SUFFIX_UNRESOLVED")
    def test_identity_not_oid_only(self):
        row={"record_set":"R","snapshot":"S","native_id":"1"}
        self.assertEqual(identity(row,{"native_id":1}),"RESULT_SCHEMA_UNRESOLVED")
        self.assertEqual(identity(row,{"native_id":1,"snapshot":"S"}),"NATIVE_ID_SNAPSHOT_MATCH_GROUP_UNRESOLVED")
        self.assertEqual(identity(row,{"native_id":1,"snapshot":"S","record_set":"R"}),"SOURCE_IDENTITY_MATCH")
    def test_evidence_retains_scope_uncertainty(self):
        hits=defaultdict(set)
        add_matches(hits,{"00123":{("00123","tunis")}},"a"*64,2,"NATIVE",[("00123","Tunis")],True,False)
        self.assertEqual(next(iter(hits[("00123","tunis")]))[3],"EXACT_TOKEN_SCOPE_UNVERIFIED")
