import unittest
from scripts.atlas_existing_source_benchmark import exact_ref,compare_ref,identity,add_matches,result_reference,stored_numeric_hits,observed_source_format,dar_source_rows
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

    def test_actual_gis_dto_is_candidate_not_identity(self):
        row={"record_set":"R","snapshot":"S","native_id":"1"}
        dto={"reference":"000123","feature_id":"1","layer":"L","has_geometry":True,"areas":[]}
        self.assertEqual(result_reference(dto),("000123",None))
        self.assertEqual(identity(row,dto),"GIS_FEATURE_RETURNED_SOURCE_IDENTITY_UNPROVEN")
        self.assertIsNone(result_reference({**dto,"reference":"123 + 456"}))

    def test_stored_text_occurrences_are_exact_digit_strings(self):
        import sqlite3,tempfile
        from pathlib import Path
        from scripts.atlas_archive_review_batch import _digest
        with tempfile.TemporaryDirectory() as d:
            db=Path(d)/"sample.sqlite"
            with sqlite3.connect(db) as c:
                c.execute("CREATE TABLE pages(sha256 TEXT,page INTEGER,text TEXT)")
                c.executemany("INSERT INTO pages VALUES(?,?,?)",[
                    ("a"*64,1,"٠٠١٢٣ ; 12345"),("a"*64,2,"9001239")])
            result,counts=stored_numeric_hits(db,_digest(db),{"a"*64:{}},{"00123":{("00123",None)}})
            self.assertEqual(result["00123"],{("a"*64,1)})
            self.assertEqual(counts["indexed_text_pages_checked"],2)

    def test_observed_attached_scope_and_extra_code(self):
        for text,number,scope in [("TFN99999Ariana","99999","ariana"),("TFN9999Tunis","9999","tunis"),("TFN99999 Tunis S9","99999","tunis")]:
            parsed=observed_source_format(text)
            self.assertEqual(parsed["tokens"],{(number,None)})
            self.assertEqual(parsed["scope_labels_private"],[scope])
        self.assertEqual(observed_source_format("TFN99999 Tunis S9")["extra_code_private"],"S9")
    def test_compounds_never_promoted_to_titles(self):
        for text in ("TFN99999/9Ariana","TFN:99999/9999/99999Tunis","TFN99999Ariana/TFN99999/9999Ariana TBD:99999","999999   999999"):
            parsed=observed_source_format(text)
            self.assertFalse(parsed["tokens"])
            self.assertTrue(parsed["weak_numbers"])

    def test_dar_cohort_preserves_source_identity_without_owner_values(self):
        import sqlite3,tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            db=Path(d)/"property.sqlite"
            with sqlite3.connect(db) as c:
                c.execute("CREATE TABLE subjects(feature_id TEXT,snapshot TEXT,native_id TEXT)")
                c.execute("CREATE TABLE title_tokens(feature_id TEXT,field TEXT,token_position INTEGER,token TEXT,status TEXT)")
                c.execute("INSERT INTO subjects VALUES('source:1','snapshot','1')")
                c.execute("INSERT INTO title_tokens VALUES('source:1','Titre_Foncier',0,'00123','CANDIDATE')")
            rows,status=dar_source_rows(db)
            self.assertEqual(status,"READ_EXISTING_SOURCE_COHORT")
            self.assertEqual(rows[0]["raw_reference"],"00123")
            self.assertEqual(rows[0]["source_feature_id_private"],"source:1")
            self.assertEqual(rows[0]["record_set"],"dar_chaabane_title_tokens")
