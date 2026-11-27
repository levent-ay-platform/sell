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


def catalogue_identities(db):
    """Retain resource identities, existing hash associations and document IDs.

    A previously unhashed resource can gain a hash without a regression.
    An existing association that disappears is held for reconciliation, not
    automatically restored or assumed corrupt. No URL or text is reported.
    """
    with closing(open_snapshot(db)) as conn:
        conn.execute("PRAGMA query_only=ON")
        resources = list(conn.execute("SELECT id,sha256 FROM resources"))
        documents = Counter(row[0] for row in conn.execute("SELECT sha256 FROM documents"))
        page_documents = {row[0] for row in conn.execute("SELECT DISTINCT sha256 FROM pages")}
    resource_ids = Counter(row[0] for row in resources)
    known_associations = Counter((identifier,sha) for identifier,sha in resources if sha)
    orphan_page_documents = page_documents - documents.keys()
    return resource_ids, known_associations, documents, len(orphan_page_documents)


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


def reconcile_inventory(backlog, inventory):
    """Compare the existing scanner report with the current catalogue.

    Verify report accounting, not file bytes a second time. Paths and source
    identities remain private; missing means not observed in this scan.
    Legacy or unrelated artifacts cannot pass archive reconciliation.
    """
    result = {"state":"HOLD_UNRECOGNIZED_INVENTORY", "catalogue_identity_matched":False,
              "report_accounting_verified":False, "file_bytes_rechecked":False,
              "global_archive_completeness_verified":False,
              "counts":{}}
    if (not isinstance(inventory,dict) or inventory.get("kind") != "ATLAS_PRIVATE_PDF_BLOB_INVENTORY_ONLY"
            or inventory.get("schema_version") != 1):
        return result

    def require(condition):
        if not condition:
            raise ValueError("INVENTORY_ACCOUNTING_MISMATCH")

    def normalized(sha):
        require(isinstance(sha,str) and re.fullmatch(r"[a-fA-F0-9]{64}",sha) is not None)
        return sha.lower()

    def identifiers(rows):
        require(isinstance(rows,list) and all(v is None or isinstance(v,str) for v in rows))
        return Counter(rows)

    expected = {}
    for row in backlog["backlog_private"] + backlog["text_ready_private"]:
        sha = normalized(row["document_sha256_private"])
        require(sha not in expected)
        expected[sha] = identifiers(row["resource_ids_private"])

    observed = {}
    missing = no_header = files = bytes_hashed = matched = with_header = 0
    paths = set()
    def check_file(item):
        require(isinstance(item,dict))
        path = item.get("relative_path_private")
        require(isinstance(path,str) and path and not Path(path).is_absolute()
                and ".." not in Path(path).parts and path not in paths)
        require(type(item.get("bytes")) is int and item["bytes"] >= 0
                and type(item.get("pdf_header_hint")) is bool)
        paths.add(path)
        return item["bytes"]

    rows = inventory.get("catalogue_private")
    require(isinstance(rows,list))
    for row in rows:
        require(isinstance(row,dict))
        sha = normalized(row.get("document_sha256_private"))
        require(sha not in observed)
        refs = identifiers(row.get("resource_ids_private"))
        require(type(row.get("resource_rows")) is int and row["resource_rows"] == sum(refs.values()) > 0)
        observed[sha] = refs
        locations = row.get("matching_files")
        require(isinstance(locations,list))
        for item in locations:
            bytes_hashed += check_file(item)
        headers = sum(item["pdf_header_hint"] for item in locations)
        require(row.get("sha256_matching_file_count") == len(locations)
                and row.get("matching_pdf_header_hint_count") == headers)
        status = ("HASH_AND_PDF_HEADER_OBSERVED" if headers else
                  "HASH_OBSERVED_NOT_PDF_HEADER" if locations else "NOT_OBSERVED_IN_THIS_SCAN")
        require(row.get("blob_status") == status)
        missing += not locations
        no_header += bool(locations) and not headers
        matched += bool(locations)
        with_header += bool(headers)
        files += len(locations)

    unmatched = inventory.get("unmatched_local_pdf_files_private")
    errors = inventory.get("scan_errors_private")
    require(isinstance(unmatched,list) and isinstance(errors,list))
    for item in unmatched:
        bytes_hashed += check_file(item)
        require(normalized(item.get("sha256_private")) not in observed)
    files += len(unmatched)
    unknown = identifiers(inventory.get("unhashed_pdf_resource_ids_private"))
    counts = inventory.get("counts")
    require(isinstance(counts,dict))
    recomputed = {
        "catalogue_distinct_hashed_pdfs_preserved":len(observed),
        "catalogue_pdf_rows_without_sha_preserved":sum(unknown.values()),
        "catalogue_resource_references_preserved":sum(sum(ids.values()) for ids in observed.values()),
        "catalogue_hashes_with_matching_bytes":matched,
        "catalogue_hashes_with_pdf_header_hint":with_header,
        "local_pdf_files_hashed":files,
        "local_pdf_bytes_hashed":bytes_hashed,
        "local_pdf_files_unmatched_to_catalogue":len(unmatched),
        "local_pdf_files_skipped_or_unreadable":len(errors),
    }
    require(all(type(counts.get(key)) is int and counts[key]==value for key,value in recomputed.items()))
    skips = [counts.get(k) for k in ("symlink_dirs_skipped","symlink_files_skipped")]
    require(all(type(v) is int and v>=0 for v in skips))
    scan_complete = not errors and not any(skips)
    require(inventory.get("scan_complete_within_selected_root") is scan_complete)
    identity_matched = (expected == observed and unknown == identifiers(backlog["unhashed_pdf_resource_ids_private"]))
    result.update({
        "catalogue_identity_matched":identity_matched,"report_accounting_verified":True,
        "selected_root_scan_reported_complete":scan_complete,
        "counts":{**recomputed,"hashes_not_observed":missing,
                  "matching_hashes_without_pdf_header":no_header,
                  "catalogue_hashes_missing_from_inventory":len(expected.keys()-observed.keys()),
                  "inventory_hashes_not_in_catalogue":len(observed.keys()-expected.keys()),
                  "hashes_with_changed_source_associations":sum(expected[k]!=observed[k] for k in expected.keys() & observed.keys())},
    })
    result["state"] = ("HOLD_INVENTORY_CATALOGUE_MISMATCH" if not identity_matched else
                       "HOLD_INVENTORY_SCOPE_INCOMPLETE" if not scan_complete else
                       "PRIVATE_ARCHIVE_RECONCILIATION_REQUIRED" if missing or no_header or unmatched or unknown else
                       "PRIVATE_SELECTED_ROOT_PDF_REPORT_ACCOUNTED")
    return result


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
    old_ids, old_links, old_docs, _ = catalogue_identities(baseline[0])
    new_ids, new_links, new_docs, orphan_page_documents = catalogue_identities(current[0])
    if (sum(old_ids.values()) != baseline[4]["resources"]
            or sum(new_ids.values()) != current[4]["resources"]
            or sum(old_docs.values()) != baseline[4]["documents"]
            or sum(new_docs.values()) != current[4]["documents"]):
        raise ValueError("CATALOGUE_IDENTITY_ACCOUNTING_MISMATCH")
    missing_resources = sum((old_ids - new_ids).values())
    missing_associations = sum((old_links - new_links).values())
    missing_documents = sum((old_docs - new_docs).values())
    backlog = build_backlog(current[0], "transactional_backup")
    ledger = delta_ledger(backlog)
    try:
        with inventory.open(encoding="utf-8") as stream:
            archive_report = reconcile_inventory(backlog,json.load(stream))
    except (ValueError, TypeError, KeyError) as exc:
        # Preserve the baseline comparison even if inventory evidence is invalid.
        archive_report = {"state":"HOLD_INVALID_INVENTORY_REPORT",
                          "catalogue_identity_matched":False,"report_accounting_verified":False,
                          "file_bytes_rechecked":False,"global_archive_completeness_verified":False,
                          "counts":{}}
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
    state = ("HOLD_BASELINE_REGRESSION" if any((removed,missing_resources,missing_associations,missing_documents)) else
             "PRIVATE_DELTA_REVIEW_REQUIRED" if ledger or backlog["counts"]["pdf_rows_without_sha"] or orphan_page_documents
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
                  "baseline_resource_rows":baseline[4]["resources"],
                  "current_resource_rows":current[4]["resources"],
                  "missing_baseline_resource_id_rows":missing_resources,
                  "missing_baseline_resource_hash_associations":missing_associations,
                  "missing_baseline_document_identity_rows":missing_documents,
                  "page_document_identities_without_document_row":orphan_page_documents,
                  "documents_requiring_review":len(ledger),
                  "unhashed_pdf_resource_rows":backlog["counts"]["pdf_rows_without_sha"],
                  "delta_states":dict(Counter(row["state"] for row in ledger)),
                  "document_coverage_states":backlog["counts"]["page_coverage_states"]},
        "delta_ledger_private":ledger,
        "unhashed_pdf_resource_ids_private":backlog["unhashed_pdf_resource_ids_private"],
        "archive_inventory_semantics_verified":archive_report["report_accounting_verified"] and archive_report["catalogue_identity_matched"],
        "archive_reconciliation_private":archive_report,
        "combined_review_state":("HOLD_BASELINE_REGRESSION" if state == "HOLD_BASELINE_REGRESSION" else
                                 "PRIVATE_TEXT_AND_SELECTED_ROOT_PDF_ACCOUNTED" if state == "PRIVATE_TEXT_COVERAGE_ACCOUNTED" and archive_report["state"] == "PRIVATE_SELECTED_ROOT_PDF_REPORT_ACCOUNTED"
                                 else "PRIVATE_REVIEW_REQUIRED"),
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
    print(json.dumps({"state":report["state"],"combined_review_state":report["combined_review_state"],
                      "archive_state":report["archive_reconciliation_private"]["state"],
                      "counts":report["counts"],"production_changed":False}))
    return 2 if report["state"] == "HOLD_BASELINE_REGRESSION" else 0


if __name__ == "__main__":
    raise SystemExit(main())
