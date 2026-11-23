"""Contract tests: source preservation, conservative classification, and private output."""
from __future__ import annotations

import hashlib
from pathlib import Path
import sqlite3
import tempfile
import unittest

from scripts.atlas_cadastre_evidence_audit import (
    audit_snapshot,
    write_private_json,
)


class CadastreEvidenceAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.db = self.root / "fixture.sqlite"
        connection = sqlite3.connect(self.db)
        connection.executescript(
            """
            CREATE TABLE resources (
                id TEXT, name TEXT, extension TEXT, sha256 TEXT
            );
            CREATE TABLE documents (
                sha256 TEXT, state TEXT, page_count INTEGER
            );
            CREATE TABLE pages (
                sha256 TEXT, page INTEGER, text TEXT
            );
            INSERT INTO resources VALUES
                ('a','Plan parcellaire - source secret.pdf','.pdf','shared'),
                ('b','Same archived document.pdf','.pdf','shared'),
                ('c','Plan de lotissement.pdf','.pdf','planning'),
                ('d','Missing SHA.pdf','.pdf',NULL),
                ('e','Image.jpg','.jpg','image');
            INSERT INTO documents VALUES
                ('shared','indexed',2),
                ('planning','indexed',1);
            INSERT INTO pages VALUES
                ('shared',1,'Plan cadastral. Titre Foncier 00001234. parcelle no 007.'),
                ('shared',2,'مخطط مسح عقاري، الرسم العقاري ٠٠٩٩'),
                ('planning',1,'Plan de lotissement Lot 32.'),
                ('image',1,'Plan cadastral.');
            """
        )
        connection.commit()
        connection.close()

    def test_conservative_classification_with_source_multiplicity(self):
        original_hash = hashlib.sha256(self.db.read_bytes()).hexdigest()
        report = audit_snapshot(self.db, snapshot_kind="mainfile_diagnostic")
        self.assertEqual(report["counts"]["all_resource_rows_preserved"], 5)
        self.assertEqual(report["counts"]["pdf_resource_rows"], 4)
        self.assertEqual(report["counts"]["distinct_pdf_sha256"], 2)
        self.assertEqual(report["counts"]["pdf_resource_rows_without_sha"], 1)
        self.assertEqual(report["counts"]["pdf_resource_rows_with_indexed_document"], 3)
        self.assertEqual(report["counts"]["cadastral_keyword_candidate_documents"], 1)
        candidate = report["candidates"][0]
        self.assertEqual(candidate["document_sha256"], "shared")
        self.assertEqual(candidate["resource_rows"], 2)
        self.assertEqual(candidate["title_reference_candidates"], ["00001234", "0099"])
        self.assertEqual(candidate["parcel_reference_candidates"], ["007"])
        self.assertEqual(candidate["ocr_page_candidates"], [1, 2])
        self.assertIsNone(report["verified_official_cadastral_plans"])
        self.assertIsNone(report["verified_georeferenced_parcels"])
        self.assertFalse(report["live_consistent"])
        self.assertEqual(original_hash, hashlib.sha256(self.db.read_bytes()).hexdigest())
        self.assertNotIn("source secret", str(report))
        self.assertNotIn("Plan de lotissement", str(report))

    def test_private_output_and_no_overwrite(self):
        output = self.root / "private" / "report.json"
        write_private_json(output, {"result": "candidate_only"})
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        self.assertIn("candidate_only", output.read_text())
        with self.assertRaises(FileExistsError):
            write_private_json(output, {"result": "overwrite"})

    def test_missing_tables_fail_closed(self):
        other = self.root / "wrong.sqlite"
        sqlite3.connect(other).close()
        with self.assertRaises(ValueError):
            audit_snapshot(other, snapshot_kind="transactional_backup")

    def test_invalid_snapshot_kind_fails(self):
        with self.assertRaises(ValueError):
            audit_snapshot(self.db, snapshot_kind="live_guess")


if __name__ == "__main__":
    unittest.main()
