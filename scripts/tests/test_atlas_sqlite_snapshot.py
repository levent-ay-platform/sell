"""Synthetic owner-owned SQLite snapshot tests; no live Atlas files accessed."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from scripts.atlas_sqlite_snapshot import backup_owner_authorized, backup_from_producer_connection


class OwnerSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder=Path(self.tmp.name).resolve()
        self.original=self.folder/"synthetic.sqlite"
        with sqlite3.connect(self.original) as db:
            db.executescript("""
                CREATE TABLE resources (id INTEGER PRIMARY KEY);
                CREATE TABLE documents (id INTEGER PRIMARY KEY);
                CREATE TABLE pages (id INTEGER PRIMARY KEY);
                INSERT INTO resources VALUES(1),(2);
                INSERT INTO documents VALUES(1);
                INSERT INTO pages VALUES(1),(2),(3);
            """)

    def test_real_backup_api_and_sha256_are_consistent(self):
        output=self.folder/"private"/"backup.sqlite"
        manifest=self.folder/"private"/"manifest.json"
        original_sha=hashlib.sha256(self.original.read_bytes()).hexdigest()
        result=backup_owner_authorized(self.original,output,manifest)
        self.assertEqual(result["backup_integrity_check"],"ok")
        self.assertTrue(result["standard_readonly_query_succeeded"])
        self.assertEqual(result["verified_table_counts"],{
            "resources":2,"documents":1,"pages":3,
        })
        self.assertEqual(hashlib.sha256(output.read_bytes()).hexdigest(),
                         result["backup_sha256"])
        self.assertEqual(hashlib.sha256(self.original.read_bytes()).hexdigest(),
                         original_sha)
        self.assertEqual(output.stat().st_mode & 0o777,0o600)
        self.assertEqual(manifest.stat().st_mode & 0o777,0o600)
        self.assertEqual(json.loads(manifest.read_text())["published"],False)

    def test_manifest_failure_does_not_leave_unpaired_backup(self):
        from unittest.mock import patch
        output=self.folder/"private"/"backup.sqlite"
        manifest=self.folder/"private"/"manifest.json"
        original_sha=hashlib.sha256(self.original.read_bytes()).hexdigest()
        with patch("scripts.atlas_sqlite_snapshot.write_private_json",
                   side_effect=OSError("synthetic manifest failure")):
            with self.assertRaisesRegex(OSError,"synthetic manifest failure"):
                backup_owner_authorized(self.original,output,manifest)
        self.assertFalse(output.exists())
        self.assertFalse(manifest.exists())
        self.assertEqual(hashlib.sha256(self.original.read_bytes()).hexdigest(),original_sha)

    def test_never_overwrite_existing_snapshot(self):
        output=self.folder/"private"/"backup.sqlite"
        manifest=self.folder/"private"/"manifest.json"
        backup_owner_authorized(self.original,output,manifest)
        with self.assertRaises(FileExistsError):
            backup_owner_authorized(self.original,output,manifest)

    def test_wal_header_without_sidecars_refused_before_open(self):
        corrupted=self.folder/"synthetic-wal-header.sqlite"
        raw=bytearray(self.original.read_bytes())
        raw[18]=2
        raw[19]=2
        corrupted.write_bytes(raw)
        with self.assertRaisesRegex(RuntimeError,"WAL_SIDECARS_MISSING"):
            backup_owner_authorized(corrupted,
                self.folder/"private"/"test.sqlite",
                self.folder/"private"/"test.json")
        self.assertFalse((self.folder/"synthetic-wal-header.sqlite-wal").exists())
        self.assertFalse((self.folder/"synthetic-wal-header.sqlite-shm").exists())

    def test_source_alias_must_never_equal_output(self):
        alias=self.folder/"alias"
        alias.symlink_to(self.folder, target_is_directory=True)
        with self.assertRaisesRegex(ValueError,"SOURCE_MUST_NOT_BE_OUTPUT"):
            backup_owner_authorized(self.original,alias/self.original.name,
                self.folder/"proof.json")

    def test_source_must_never_equal_output(self):
        with self.assertRaisesRegex(ValueError,"SOURCE_MUST_NOT_BE_OUTPUT"):
            backup_owner_authorized(self.original,self.original,
                self.folder/"private"/"proof.json")


    def test_producer_exports_committed_wal_as_standalone_snapshot(self):
        from contextlib import closing
        output=self.folder/"private"/"producer.sqlite"
        manifest=self.folder/"private"/"producer.json"
        with closing(sqlite3.connect(self.original)) as db:
            self.assertEqual(db.execute("PRAGMA journal_mode=WAL").fetchone()[0],"wal")
            db.execute("PRAGMA wal_autocheckpoint=0")
            db.execute("INSERT INTO pages VALUES(4)")
            db.commit()
            self.assertTrue(Path(str(self.original)+"-wal").exists())
            result=backup_from_producer_connection(db,output,manifest)
            self.assertFalse(db.in_transaction)
            self.assertEqual(db.execute("PRAGMA journal_mode").fetchone()[0],"wal")
            self.assertEqual(result["source_connection_mode"],"existing_producer")
            self.assertEqual(result["verified_table_counts"]["pages"],4)
            self.assertTrue(result["source_snapshot_counts_pinned"])
            self.assertEqual(output.stat().st_mode & 0o777,0o600)
            self.assertEqual(manifest.stat().st_mode & 0o777,0o600)
            self.assertFalse(Path(str(output)+"-wal").exists())
            with closing(sqlite3.connect(output.as_uri()+"?mode=ro",uri=True)) as copy:
                self.assertEqual(copy.execute("SELECT COUNT(*) FROM pages").fetchone()[0],4)
                self.assertEqual(copy.execute("PRAGMA journal_mode").fetchone()[0],"delete")
            db.execute("INSERT INTO pages VALUES(5)")
            db.commit()
            self.assertEqual(db.execute("SELECT COUNT(*) FROM pages").fetchone()[0],5)

    def test_active_producer_transaction_is_never_committed_or_rolled_back(self):
        from contextlib import closing
        output=self.folder/"private"/"producer.sqlite"
        manifest=self.folder/"private"/"producer.json"
        with closing(sqlite3.connect(self.original)) as db:
            db.execute("INSERT INTO pages VALUES(4)")
            with self.assertRaisesRegex(RuntimeError,"SOURCE_TRANSACTION_ALREADY_ACTIVE"):
                backup_from_producer_connection(db,output,manifest)
            self.assertTrue(db.in_transaction)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM pages").fetchone()[0],4)
            db.rollback()
            self.assertEqual(db.execute("SELECT COUNT(*) FROM pages").fetchone()[0],3)
        self.assertFalse(output.exists())
        self.assertFalse(manifest.exists())

    def test_producer_cannot_overwrite_source(self):
        from contextlib import closing
        with closing(sqlite3.connect(self.original)) as db:
            with self.assertRaisesRegex(ValueError,"SOURCE_MUST_NOT_BE_OUTPUT"):
                backup_from_producer_connection(db,self.original,self.folder/"proof.json")
            self.assertFalse(db.in_transaction)


if __name__=="__main__":
    unittest.main()
