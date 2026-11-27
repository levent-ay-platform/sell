"""Environment preflight contracts; no private files or network."""
import unittest
from unittest.mock import patch

from scripts import atlas_environment_preflight as p


class PreflightTest(unittest.TestCase):
    def test_missing_pdf_parser_blocks_vector(self):
        with patch.object(p, "_import_ok", return_value=False):
            r = p.readiness()
        self.assertFalse(r["ready"])
        self.assertIn("pypdf", r["missing"])

    def test_vector_does_not_require_ocr(self):
        with patch.object(p, "_import_ok", return_value=True), \
             patch.object(p.shutil, "which", return_value=None):
            r = p.readiness()
        self.assertNotIn("tesseract", r["checks"])
        self.assertEqual(r["ready"], r["checks"]["python_at_least_3_11"])

    def test_ocr_missing_french_is_not_ready(self):
        with patch.object(p, "_import_ok", return_value=True), \
             patch.object(p.shutil, "which", return_value="/synthetic/tool"), \
             patch.object(p, "_languages", return_value={"ara"}):
            r = p.readiness(ocr=True)
        self.assertFalse(r["ready"])
        self.assertIn("ocr_french", r["missing"])

    def test_complete_ocr(self):
        with patch.object(p, "_import_ok", return_value=True), \
             patch.object(p.shutil, "which", return_value="/synthetic/tool"), \
             patch.object(p, "_languages", return_value={"ara", "fra"}):
            r = p.readiness(ocr=True)
        self.assertEqual(r["ready"], r["checks"]["python_at_least_3_11"])

    def test_languages_failure_retains_empty_set(self):
        with patch.object(p.shutil, "which", return_value="/synthetic/tool"), \
             patch.object(p.subprocess, "run", side_effect=OSError):
            self.assertEqual(p._languages(), set())


if __name__ == "__main__":
    unittest.main()
