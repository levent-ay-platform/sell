"""Synthetic archive-to-vector/OCR batch integration; no live data or services."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sqlite3
import shutil
import tempfile
import unittest
from unittest.mock import patch

from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, NameObject, DictionaryObject

from scripts.atlas_archive_review_batch import run_batch, _ocr_page_evidence


class ArchiveBatchTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name).resolve()
        self.root = self.base / "archive"
        self.root.mkdir()
        self.other = self.base / "other"
        self.other.mkdir()
        self.db = self.base / "snapshot.sqlite"
        self.a = self.pdf(self.root / "priority.pdf", b"20 30 60 40 re S")
        self.b = self.pdf(self.other / "second.pdf", b"10 10 20 20 re S")
        self.missing = "0"*64
        with sqlite3.connect(self.db) as c:
            c.executescript("""
                CREATE TABLE resources (id TEXT, name TEXT, extension TEXT, sha256 TEXT, url TEXT);
                CREATE TABLE documents (sha256 TEXT, state TEXT, page_count INTEGER);
                CREATE TABLE pages (sha256 TEXT, page INTEGER, text TEXT);
            """)
            c.executemany("INSERT INTO resources VALUES (?,?,?,?,?)", [
                ("a", "Plan cadastral Kelibia.pdf", ".pdf", self.a, "https://otc.nat.tn/a.pdf"),
                ("a2", "Copy.pdf", ".pdf", self.a, "https://geopau.gov.tn/copy.pdf"),
                ("b", "Other.pdf", ".pdf", self.b, "https://afh.nat.tn/b.pdf"),
                ("c", "Unknown.pdf", ".pdf", self.missing, "https://example.invalid/c.pdf"),
                ("d", "No hash.pdf", ".pdf", None, "https://example.invalid/d.pdf"),
            ])
            c.execute("INSERT INTO documents VALUES (?, ?, ?)", (self.a,"ocr_pending",1))
            c.execute("INSERT INTO pages VALUES (?, ?, ?)", (self.a,1,None))

    def pdf(self, path, stream):
        w = PdfWriter()
        page = w.add_blank_page(width=200, height=150)
        obj = DecodedStreamObject()
        obj.set_data(stream)
        page[NameObject("/Contents")] = w._add_object(obj)
        with path.open("wb") as file:
            w.write(file)
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def run_one(self, name="output", **kwargs):
        return run_batch(self.db, "mainfile_diagnostic", [self.root,self.other],
                         self.base/name, **kwargs)

    def test_real_pdf_batch_priority_and_resume(self):
        initial = self.db.read_bytes()
        r = self.run_one(max_documents=1)
        self.assertEqual(r["counts"]["catalogue_hashed_pdfs"], 3)
        self.assertEqual(r["counts"]["catalogue_unhashed_rows"], 1)
        self.assertEqual(r["counts"]["eligible_pdf_hashes_in_selected_roots"], 2)
        self.assertEqual(r["next_offset"], 1)
        self.assertEqual(r["all_catalogue_states_private"][self.a], "PRIVATE_REVIEW_REQUIRED")
        self.assertEqual(r["all_catalogue_states_private"][self.b], "DEFERRED_TO_ANOTHER_BATCH")
        self.assertEqual(r["all_catalogue_states_private"][self.missing], "NOT_OBSERVED_WITH_PDF_HEADER")
        result = self.base/"output"/"documents"/"000001.json"
        data = json.loads(result.read_text())
        self.assertEqual(data["resource_ids_private"], ["a","a2"])
        self.assertEqual(data["vector_evidence_private"]["candidate_outline_count"], 1)
        self.assertFalse(data["ground_verified"])
        self.assertIsNone(r["official_plan_count"])
        self.assertEqual(result.stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.base/"output").stat().st_mode & 0o777, 0o700)
        second = self.run_one("second", offset=1, max_documents=1,
                              resume_manifest=self.base/"output"/"completed-private-manifest.json")
        self.assertEqual(second["all_catalogue_states_private"][self.b], "PRIVATE_REVIEW_REQUIRED")
        self.assertEqual(second["all_catalogue_states_private"][self.a], "PRIVATE_REVIEW_REQUIRED")
        self.assertEqual(second["counts"]["cumulative_attempted_hashes"], 2)
        self.assertEqual(second["previous_completed_manifest_sha256_private"],
                         hashlib.sha256((self.base/"output"/"completed-private-manifest.json").read_bytes()).hexdigest())
        self.assertIsNone(second["next_offset"])
        self.assertEqual(self.db.read_bytes(), initial)
        self.assertEqual(hashlib.sha256((self.root/"priority.pdf").read_bytes()).hexdigest(), self.a)

    def test_resume_requires_same_eligible_sequence(self):
        self.run_one(max_documents=1)
        proof = self.base/"output"/"completed-private-manifest.json"
        with self.assertRaisesRegex(ValueError, "PREVIOUS_COMPLETED_MANIFEST"):
            self.run_one("no-proof", offset=1, max_documents=1)
        (self.root/"priority.pdf").unlink()
        with self.assertRaisesRegex(ValueError, "RESUME_QUEUE_CHANGED"):
            self.run_one("changed", offset=1, max_documents=1, resume_manifest=proof)
        self.assertFalse((self.base/"changed"/"completed-private-manifest.json").exists())

    @unittest.skipUnless(shutil.which("pdftotext") and shutil.which("pdfinfo"),
                         "Poppler required for actual native PDF-text worker")
    def test_actual_existing_text_worker_and_vector_share_sha(self):
        file = self.root / "priority.pdf"
        w = PdfWriter()
        page = w.add_blank_page(width=600, height=300)
        font = DictionaryObject({NameObject("/Type"):NameObject("/Font"),
                                 NameObject("/Subtype"):NameObject("/Type1"),
                                 NameObject("/BaseFont"):NameObject("/Helvetica")})
        page[NameObject("/Resources")] = DictionaryObject({
            NameObject("/Font"): DictionaryObject({NameObject("/F1"): w._add_object(font)})})
        obj = DecodedStreamObject()
        obj.set_data(b"BT /F1 14 Tf 20 250 Td (T.F. 00012345 Parcelle 0021 PRIVATE_OWNER) Tj ET 20 30 60 40 re S")
        page[NameObject("/Contents")] = w._add_object(obj)
        with file.open("wb") as f:
            w.write(f)
        sha = hashlib.sha256(file.read_bytes()).hexdigest()
        with sqlite3.connect(self.db) as c:
            for table in ("resources", "documents", "pages"):
                c.execute("UPDATE "+table+" SET sha256=? WHERE sha256=?", (sha,self.a))
        r = self.run_one(max_documents=1, ocr=True)
        self.assertEqual(r["all_catalogue_states_private"][sha], "PRIVATE_REVIEW_REQUIRED")
        entry = json.loads((self.base/"output"/"documents"/"000001.json").read_text())
        self.assertEqual(entry["ocr_status"], "COMPLETED")
        self.assertEqual(entry["vector_status"], "COMPLETED")
        self.assertEqual(entry["vector_evidence_private"]["document_sha256_private"], sha)
        self.assertEqual(entry["page_reference_evidence_private"][0]["title_reference_candidates"], ["00012345"])
        self.assertEqual(entry["page_reference_evidence_private"][0]["parcel_reference_candidates"], ["0021"])
        self.assertNotIn("PRIVATE_OWNER", json.dumps(entry))

    def test_resume_preserves_failure_and_rejects_inconsistent_prior_state(self):
        with patch("scripts.atlas_archive_review_batch._run_child", return_value=(None,"WORKER_TIMEOUT")):
            self.run_one(max_documents=1)
        proof = self.base/"output"/"completed-private-manifest.json"
        second = self.run_one("second", offset=1, max_documents=1, resume_manifest=proof)
        self.assertEqual(second["all_catalogue_states_private"][self.a], "EXTRACTION_FAILED_RETAINED")
        data = json.loads(proof.read_text())
        data["all_catalogue_states_private"][self.a] = "DEFERRED_TO_ANOTHER_BATCH"
        proof.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "RESUME_STATE_ACCOUNTING_MISMATCH"):
            self.run_one("bad", offset=1, max_documents=1, resume_manifest=proof)

    @unittest.skipUnless(shutil.which("tesseract") and shutil.which("pdftoppm")
                         and shutil.which("pdftotext") and shutil.which("pdfinfo"),
                         "Real raster OCR tools required")
    def test_image_only_pdf_real_ocr(self):
        from PIL import Image, ImageDraw, ImageFont
        fonts = [Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
                 Path("/System/Library/Fonts/Supplemental/Arial.ttf")]
        font_path = next((path for path in fonts if path.is_file()), None)
        from scripts.atlas_environment_preflight import readiness
        if not readiness(ocr=True)["ready"]:
            self.skipTest("Arabic and French OCR language data required")
        file = self.root / "priority.pdf"
        image = Image.new("RGB", (1500, 1000), "white")
        draw = ImageDraw.Draw(image)
        font = ImageFont.truetype(str(font_path), 60) if font_path else ImageFont.load_default(size=60)
        draw.text((90, 90), "Document de test", font=font, fill="black")
        draw.text((90, 200), "Titre foncier 00012345", font=font, fill="black")
        draw.text((90, 310), "Parcelle 0021", font=font, fill="black")
        image.save(file, "PDF", resolution=150)
        sha = hashlib.sha256(file.read_bytes()).hexdigest()
        with sqlite3.connect(self.db) as c:
            for table in ("resources", "documents", "pages"):
                c.execute("UPDATE "+table+" SET sha256=? WHERE sha256=?", (sha,self.a))
        r = self.run_one(max_documents=1, ocr=True)
        entry = json.loads((self.base/"output"/"documents"/"000001.json").read_text())
        self.assertEqual(entry["document_sha256_private"], sha)
        self.assertEqual(entry["ocr_status"], "COMPLETED")
        page = entry["page_reference_evidence_private"][0]
        self.assertEqual(page["method"], "ocr")
        self.assertTrue(page["text_present"])
        self.assertIn("00012345", page["title_reference_candidates"])
        self.assertIn("0021", page["parcel_reference_candidates"])
        self.assertIsNone(r["official_plan_count"])
        self.assertFalse(entry["ground_verified"])

    def test_requires_transactional_manifest_not_just_label(self):
        with self.assertRaisesRegex(ValueError, "OWNER_BACKUP_MANIFEST_REQUIRED"):
            run_batch(self.db, "transactional_backup", [self.root], self.base/"output")
        proof = self.base/"manifest.json"
        proof.write_text(json.dumps({"kind":"OWNER_AUTHORIZED_SQLITE_BACKUP",
                                     "backup_api_completed":True,
                                     "standard_readonly_query_succeeded":True,
                                     "backup_integrity_check":"ok",
                                     "backup_sha256":"f"*64}))
        with self.assertRaisesRegex(ValueError, "OWNER_BACKUP_MANIFEST_MISMATCH"):
            run_batch(self.db, "transactional_backup", [self.root], self.base/"output", manifest=proof)

    def test_does_not_overwrite_or_scan_overlapping_roots(self):
        self.run_one(max_documents=1)
        with self.assertRaisesRegex(FileExistsError, "NEW_PRIVATE_RUN_DIRECTORY"):
            self.run_one(max_documents=1)
        sub = self.root/"sub"
        sub.mkdir()
        with self.assertRaisesRegex(ValueError, "ARCHIVE_ROOTS_MUST_NOT_OVERLAP"):
            run_batch(self.db, "mainfile_diagnostic", [self.root,sub], self.base/"other-output")
        with self.assertRaisesRegex(ValueError, "OUTPUT_MUST_BE_OUTSIDE"):
            run_batch(self.db, "mainfile_diagnostic", [self.root], sub/"output")

    def test_output_symlink_ancestor_is_still_rejected(self):
        real = self.base/"real"
        real.mkdir()
        alias = self.base/"alias"
        alias.symlink_to(real, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "SYMLINK_OUTPUT_DIRECTORY"):
            run_batch(self.db, "mainfile_diagnostic", [self.root], alias/"output")
        self.assertFalse((real/"output").exists())

    def test_failed_worker_retains_document(self):
        with patch("scripts.atlas_archive_review_batch._run_child", return_value=(None,"WORKER_TIMEOUT")):
            r = self.run_one(max_documents=1, ocr=True)
        self.assertEqual(r["all_catalogue_states_private"][self.a], "EXTRACTION_FAILED_RETAINED")
        self.assertEqual(sum(r["counts"]["states"].values()), 3)

    def test_ocr_page_references_only_no_full_raw_text(self):
        output = _ocr_page_evidence({"pages":[
            {"page":1,"method":"pdf-text","lines":[{"text":"Plan cadastral. T.F. 00012345. Parcelle 0021. PRIVATE_OWNER"}]},
            {"page":2,"method":"ocr","lines":[{"text":"الرسم العقاري عدد ٠٠٠٦٧٨٩"}]},
        ]})
        self.assertEqual(output[0]["title_reference_candidates"], ["00012345"])
        self.assertEqual(output[0]["parcel_reference_candidates"], ["0021"])
        self.assertEqual(output[1]["title_reference_candidates"], ["0006789"])
        self.assertNotIn("PRIVATE_OWNER", json.dumps(output))


if __name__ == "__main__":
    unittest.main()
