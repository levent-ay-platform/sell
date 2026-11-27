"""Synthetic end-to-end generation tests using the real SQLite backup API."""
from contextlib import closing
import hashlib
from pathlib import Path
import sqlite3
import tempfile
import unittest

from scripts.atlas_generation_review import build_generation, reconcile_inventory
from scripts.atlas_ocr_backlog import build_backlog
from scripts.atlas_pdf_blob_inventory import scan_pdf_blobs
from scripts.atlas_cadastre_evidence_audit import write_private_json
import json
from scripts.atlas_sqlite_snapshot import backup_from_producer_connection


class GenerationReviewTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.source = self.root/"source.sqlite"
        self.inventory = self.root/"inventory.json"
        self.inventory.write_text('{"fixture":true}')
        self.inventory.chmod(0o600)
        self.inventory_hash = hashlib.sha256(self.inventory.read_bytes()).hexdigest()
        self.db = sqlite3.connect(self.source)
        self.addCleanup(self.db.close)
        self.db.executescript("""
          CREATE TABLE resources(id TEXT,name TEXT,extension TEXT,sha256 TEXT,url TEXT);
          CREATE TABLE documents(sha256 TEXT,state TEXT,page_count INTEGER);
          CREATE TABLE pages(sha256 TEXT,page INTEGER,text TEXT);
          INSERT INTO resources VALUES('r','sample.pdf','.pdf','a','https://example.invalid/a.pdf');
          INSERT INTO documents VALUES('a','native_text_unreviewed',4);
          INSERT INTO pages VALUES('a',1,'original private text');
        """)
        self.db.commit()
        self.baseline = self.export("baseline")

    def export(self,name):
        paths = self.root/(name+".sqlite"), self.root/(name+".json")
        backup_from_producer_connection(self.db,*paths)
        return paths

    def review(self,current,**kwargs):
        opts = dict(inventory_sha256=self.inventory_hash,extractor_revision="fixture-v1",minimum_baseline_pages=1)
        opts.update(kwargs)
        return build_generation(*self.baseline,*current,self.inventory,**opts)

    def test_real_backup_retains_baseline_and_schedules_only_gaps(self):
        self.db.execute("INSERT INTO pages VALUES('a',3,'new text')")
        self.db.commit()
        result = self.review(self.export("current"))
        self.assertEqual(result["state"],"PRIVATE_DELTA_REVIEW_REQUIRED")
        self.assertEqual(result["counts"]["retained_baseline_rows"],1)
        self.assertEqual(result["counts"]["added_or_changed_rows"],1)
        item = result["delta_ledger_private"][0]
        self.assertEqual(item["existing_text_pages"],[1,3])
        self.assertEqual(item["missing_ranges_inclusive"],[[2,2],[4,4]])
        self.assertFalse(item["extraction_authorized"])
        self.assertNotIn("original private text",str(result))
        self.assertNotIn("example.invalid",str(result))
        self.assertFalse(result["public_export_permitted"])

    def test_equal_counts_do_not_hide_replaced_baseline_text(self):
        self.db.execute("UPDATE pages SET text='replacement'")
        self.db.commit()
        result = self.review(self.export("changed"))
        self.assertEqual(result["state"],"HOLD_BASELINE_REGRESSION")
        self.assertEqual(result["counts"]["removed_or_changed_baseline_rows"],1)
        self.assertEqual(result["counts"]["current_page_rows"],1)

    def test_duplicate_baseline_rows_are_accounted(self):
        self.db.execute("INSERT INTO pages SELECT * FROM pages")
        self.db.commit()
        self.baseline = self.export("duplicate")
        self.db.execute("DELETE FROM pages WHERE rowid=(SELECT MAX(rowid) FROM pages)")
        self.db.commit()
        result = self.review(self.export("lost"))
        self.assertEqual(result["state"],"HOLD_BASELINE_REGRESSION")
        self.assertEqual(result["counts"]["removed_or_changed_baseline_rows"],1)

    def test_unknown_coverage_is_held(self):
        self.db.execute("UPDATE documents SET page_count=NULL")
        self.db.commit()
        result = self.review(self.export("unknown"))
        self.assertEqual(result["delta_ledger_private"][0]["state"],"HOLD_EXPECTED_PAGE_COUNT_UNKNOWN")
        self.assertIsNone(result["delta_ledger_private"][0]["missing_ranges_inclusive"])

    def test_conflicting_text_is_held(self):
        self.db.execute("INSERT INTO pages VALUES('a',1,'different')")
        self.db.commit()
        result = self.review(self.export("conflict"))
        self.assertEqual(result["delta_ledger_private"][0]["state"],"HOLD_CONFLICT_OR_FAILURE")
        self.assertEqual(result["delta_ledger_private"][0]["conflicting_pages"],[1])

    def test_complete_text_does_not_authorize_publication(self):
        self.db.executemany("INSERT INTO pages VALUES('a',?,'text')",[(2,),(3,),(4,)])
        self.db.commit()
        result = self.review(self.export("complete"))
        self.assertEqual(result["state"],"PRIVATE_TEXT_COVERAGE_ACCOUNTED")
        self.assertFalse(result["live_service_acceptance"])
        self.assertFalse(result["public_export_permitted"])

    def test_inventory_hash_and_minimum_baseline_enforced(self):
        current = self.export("current")
        with self.assertRaisesRegex(ValueError,"INVENTORY_HASH"):
            self.review(current,inventory_sha256="0"*64)
        with self.assertRaisesRegex(ValueError,"BASELINE_PAGE_FLOOR"):
            self.review(current,minimum_baseline_pages=2)

    def test_tampered_snapshot_is_rejected(self):
        current = self.export("current")
        with closing(sqlite3.connect(current[0])) as db:
            db.execute("UPDATE pages SET text='tampered'")
            db.commit()
        with self.assertRaisesRegex(ValueError,"BACKUP_PROOF_MISMATCH"):
            self.review(current)

    def test_unhashed_resource_is_not_silently_dropped(self):
        self.db.executemany("INSERT INTO pages VALUES('a',?,'text')",[(2,),(3,),(4,)])
        self.db.execute("INSERT INTO resources VALUES('missing','other.pdf','.pdf',NULL,'')")
        self.db.commit()
        result = self.review(self.export("unhashed"))
        self.assertEqual(result["state"],"PRIVATE_DELTA_REVIEW_REQUIRED")
        self.assertEqual(result["unhashed_pdf_resource_ids_private"],["missing"])

    def test_resource_identity_loss_with_equal_page_counts_is_held(self):
        self.db.execute("DELETE FROM resources")
        self.db.execute("INSERT INTO resources VALUES('replacement','sample.pdf','.pdf','a','')")
        self.db.commit()
        result = self.review(self.export("replaced"))
        self.assertEqual(result["state"],"HOLD_BASELINE_REGRESSION")
        self.assertEqual(result["counts"]["missing_baseline_resource_id_rows"],1)
        self.assertEqual(result["counts"]["missing_baseline_resource_hash_associations"],1)
        self.assertEqual(result["counts"]["removed_or_changed_baseline_rows"],0)

    def test_existing_resource_hash_change_requires_reconciliation(self):
        self.db.execute("UPDATE resources SET sha256='new'")
        self.db.commit()
        result = self.review(self.export("changedlink"))
        self.assertEqual(result["state"],"HOLD_BASELINE_REGRESSION")
        self.assertEqual(result["counts"]["missing_baseline_resource_id_rows"],0)
        self.assertEqual(result["counts"]["missing_baseline_resource_hash_associations"],1)

    def test_previously_unhashed_resource_can_gain_hash(self):
        self.db.execute("INSERT INTO resources VALUES('pending','sample.pdf','.pdf',NULL,'')")
        self.db.commit()
        self.baseline = self.export("pending")
        self.db.execute("UPDATE resources SET sha256='a' WHERE id='pending'")
        self.db.commit()
        result = self.review(self.export("hashed"))
        self.assertEqual(result["state"],"PRIVATE_DELTA_REVIEW_REQUIRED")
        self.assertEqual(result["counts"]["missing_baseline_resource_hash_associations"],0)

    def test_document_identity_loss_is_held_even_if_pages_survive(self):
        self.db.execute("DELETE FROM documents")
        self.db.commit()
        result = self.review(self.export("missingdoc"))
        self.assertEqual(result["state"],"HOLD_BASELINE_REGRESSION")
        self.assertEqual(result["counts"]["missing_baseline_document_identity_rows"],1)
        self.assertEqual(result["counts"]["page_document_identities_without_document_row"],1)

    def test_orphan_page_identity_is_not_a_complete_generation(self):
        self.db.executemany("INSERT INTO pages VALUES('a',?,'text')",[(2,),(3,),(4,)])
        self.db.execute("INSERT INTO pages VALUES('orphan',1,'text')")
        self.db.commit()
        result = self.review(self.export("orphan"))
        self.assertEqual(result["state"],"PRIVATE_DELTA_REVIEW_REQUIRED")
        self.assertEqual(result["counts"]["page_document_identities_without_document_row"],1)

    def real_inventory(self):
        payload=b"%PDF-1.4\\nSynthetic bytes, not a valid legal plan"
        sha=hashlib.sha256(payload).hexdigest()
        self.db.execute("UPDATE resources SET sha256=?",(sha,))
        self.db.execute("UPDATE documents SET sha256=?",(sha,))
        self.db.execute("UPDATE pages SET sha256=?",(sha,))
        self.db.executemany("INSERT INTO pages VALUES(?,?,'text')",[(sha,p) for p in (2,3,4)])
        self.db.commit()
        self.baseline=self.export("realbaseline")
        current=self.export("realcurrent")
        backlog=build_backlog(current[0],"transactional_backup")
        path=self.root/"backlog.json"
        write_private_json(path,backlog)
        folder=self.root/"blobs"
        folder.mkdir()
        (folder/"sample.pdf").write_bytes(payload)
        inventory=scan_pdf_blobs(path,folder)
        return current,backlog,inventory,folder,path

    def test_real_scanner_report_matches_catalogue(self):
        current,backlog,inventory,_,_=self.real_inventory()
        self.inventory.write_text(json.dumps(inventory))
        result=self.review(current,inventory_sha256=hashlib.sha256(self.inventory.read_bytes()).hexdigest())
        self.assertTrue(result["archive_inventory_semantics_verified"])
        self.assertEqual(result["combined_review_state"],"PRIVATE_TEXT_AND_SELECTED_ROOT_PDF_ACCOUNTED")
        self.assertFalse(result["archive_reconciliation_private"]["file_bytes_rechecked"])
        self.assertFalse(result["public_export_permitted"])
        self.assertNotIn("sample.pdf",str(result))

    def test_equal_counts_do_not_hide_inventory_source_mismatch(self):
        _,backlog,inventory,_,_=self.real_inventory()
        inventory["catalogue_private"][0]["resource_ids_private"]=["wrong"]
        report=reconcile_inventory(backlog,inventory)
        self.assertEqual(report["state"],"HOLD_INVENTORY_CATALOGUE_MISMATCH")
        self.assertEqual(report["counts"]["hashes_with_changed_source_associations"],1)

    def test_missing_pdf_report_is_not_archive_complete(self):
        _,backlog,_,folder,path=self.real_inventory()
        (folder/"sample.pdf").unlink()
        report=reconcile_inventory(backlog,scan_pdf_blobs(path,folder))
        self.assertEqual(report["state"],"PRIVATE_ARCHIVE_RECONCILIATION_REQUIRED")
        self.assertEqual(report["counts"]["hashes_not_observed"],1)

    def test_unmatched_file_is_held(self):
        _,backlog,_,folder,path=self.real_inventory()
        (folder/"extra.pdf").write_bytes(b"%PDF-1.4 extra")
        report=reconcile_inventory(backlog,scan_pdf_blobs(path,folder))
        self.assertEqual(report["state"],"PRIVATE_ARCHIVE_RECONCILIATION_REQUIRED")
        self.assertEqual(report["counts"]["local_pdf_files_unmatched_to_catalogue"],1)

    def test_inventory_report_tampered_counts_rejected(self):
        _,backlog,inventory,_,_=self.real_inventory()
        inventory["counts"]["local_pdf_bytes_hashed"]+=1
        with self.assertRaisesRegex(ValueError,"INVENTORY_ACCOUNTING"):
            reconcile_inventory(backlog,inventory)

    def test_incomplete_scan_is_held(self):
        _,backlog,_,folder,path=self.real_inventory()
        (folder/"linked.pdf").symlink_to(folder/"sample.pdf")
        report=reconcile_inventory(backlog,scan_pdf_blobs(path,folder))
        self.assertEqual(report["state"],"HOLD_INVENTORY_SCOPE_INCOMPLETE")

    def test_unrecognized_inventory_never_passes_combined_review(self):
        self.db.executemany("INSERT INTO pages VALUES('a',?,'text')",[(2,),(3,),(4,)])
        self.db.commit()
        result=self.review(self.export("completelegacy"))
        self.assertEqual(result["state"],"PRIVATE_TEXT_COVERAGE_ACCOUNTED")
        self.assertEqual(result["combined_review_state"],"PRIVATE_REVIEW_REQUIRED")
        self.assertFalse(result["archive_inventory_semantics_verified"])

    def test_public_mode_or_sidecar_is_rejected(self):
        current = self.export("current")
        current[1].chmod(0o644)
        with self.assertRaisesRegex(ValueError,"PRIVATE_REGULAR_INPUT"):
            self.review(current)
        current[1].chmod(0o600)
        Path(str(current[0])+"-wal").touch()
        with self.assertRaisesRegex(ValueError,"STANDALONE_SNAPSHOT"):
            self.review(current)


if __name__ == "__main__":
    unittest.main()
