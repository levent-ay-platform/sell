#!/usr/bin/env python3
"""Operator-only, no-repair consistent SQLite backup using Backup API.

Never falls back to immutable=1 for a live database. Refuses WAL-oriented
sources with missing sidecars to avoid silently fabricating -shm/-wal on the
source. Requires an authorized file-owner context and a source that can be
opened in standard mode=ro. No checkpoint, journal-mode change, or migration.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile

from scripts.atlas_cadastre_evidence_audit import write_private_json

TABLES=("resources","documents","pages","gis_records","published_polygon_records")


def _hash_file(path):
    digest=hashlib.sha256()
    with path.open("rb") as item:
        while block:=item.read(1024*1024):
            digest.update(block)
    return digest.hexdigest()


def _check_source(path):
    if not path.is_file() or path.is_symlink():
        raise ValueError("REGULAR_SOURCE_REQUIRED")
    with path.open("rb") as src:
        header=src.read(20)
    if len(header)<20 or header[:16]!=b"SQLite format 3\x00":
        raise ValueError("INVALID_SQLITE_HEADER")
    wal_mode=header[18]==2 or header[19]==2
    sidecar_paths=[Path(str(path)+ending) for ending in ("-wal","-shm")]
    if wal_mode and any(not item.is_file() or item.is_symlink() for item in sidecar_paths):
        raise RuntimeError("WAL_SIDECARS_MISSING_OWNER_COORDINATION_REQUIRED")
    return wal_mode


def _counts(connection):
    tables={row[0] for row in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    return {table:connection.execute(
        'SELECT COUNT(*) FROM "'+table+'"').fetchone()[0]
        for table in TABLES if table in tables}


def backup_owner_authorized(source, target, manifest):
    source, target, manifest = Path(source), Path(target), Path(manifest)
    if source.resolve() in (target.resolve(),manifest.resolve()):
        raise ValueError("SOURCE_MUST_NOT_BE_OUTPUT")
    if target.resolve()==manifest.resolve() or target.exists() or target.is_symlink() or manifest.exists() or manifest.is_symlink():
        raise FileExistsError("REFUSING_TO_OVERWRITE_BACKUP_OR_MANIFEST")
    for parent in (target.parent,manifest.parent):
        if parent.exists() and parent.is_symlink():
            raise ValueError("SYMLINK_OUTPUT_DIRECTORY")
        parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    wal_mode=_check_source(source)  # before any SQLite connection to source
    old_umask=os.umask(0o077)
    staging=None
    try:
        fd,staging=tempfile.mkstemp(prefix=".atlas-backup-",dir=str(target.parent))
        os.close(fd)
        os.chmod(staging,0o600)
        source_uri=source.resolve().as_uri()+"?mode=ro"
        # NO immutable=1: SQLite must see live committed state / WAL.
        with closing(sqlite3.connect(source_uri,uri=True,timeout=2.0)) as read:
            read.execute("PRAGMA query_only=ON")
            source_counts=_counts(read)  # prove a normal RO SQL statement worked
            with closing(sqlite3.connect(staging)) as write:
                read.backup(write,pages=128,sleep=0.02)
                write.commit()
        with closing(sqlite3.connect(Path(staging).resolve().as_uri()+"?mode=ro&immutable=1",
                             uri=True)) as verify:
            integrity=verify.execute("PRAGMA integrity_check").fetchone()[0]
            if integrity!="ok":
                raise ValueError("SQLITE_BACKUP_INTEGRITY_FAILED")
            verified_counts=_counts(verify)
        if source_counts!=verified_counts:
            raise ValueError("SOURCE_COUNTS_DIFFERED_FROM_SNAPSHOT")
        os.chmod(staging,0o600)
        with open(staging,"rb") as handle:
            os.fsync(handle.fileno())
        backup_hash=_hash_file(Path(staging))
        record={
            "schema_version":1,
            "kind":"OWNER_AUTHORIZED_SQLITE_BACKUP",
            "source_mainfile_wal_header":wal_mode,
            "standard_readonly_query_succeeded":True,
            "backup_api_completed":True,
            "backup_integrity_check":"ok",
            "backup_sha256":backup_hash,
            "verified_table_counts":verified_counts,
            "private_archive_only":True,
            "published":False,
        }
        # Atomic no-overwrite output; manifest is private proof of this copy.
        os.link(staging,target)
        write_private_json(manifest,record)
        return record
    finally:
        if staging and os.path.exists(staging):
            os.unlink(staging)
        os.umask(old_umask)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source",type=Path,required=True)
    parser.add_argument("--target",type=Path,required=True)
    parser.add_argument("--manifest",type=Path,required=True)
    args=parser.parse_args()
    report=backup_owner_authorized(args.source,args.target,args.manifest)
    print(json.dumps({"private_backup_completed":True,
                      "backup_integrity":report["backup_integrity_check"],
                      "deployed":False}))


if __name__=="__main__":
    main()
