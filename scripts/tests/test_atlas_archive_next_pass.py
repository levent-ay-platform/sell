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

    def test_completed_run_recovery_and_real_pilot(self):
        import hashlib
        import shutil
        import sqlite3
        import tempfile
        from pathlib import Path
        from pypdf import PdfWriter
        from scripts.atlas_archive_review_batch import run_batch
        from scripts.atlas_archive_next_pass import run_pilot
        if not (shutil.which("pdfinfo") and shutil.which("pdftotext")):
            self.skipTest("Poppler required")
        with tempfile.TemporaryDirectory() as t:
            base=Path(t).resolve()
            root=base/"archive"; root.mkdir()
            run=base/"first"; run.mkdir(mode=0o700)
            db=run/"catalogue.sqlite"
            def pdf(path,width):
                w=PdfWriter(); w.add_blank_page(width,150)
                with path.open("wb") as out: w.write(out)
                return hashlib.sha256(path.read_bytes()).hexdigest()
            a=pdf(root/"first.pdf",200)
            b=pdf(root/"hidden-blob",300)
            with sqlite3.connect(db) as conn:
                conn.executescript("CREATE TABLE resources(id TEXT,name TEXT,extension TEXT,sha256 TEXT,url TEXT); CREATE TABLE documents(sha256 TEXT,state TEXT,page_count INTEGER); CREATE TABLE pages(sha256 TEXT,page INTEGER,text TEXT);")
                conn.executemany("INSERT INTO resources VALUES(?,?,?,?,?)",[
                    ("a","Map.pdf",".pdf",a,"https://example.invalid/a"),
                    ("b","Plan cadastral Kelibia.pdf",".pdf",b,"https://otc.nat.tn/b")])
            before=db.read_bytes()
            manifest=run_batch(db,"mainfile_diagnostic",[root],run/"batch01")
            self.assertIsNone(manifest["next_offset"])
            self.assertEqual(manifest["counts"]["eligible_pdf_hashes_in_selected_roots"],1)
            output=base/"second"
            r=run_pilot(run,[root],output,max_documents=1,pages_per_document=1)
            self.assertEqual(r["counts"]["catalogue_hashes"],2)
            self.assertEqual(r["counts"]["newly_observed_hashes"],1)
            self.assertEqual(r["counts"]["pilot_attempted"],1)
            self.assertEqual(r["outcomes_private"][0]["document_sha256_private"],b)
            self.assertEqual(r["outcomes_private"][0]["status"],"PAGE_WINDOW_REVIEWED")
            self.assertEqual(db.read_bytes(),before)
            self.assertIsNone(r["official_plan_count"])
