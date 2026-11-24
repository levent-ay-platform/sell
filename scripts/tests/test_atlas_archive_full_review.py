import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
from pypdf import PdfWriter
from scripts.atlas_archive_review_batch import run_batch
from scripts.atlas_archive_full_review import run_full, deep_observations, merge_index
from scripts.atlas_cadastre_evidence_audit import write_private_json

class FullReviewTests(unittest.TestCase):
    def test_qualified_refs_not_dropped_into_numeric_graph(self):
        windows=[{"pages_private":[{"page":1,"observations":[{"status":"COMPLETED",
                  "method":"native_layout","evidence":{"title_candidates":[{"number":"00123","suffix":"A"}],
                  "parcel_candidates":[{"number":"02","suffix":None}],"area_candidates":[],
                  "cadastral_term_present":True,"family_hints":[]}}]}]}]
        rows,_=deep_observations(windows)
        self.assertEqual(rows[0]["title_reference_candidates"],[])
        self.assertEqual(rows[0]["qualified_title_candidates_private"][0]["suffix"],"A")
        self.assertEqual(rows[0]["parcel_reference_candidates"],["02"])

    @unittest.skipUnless(shutil.which("pdfinfo") and shutil.which("pdftotext"),"Poppler required")
    def test_real_pdf_resume_no_rescan_and_index_accounting(self):
        from unittest.mock import patch
        from scripts.atlas_environment_preflight import readiness
        if not readiness(ocr=False)["ready"]:
            self.skipTest("Environment preflight required")
        with tempfile.TemporaryDirectory() as t:
            base=Path(t).resolve()
            root=base/"archive";root.mkdir()
            run=base/"first";run.mkdir(mode=0o700)
            db=run/"catalogue.sqlite"
            w=PdfWriter()
            for _ in range(21):w.add_blank_page(300,200)
            pdf=root/"hidden-blob"
            with pdf.open("wb") as f:w.write(f)
            digest=hashlib.sha256(pdf.read_bytes()).hexdigest()
            with sqlite3.connect(db) as c:
                c.executescript("CREATE TABLE resources(id TEXT,name TEXT,extension TEXT,sha256 TEXT,url TEXT); CREATE TABLE documents(sha256 TEXT,state TEXT,page_count INTEGER); CREATE TABLE pages(sha256 TEXT,page INTEGER,text TEXT);")
                c.execute("INSERT INTO resources VALUES(?,?,?,?,?)",("a","Plan cadastral Kelibia.pdf",".pdf",digest,"https://otc.nat.tn/a"))
            write_private_json(run/"scopes.json",{})
            write_private_json(run/"sources.json",[])
            manifest=run_batch(db,"mainfile_diagnostic",[root],run/"batch01")
            self.assertIsNone(manifest["next_offset"])
            before=db.read_bytes()
            output=base/"second"
            r=run_full(run,[root],output,raster_mode="none",max_windows=1)
            self.assertEqual(r["counts"]["newly_observed_hashes"],1)
            self.assertEqual(r["counts"]["page_attempts"],20)
            self.assertFalse(r["available_queue_attempted"])
            first=output/"full-documents"/digest/"pages-00000001.json"
            initial=first.read_bytes()
            with patch("scripts.atlas_archive_full_review.run_pilot",side_effect=AssertionError("must not rescan")):
                r=run_full(run,[root],output,raster_mode="none")
            self.assertTrue(r["available_queue_attempted"])
            self.assertEqual(r["counts"]["page_attempts"],21)
            self.assertEqual(r["counts"]["new_windows_this_invocation"],1)
            self.assertEqual(first.read_bytes(),initial)
            self.assertEqual(db.read_bytes(),before)
            idx=json.loads((output/r["combined_index_name_private"]).read_text())
            self.assertEqual(idx["counts"]["document_hashes"],1)
            self.assertFalse(idx["public_export_permitted"])
            self.assertIsNone(idx["official_plan_count"])
            with self.assertRaisesRegex(ValueError,"CONFIG_CHANGED"):
                run_full(run,[root],output,raster_mode="none",max_pages=501)
