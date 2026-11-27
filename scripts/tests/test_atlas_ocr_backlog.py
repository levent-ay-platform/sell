"""Contract tests for lossless, source-aware, private PDF OCR backlog."""
from __future__ import annotations

from pathlib import Path
import sqlite3
import tempfile
import unittest

from scripts.atlas_ocr_backlog import build_backlog


class AtlasOcrBacklogTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db=Path(self.tmp.name)/"fixture.sqlite"
        with sqlite3.connect(self.db) as conn:
            conn.executescript("""
                CREATE TABLE resources (id TEXT, name TEXT, extension TEXT, sha256 TEXT, url TEXT);
                CREATE TABLE documents (sha256 TEXT, state TEXT, page_count INTEGER);
                CREATE TABLE pages (sha256 TEXT, page INTEGER, text TEXT);
                INSERT INTO resources VALUES
                    ('1','Plan cadastral Nabeul.pdf','.pdf','shaA','https://www.otc.nat.tn/a.pdf'),
                    ('2','DUPLICATE.pdf','.pdf','shaA','https://geopau.gov.tn/a.pdf'),
                    ('3','Archive.pdf','.pdf','shaB','https://afh.nat.tn/b.pdf'),
                    ('4','Not yet indexed.pdf','.pdf','shaC','https://unknown.example/c.pdf'),
                    ('5','Lost hash.pdf','.pdf',NULL,'https://otc.nat.tn/missing.pdf'),
                    ('6','File.jpg','.jpg','image','https://otc.nat.tn/image.jpg'),
                    ('7','OCR ready.pdf','.pdf','shaD','https://www.otc.nat.tn/d.pdf'),
                    ('8','Broken.pdf','.pdf','shaE','https://geopau.gov.tn/e.pdf');
                INSERT INTO documents VALUES
                    ('shaA','ocr_pending',1),
                    ('shaB','mixed_ocr_pending',1),
                    ('shaD','native_text_unreviewed',1),
                    ('shaE','processing_failed',1);
                INSERT INTO pages VALUES
                    ('shaA',1,NULL),('shaB',1,' '),('shaD',1,'Page text'),
                    ('shaE',1,'');
            """)

    def test_preserves_duplicate_source_associations_and_unknown(self):
        report=build_backlog(self.db,"mainfile_diagnostic")
        counts=report["counts"]
        self.assertEqual(counts["pdf_resource_rows"],7)
        self.assertEqual(counts["distinct_hashed_pdfs"],5)
        self.assertEqual(counts["pdf_rows_without_sha"],1)
        self.assertEqual(counts["backlog_documents"],4)
        self.assertEqual(counts["text_ready_documents"],1)
        self.assertFalse(report["live_consistent"])
        self.assertEqual(report["unhashed_pdf_resource_ids_private"],["5"])
        by_hash={r["document_sha256_private"]:r for r in report["backlog_private"]}
        self.assertEqual(by_hash["shaA"]["resource_rows"],2)
        self.assertEqual(by_hash["shaA"]["resource_ids_private"],["1","2"])
        self.assertEqual(by_hash["shaA"]["origins"],["GEOPAU","OTC"])
        self.assertEqual(by_hash["shaA"]["required_action"],"OCR_REQUIRED_AFTER_BLOB_CHECK")
        self.assertEqual(by_hash["shaC"]["required_action"],"VERIFY_BLOB_AND_INDEX")
        self.assertEqual(by_hash["shaE"]["required_action"],"REVIEW_INDEX_FAILURE")
        self.assertTrue(by_hash["shaA"]["locality_filename_hint"])
        self.assertTrue(by_hash["shaA"]["cadastre_filename_hint"])
        self.assertFalse(by_hash["shaA"]["blob_signature_verified"])
        self.assertIsNone(by_hash["shaA"]["verified_official_plan"])
        self.assertEqual(counts["source_associations"]["OTC"]["distinct_source_association"],2)
        self.assertEqual(counts["source_associations"]["GEOPAU"]["distinct_source_association"],2)

    def test_private_queue_does_not_contain_raw_urls_or_names(self):
        report=build_backlog(self.db,"mainfile_diagnostic")
        rendered=str(report)
        self.assertNotIn("unknown.example",rendered)
        self.assertNotIn("Plan cadastral Nabeul.pdf",rendered)
        self.assertNotIn("https://",rendered)

    def test_requires_explicit_snapshot_kind(self):
        with self.assertRaisesRegex(ValueError,"EXPLICIT"):
            build_backlog(self.db,"guess_live")

    def test_rejects_missing_tables(self):
        path=Path(self.tmp.name)/"missing.sqlite"
        sqlite3.connect(path).close()
        with self.assertRaisesRegex(ValueError,"ATLAS_TABLES_REQUIRED"):
            build_backlog(path,"transactional_backup")

    def test_filename_hint_priority_never_confers_official_status(self):
        report=build_backlog(self.db,"mainfile_diagnostic")
        self.assertEqual(report["backlog_private"][0]["document_sha256_private"],"shaA")
        self.assertTrue(all(item["verified_official_plan"] is None for
                            item in report["backlog_private"]))


    def test_one_indexed_page_does_not_complete_document(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("UPDATE documents SET page_count=4 WHERE sha256='shaD'")
        report=build_backlog(self.db,"mainfile_diagnostic")
        row=next(r for r in report["backlog_private"] if r["document_sha256_private"]=="shaD")
        self.assertEqual(row["coverage_state"],"PARTIAL_PAGE_COVERAGE")
        self.assertEqual(row["missing_page_count"],3)
        self.assertEqual(row["observed_text_page_ids"],[1])
        self.assertEqual(row["required_action"],"REVIEW_PARTIAL_OR_UNKNOWN_COVERAGE")

    def test_unknown_count_is_not_complete(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("UPDATE documents SET page_count=NULL WHERE sha256='shaD'")
        report=build_backlog(self.db,"mainfile_diagnostic")
        row=next(r for r in report["backlog_private"] if r["document_sha256_private"]=="shaD")
        self.assertEqual(row["coverage_state"],"EXPECTED_PAGE_COUNT_UNKNOWN")
        self.assertIsNone(row["missing_page_count"])

    def test_duplicate_page_rows_never_inflate_coverage(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("UPDATE documents SET page_count=2 WHERE sha256='shaD'")
            conn.execute("INSERT INTO pages VALUES('shaD',1,'Page text')")
        row=next(r for r in build_backlog(self.db,"mainfile_diagnostic")["backlog_private"]
                 if r["document_sha256_private"]=="shaD")
        self.assertEqual(row["indexed_pages"],1)
        self.assertEqual(row["missing_page_count"],1)

    def test_blank_page_is_distinguished_from_absent_page(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("UPDATE documents SET page_count=2 WHERE sha256='shaD'")
            conn.execute("INSERT INTO pages VALUES('shaD',2,'')")
        row=next(r for r in build_backlog(self.db,"mainfile_diagnostic")["backlog_private"]
                 if r["document_sha256_private"]=="shaD")
        self.assertEqual(row["coverage_state"],"PAGES_PRESENT_TEXT_INCOMPLETE")
        self.assertEqual(row["missing_page_count"],0)
        self.assertEqual(row["observed_no_text_page_ids"],[2])

    def test_conflicting_page_status_is_not_complete(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("INSERT INTO pages VALUES('shaD',1,NULL)")
        row=next(r for r in build_backlog(self.db,"mainfile_diagnostic")["backlog_private"]
                 if r["document_sha256_private"]=="shaD")
        self.assertEqual(row["coverage_state"],"CONFLICT_REVIEW")
        self.assertEqual(row["conflicting_page_ids"],[1])
        self.assertEqual(row["required_action"],"REVIEW_PAGE_IDENTITY_CONFLICT")

    def test_two_different_nonblank_texts_conflict(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("INSERT INTO pages VALUES('shaD',1,'Different text')")
        report=build_backlog(self.db,"mainfile_diagnostic")
        row=next(r for r in report["backlog_private"] if r["document_sha256_private"]=="shaD")
        self.assertEqual(row["coverage_state"],"CONFLICT_REVIEW")
        self.assertEqual(row["conflicting_page_ids"],[1])
        self.assertNotIn("Different text",str(report))

    def test_missing_ranges_are_sparse_and_never_authorize_extraction(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("UPDATE documents SET page_count=1000000000 WHERE sha256='shaD'")
            conn.execute("INSERT INTO pages VALUES('shaD',4,'text')")
        row=next(r for r in build_backlog(self.db,"mainfile_diagnostic")["backlog_private"]
                 if r["document_sha256_private"]=="shaD")
        self.assertEqual(row["missing_page_ranges_inclusive"],[[2,3],[5,1000000000]])
        self.assertEqual(row["missing_page_count"],999999998)
        self.assertFalse(row["absence_authorizes_extraction"])

    def test_whitespace_only_page_has_no_text(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("UPDATE pages SET text=? WHERE sha256='shaD'",("\\n\\t".replace("\\n","\n").replace("\\t","\t"),))
        row=next(r for r in build_backlog(self.db,"transactional_backup")["backlog_private"]
                 if r["document_sha256_private"]=="shaD")
        self.assertEqual(row["observed_no_text_page_ids"],[1])

    def test_out_of_range_page_identity_is_held(self):
        with sqlite3.connect(self.db) as conn:
            conn.execute("INSERT INTO pages VALUES('shaD',2,'extra')")
        row=next(r for r in build_backlog(self.db,"mainfile_diagnostic")["backlog_private"]
                 if r["document_sha256_private"]=="shaD")
        self.assertEqual(row["out_of_range_page_ids"],[2])
        self.assertEqual(row["coverage_state"],"CONFLICT_REVIEW")


if __name__=="__main__":
    unittest.main()
