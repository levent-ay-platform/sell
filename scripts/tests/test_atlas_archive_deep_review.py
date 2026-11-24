import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from pypdf import PdfWriter
from scripts.atlas_archive_deep_review import extract_candidates, review_pdf
from scripts.atlas_pdf_blob_inventory import scan_pdf_blobs

class DeepArchiveTests(unittest.TestCase):
    def test_normalized_refs_suffixes_and_area(self):
        result = extract_candidates("T.F. n° 000123 / Tunis\nالمقسم عدد ٠٠٢١\nSuperficie: 120,25 m²\nPRIVATE_NAME")
        self.assertEqual(result["title_candidates"], [{"number":"000123","suffix":"Tunis"}])
        self.assertEqual(result["parcel_candidates"], [{"number":"0021","suffix":None}])
        self.assertEqual(result["area_candidates"], [{"value":"120.25","unit":"m2"}])
        self.assertNotIn("PRIVATE_NAME", json.dumps(result))
    def test_no_unlabeled_numbers_or_cross_line_pair(self):
        result = extract_candidates("20261124\nTitre foncier\n000123\nSurface: 1.234 m2")
        self.assertEqual(result["title_candidates"], [])
        self.assertEqual(result["area_candidates"], [])
    def test_no_partial_long_identifier(self):
        self.assertEqual(extract_candidates("TF 123456789012345")["title_candidates"], [])
    def test_extensionless_recovery_opt_in(self):
        with tempfile.TemporaryDirectory() as t:
            base=Path(t).resolve()
            root=base/"archive"; root.mkdir()
            raw=b"%PDF-1.4\nsynthetic"
            key=hashlib.sha256(raw).hexdigest()
            (root/"blob-no-extension").write_bytes(raw)
            (root/"not-a-pdf.bin").write_bytes(b"not PDF")
            backlog=base/"backlog.json"
            backlog.write_text(json.dumps({"kind":"ATLAS_PRIVATE_OCR_BACKLOG_CANDIDATES_ONLY",
              "snapshot_kind":"mainfile_diagnostic","unhashed_pdf_resource_ids_private":[],"counts":{"distinct_hashed_pdfs":1,"pdf_rows_without_sha":0},
              "backlog_private":[{"document_sha256_private":key,"resource_rows":1,"resource_ids_private":["x"]}]}))
            self.assertEqual(scan_pdf_blobs(backlog,root)["counts"]["catalogue_hashes_with_pdf_header_hint"],0)
            report=scan_pdf_blobs(backlog,root,probe_other_extensions=True)
            self.assertEqual(report["counts"]["catalogue_hashes_with_pdf_header_hint"],1)
            self.assertEqual(report["pdf_header_discoveries_other_extensions"],1)
            with self.assertRaisesRegex(ValueError,"MAX_HEADER_PROBES"):
                scan_pdf_blobs(backlog,root,probe_other_extensions=True,max_probe_files=1)
    @unittest.skipUnless(shutil.which("pdfinfo") and shutil.which("pdftotext"), "Poppler required")
    def test_real_large_page_count_window_and_sha(self):
        with tempfile.TemporaryDirectory() as t:
            base=Path(t).resolve(); pdf=base/"nine.pdf"
            w=PdfWriter()
            for _ in range(9): w.add_blank_page(300,300)
            with pdf.open("wb") as f: w.write(f)
            digest=hashlib.sha256(pdf.read_bytes()).hexdigest()
            result=review_pdf(pdf,digest,base/"first.json",max_pages=2)
            self.assertTrue(result["legacy_page_limit_exceeded"])
            self.assertEqual(result["document_page_count"],9)
            self.assertEqual(result["next_page"],3)
            self.assertEqual(len(result["pages_private"]),2)
            self.assertFalse(result["ground_verified"])
            with self.assertRaisesRegex(ValueError,"SHA_OR_HEADER"):
                review_pdf(pdf,"0"*64,base/"wrong.json")
            self.assertFalse((base/"wrong.json").exists())
