#!/usr/bin/env python3
"""Bind an offline Atlas generation to proven snapshots, preserving baseline rows.

This is a private acceptance and scheduling ledger, not a live reader, extractor,
publisher, or authorization mechanism. The caller must supply owner-exported
standalone backups and the exact archive inventory artifact.
"""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3

from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_ocr_backlog import build_backlog
from scripts.atlas_sqlite_snapshot import _counts, _hash_file


def private_file(path):
    path = Path(path)
    if (not path.is_file() or path.is_symlink()
            or any(parent.is_symlink() for parent in path.parents)
            or path.stat().st_mode & 0o077):
        raise ValueError("PRIVATE_REGULAR_INPUT_REQUIRED")
    return path


def standalone(path):
    if any(Path(str(path) + suffix).exists() or Path(str(path) + suffix).is_symlink()
           for suffix in ("-wal", "-shm", "-journal")):
        raise ValueError("STANDALONE_SNAPSHOT_REQUIRED")
    with path.open("rb") as item:
        header = item.read(20)
    if (len(header) != 20 or header[:16] != b"SQLite format 3\x00"
            or header[18:20] != b"\x01\x01"):
        raise ValueError("STANDALONE_SNAPSHOT_REQUIRED")


def open_snapshot(path):
    return sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=2.0)


def verified_snapshot(db, proof):
    db, proof = private_file(db), private_file(proof)
    standalone(db)
    proof_hash = _hash_file(proof)
    with proof.open(encoding="utf-8") as stream:
        record = json.load(stream)
    digest = _hash_file(db)
    if (record.get("kind") != "OWNER_AUTHORIZED_SQLITE_BACKUP"
            or record.get("schema_version") != 1
            or record.get("backup_api_completed") is not True
            or record.get("source_snapshot_counts_pinned") is not True
            or record.get("backup_integrity_check") != "ok"
            or record.get("private_archive_only") is not True
            or record.get("published") is not False
            or record.get("source_connection_mode") not in ("existing_producer", "external_readonly")
            or record.get("backup_sha256") != digest):
        raise ValueError("BACKUP_PROOF_MISMATCH")
    with closing(open_snapshot(db)) as conn:
        conn.execute("PRAGMA query_only=ON")
        if conn.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            raise ValueError("SNAPSHOT_INTEGRITY_FAILED")
        counts = _counts(conn)
        if counts != record.get("verified_table_counts") or not {"resources", "documents", "pages"} <= counts.keys():
            raise ValueError("SNAPSHOT_TABLE_ACCOUNTING_MISMATCH")
    return db, proof, digest, proof_hash, counts


def page_rows(db):
    # Preserve multiplicity and exact content, including blank/invalid rows.
    # Digests are internal comparison keys and are never included in reports.
    rows = Counter()
    with closing(open_snapshot(db)) as conn:
        conn.execute("PRAGMA query_only=ON")
        for sha, page, content in conn.execute("SELECT sha256,page,text FROM pages"):
            def encode(value):
                if isinstance(value, bytes):
                    return ["bytes", value.hex()]
                return [type(value).__name__, value]
            key = hashlib.sha256(json.dumps([encode(sha),encode(page),encode(content)],
                ensure_ascii=False, separators=(",",":"), allow_nan=False).encode()).digest()
            rows[key] += 1
    return rows


def delta_ledger(backlog):
    ledger = []
    for row in backlog["backlog_private"]:
        coverage = row["coverage_state"]
        if coverage == "CONFLICT_REVIEW" or row["indexed_state"] == "processing_failed":
            state = "HOLD_CONFLICT_OR_FAILURE"
        elif row["expected_pages"] is None:
            state = "HOLD_EXPECTED_PAGE_COUNT_UNKNOWN"
        else:
            state = "REVIEW_KNOWN_PAGE_GAPS"
        ledger.append({
            "document_sha256_private": row["document_sha256_private"],
            "state": state,
            "expected_pages": row["expected_pages"],
            "existing_text_pages": row["observed_text_page_ids"],
            "missing_ranges_inclusive": row["missing_page_ranges_inclusive"],
            "blank_pages": row["observed_no_text_page_ids"],
            "conflicting_pages": row["conflicting_page_ids"],
            "out_of_range_pages": row["out_of_range_page_ids"],
            "invalid_page_rows": row["invalid_page_identity_rows"],
            "extraction_authorized": False,
        })
    return ledger


def build_generation(baseline_db, baseline_proof, current_db, current_proof,
                     inventory, *, inventory_sha256, extractor_revision, minimum_baseline_pages):
    if (not isinstance(extractor_revision, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", extractor_revision)
            or type(minimum_baseline_pages) is not int or minimum_baseline_pages < 0
            or not isinstance(inventory_sha256,str) or not re.fullmatch(r"[a-f0-9]{64}",inventory_sha256)):
        raise ValueError("EXPLICIT_GENERATION_BINDINGS_REQUIRED")
    inventory = private_file(inventory)
    if _hash_file(inventory) != inventory_sha256:
        raise ValueError("ARCHIVE_INVENTORY_HASH_MISMATCH")
    baseline = verified_snapshot(baseline_db, baseline_proof)
    current = verified_snapshot(current_db, current_proof)
    if baseline[4]["pages"] < minimum_baseline_pages:
        raise ValueError("BASELINE_PAGE_FLOOR_NOT_MET")
    previous, present = page_rows(baseline[0]), page_rows(current[0])
    if sum(previous.values()) != baseline[4]["pages"] or sum(present.values()) != current[4]["pages"]:
        raise ValueError("PAGE_ROW_ACCOUNTING_MISMATCH")
    removed = sum((previous - present).values())
    added = sum((present - previous).values())
    backlog = build_backlog(current[0], "transactional_backup")
    ledger = delta_ledger(backlog)
    if len(ledger) != backlog["counts"]["backlog_documents"]:
        raise ValueError("DELTA_LEDGER_ACCOUNTING_MISMATCH")
    # Recheck every artifact after all reads; a proof is not a lock.
    for db, proof, digest, proof_hash, _ in (baseline,current):
        standalone(db)
        if _hash_file(db) != digest or _hash_file(proof) != proof_hash:
            raise ValueError("GENERATION_INPUT_CHANGED")
    if _hash_file(inventory) != inventory_sha256:
        raise ValueError("GENERATION_INPUT_CHANGED")
    # A disappearing or changed historical row requires explicit reconciliation.
    state = ("HOLD_BASELINE_REGRESSION" if removed else
             "PRIVATE_DELTA_REVIEW_REQUIRED" if ledger or backlog["counts"]["pdf_rows_without_sha"]
             else "PRIVATE_TEXT_COVERAGE_ACCOUNTED")
    bindings = {"baseline_backup_sha256":baseline[2], "baseline_proof_sha256":baseline[3],
                "current_backup_sha256":current[2], "current_proof_sha256":current[3],
                "archive_inventory_sha256":inventory_sha256, "extractor_revision":extractor_revision,
                "minimum_baseline_pages":minimum_baseline_pages}
    generation_id = hashlib.sha256(json.dumps(bindings,sort_keys=True,separators=(",",":")).encode()).hexdigest()
    return {
        "kind":"PRIVATE_ATLAS_GENERATION_REVIEW", "schema_version":1,
        "generation_id_private":generation_id, "bindings_private":bindings,
        "state":state,
        "counts":{"baseline_page_rows":baseline[4]["pages"],"current_page_rows":current[4]["pages"],
                  "removed_or_changed_baseline_rows":removed,"added_or_changed_rows":added,
                  "retained_baseline_rows":baseline[4]["pages"]-removed,
                  "documents_requiring_review":len(ledger),
                  "unhashed_pdf_resource_rows":backlog["counts"]["pdf_rows_without_sha"],
                  "delta_states":dict(Counter(row["state"] for row in ledger)),
                  "document_coverage_states":backlog["counts"]["page_coverage_states"]},
        "delta_ledger_private":ledger,
        "unhashed_pdf_resource_ids_private":backlog["unhashed_pdf_resource_ids_private"],
        "archive_inventory_semantics_verified":False,
        "extractor_revision_declared_not_attested":True,
        "same_catalogue_identity_operator_responsibility":True,
        "live_service_acceptance":False,"official_plan_verification":None,
        "extraction_authorized":False,"public_export_permitted":False,"production_changed":False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("baseline-db","baseline-proof","current-db","current-proof","inventory","output"):
        parser.add_argument("--"+name,type=Path,required=True)
    parser.add_argument("--inventory-sha256",required=True)
    parser.add_argument("--extractor-revision",required=True)
    parser.add_argument("--minimum-baseline-pages",type=int,required=True)
    a = parser.parse_args()
    report = build_generation(a.baseline_db,a.baseline_proof,a.current_db,a.current_proof,a.inventory,
        inventory_sha256=a.inventory_sha256,extractor_revision=a.extractor_revision,
        minimum_baseline_pages=a.minimum_baseline_pages)
    write_private_json(a.output,report)
    print(json.dumps({"state":report["state"],"counts":report["counts"],"production_changed":False}))
    return 2 if report["state"] == "HOLD_BASELINE_REGRESSION" else 0


if __name__ == "__main__":
    raise SystemExit(main())
