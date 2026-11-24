"""Synthetic owner-owned SQLite snapshot tests; no live Atlas files accessed."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from scripts.atlas_sqlite_snapshot import backup_owner_authorized


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


if __name__=="__main__":
    unittest.main()
