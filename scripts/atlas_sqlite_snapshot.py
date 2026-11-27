#!/usr/bin/env python3
"""Operator-only, no-repair consistent SQLite backup using Backup API.

Never falls back to immutable=1 for a live database. Refuses WAL-oriented
sources with missing sidecars to avoid silently fabricating -shm/-wal on the
source. Requires an authorized file-owner context and a source that can be
opened in standard mode=ro. The existing authorized catalogue producer may
export through its own idle connection instead. No source checkpoint,
journal-mode change, or migration.
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
import time

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


def _backup_connection(read, source, target, manifest, *, wal_mode, mode, timeout_seconds=180):
    """Create a consistent export using the caller's already-authorized connection.

    The source connection is borrowed, not closed or committed. The caller
    must serialize this invocation with its own connection operations.
    """
    source, target, manifest = Path(source), Path(target), Path(manifest)
    if read.in_transaction:
        raise RuntimeError("SOURCE_TRANSACTION_ALREADY_ACTIVE")
    if source.resolve() in (target.resolve(),manifest.resolve()):
        raise ValueError("SOURCE_MUST_NOT_BE_OUTPUT")
    if target.resolve()==manifest.resolve() or target.exists() or target.is_symlink() or manifest.exists() or manifest.is_symlink():
        raise FileExistsError("REFUSING_TO_OVERWRITE_BACKUP_OR_MANIFEST")
    for parent in (target.parent,manifest.parent):
        if parent.exists() and parent.is_symlink():
            raise ValueError("SYMLINK_OUTPUT_DIRECTORY")
        parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    if not 0 < timeout_seconds <= 3600:
        raise ValueError("INVALID_BACKUP_TIMEOUT")
    deadline = time.monotonic() + timeout_seconds
    def progress(status, remaining, total):
        if time.monotonic() >= deadline:
            raise TimeoutError("SQLITE_BACKUP_DEADLINE")
    staging = None
    try:
        fd, staging = tempfile.mkstemp(prefix=".atlas-backup-", dir=str(target.parent))
        os.close(fd)
        # Hold the same read snapshot for counts and Backup API; otherwise
        # a concurrent producer commit can invalidate the comparison.
        read.execute("BEGIN")
        try:
            source_counts = _counts(read)
            with closing(sqlite3.connect(staging, timeout=2.0)) as write:
                read.backup(write, pages=128, sleep=0.02, progress=progress)
                write.commit()
                # Only the disposable destination is changed to a standalone
                # rollback-journal format, never the live source.
                if write.execute("PRAGMA journal_mode=DELETE").fetchone()[0].lower() != "delete":
                    raise RuntimeError("DESTINATION_NOT_STANDALONE")
        finally:
            read.rollback()
        for suffix in ("-wal","-shm","-journal"):
            if Path(staging+suffix).exists():
                raise RuntimeError("DESTINATION_SIDECAR_REMAINS")
        with closing(sqlite3.connect(Path(staging).resolve().as_uri()+"?mode=ro", uri=True)) as verify:
            verify.execute("PRAGMA query_only=ON")
            integrity=verify.execute("PRAGMA integrity_check").fetchone()[0]
            if integrity!="ok":
                raise ValueError("SQLITE_BACKUP_INTEGRITY_FAILED")
            verified_counts=_counts(verify)
        if source_counts!=verified_counts:
            raise ValueError("SOURCE_COUNTS_DIFFERED_FROM_SNAPSHOT")
        os.chmod(staging,0o600)
        with open(staging,"rb") as handle:
            os.fsync(handle.fileno())
        record={
            "schema_version":1,
            "kind":"OWNER_AUTHORIZED_SQLITE_BACKUP",
            "source_mainfile_wal_header":wal_mode,
            "standard_readonly_query_succeeded":mode=="external_readonly",
            "source_connection_mode":mode,
            "source_snapshot_counts_pinned":True,
            "backup_api_completed":True,
            "backup_integrity_check":"ok",
            "backup_sha256":_hash_file(Path(staging)),
            "verified_table_counts":verified_counts,
            "private_archive_only":True,
            "published":False,
        }
        os.link(staging,target)
        try:
            write_private_json(manifest,record)
        except Exception:
            # Never leave an apparently complete backup without its proof.
            # Only remove our own hard link; never touch a replacement file.
            if target.exists() and os.path.samestat(os.stat(staging),os.lstat(target)):
                target.unlink()
            raise
        return record
    finally:
        if staging:
            for suffix in ("","-wal","-shm","-journal"):
                item=Path(staging+suffix)
                if item.exists():
                    item.unlink()


def backup_from_producer_connection(connection, target, manifest, *, timeout_seconds=180):
    """Call inside the existing catalogue producer after its commit.

    Does not discover/open a live database, change its permissions, or start
    a second writer. An active producer transaction is never committed here.
    """
    if connection.in_transaction:
        raise RuntimeError("SOURCE_TRANSACTION_ALREADY_ACTIVE")
    main = next((row[2] for row in connection.execute("PRAGMA database_list")
                 if row[1]=="main"), "")
    if not main:
        raise ValueError("FILE_BACKED_PRODUCER_REQUIRED")
    source = Path(main)
    if not source.is_file() or source.is_symlink():
        raise ValueError("REGULAR_SOURCE_REQUIRED")
    wal_mode = connection.execute("PRAGMA journal_mode").fetchone()[0].lower()=="wal"
    return _backup_connection(connection,source,target,manifest,wal_mode=wal_mode,
                              mode="existing_producer",timeout_seconds=timeout_seconds)


def backup_owner_authorized(source, target, manifest):
    source, target, manifest = Path(source), Path(target), Path(manifest)
    if source.resolve() in (target.resolve(),manifest.resolve()):
        raise ValueError("SOURCE_MUST_NOT_BE_OUTPUT")
    # Retain the strict external read-only gate; the producer integration
    # is a separate API and cannot be selected by the external CLI.
    wal_mode=_check_source(source)
    with closing(sqlite3.connect(source.resolve().as_uri()+"?mode=ro",
                                 uri=True,timeout=2.0)) as read:
        read.execute("PRAGMA query_only=ON")
        return _backup_connection(read,source,target,manifest,wal_mode=wal_mode,
                                  mode="external_readonly")


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
