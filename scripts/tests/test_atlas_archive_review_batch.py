"""Synthetic archive-to-vector/OCR batch integration; no live data or services."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, NameObject

from scripts.atlas_archive_review_batch import run_batch, _ocr_page_evidence


class ArchiveBatchTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
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
        second = self.run_one("second", offset=1, max_documents=1)
        self.assertEqual(second["all_catalogue_states_private"][self.b], "PRIVATE_REVIEW_REQUIRED")
        self.assertIsNone(second["next_offset"])
        self.assertEqual(self.db.read_bytes(), initial)
        self.assertEqual(hashlib.sha256((self.root/"priority.pdf").read_bytes()).hexdigest(), self.a)

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
