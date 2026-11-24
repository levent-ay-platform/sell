import unittest
from scripts.atlas_archive_next_pass import plan_rows,priority
A,B,C=("a"*64,"b"*64,"c"*64)
class NextPassTests(unittest.TestCase):
    def test_priority_and_recovered_deduplication(self):
        meta={A:{"resource_ids_private":["a"],"origins":["OTC"],"cadastre_filename_hint":True},
              B:{"resource_ids_private":["b"],"origins":["OTHER"]},
              C:{"resource_ids_private":["c"],"origins":["OTHER"]}}
        docs={k:{} for k in meta}
        first={A:{"status":"EXTRACTION_FAILED_RETAINED"}}
        states={A:"EXTRACTION_FAILED_RETAINED",B:"NOT_OBSERVED_WITH_PDF_HEADER",C:"NOT_OBSERVED_WITH_PDF_HEADER"}
        def inventory():
            return {"catalogue_private":[{"document_sha256_private":k,
              "resource_ids_private":meta[k]["resource_ids_private"],
              "matching_files":[{"relative_path_private":"blob","pdf_header_hint":True,"bytes":50}] if k!=C else []} for k in meta]}
        result=plan_rows(meta,docs,first,states,[inventory(),inventory()])
        self.assertEqual([r["document_sha256_private"] for r in result],[A,B,C])
        self.assertTrue(result[1]["newly_observed_after_first_pass"])
        self.assertEqual(len(result[1]["locations_private"]),2)
        self.assertEqual(result[2]["state"],"NOT_OBSERVED_IN_SELECTED_ROOTS")
        self.assertEqual(len(result),3)
    def test_wrong_inventory_rejected(self):
        meta={A:{"resource_ids_private":["a"]}}
        with self.assertRaisesRegex(ValueError,"BINDING"):
            plan_rows(meta,{A:{}},{},{A:"NOT_OBSERVED_WITH_PDF_HEADER"},[{"catalogue_private":[
                {"document_sha256_private":A,"resource_ids_private":["different"],"matching_files":[]}]}])
    def test_failure_and_cadastral_priority(self):
        score,reasons=priority({"origins":["OTC"]},{"cadastral_term_present":True},{"status":"EXTRACTION_FAILED_RETAINED"},False)
        self.assertGreater(score,400)
        self.assertIn("FIRST_PASS_FAILURE",reasons)
        self.assertIn("CADASTRAL_HINT",reasons)
