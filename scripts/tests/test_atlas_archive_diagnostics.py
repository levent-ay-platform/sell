import unittest
from scripts.atlas_archive_diagnostics import summarize
A,B,C="a"*64,"b"*64,"c"*64
class DiagnosticTest(unittest.TestCase):
    def test_vector_success_not_outline_and_failure_size_hint(self):
        docs={k:{"resource_ids_private":[k]} for k in (A,B,C)}
        meta={k:{"origins":["OTHER"]} for k in docs}
        entries={A:{"vector_status":"COMPLETED","ocr_status":"WORKER_REJECTED_INPUT_OR_FAILED",
                    "vector_evidence_private":{"candidate_outline_count":0,"document_page_count":9}},
                 B:{"vector_status":"WORKER_TIMEOUT","ocr_status":"WORKER_REJECTED_INPUT_OR_FAILED"}}
        states={A:"PRIVATE_REVIEW_REQUIRED",B:"EXTRACTION_FAILED_RETAINED",C:"NOT_OBSERVED_WITH_PDF_HEADER"}
        inventory={"catalogue_private":[{"document_sha256_private":k,"resource_ids_private":[k],
             "matching_files":[{"bytes":13*1024**2,"pdf_header_hint":True}] if k!=C else []} for k in docs]}
        r=summarize(docs,entries,meta,inventory,states)
        self.assertEqual(r["metrics"]["vector_zero_outlines"],1)
        self.assertEqual(r["metrics"]["failed_over_legacy_ocr_size"],1)
        self.assertEqual(r["metrics"]["observed_over_legacy_ocr_pages"],1)
        self.assertEqual(r["metrics"]["not_observed"],1)
        self.assertNotIn("vector_with_outlines",r["metrics"])
        self.assertEqual(r["metrics"]["attempted"],2)
    def test_inventory_binding(self):
        with self.assertRaisesRegex(ValueError,"ACCOUNTING"):
            summarize({A:{}},{},{A:{}},{"catalogue_private":[]},{A:"PRIVATE_REVIEW_REQUIRED"})
