#!/usr/bin/env python3
"""Owner-operated private Atlas archive review batch, no database/source writes.

A single invocation inventories stable catalogue + approved archive roots, then
processes a bounded priority window using existing vector and optional OCR
workers. No legal decision, geolocation, backend import, or publication.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from scripts.atlas_cadastre_evidence_audit import (
    CADASTRAL_WORDS, FAMILY_PATTERNS, PARCEL_REF, TITLE_REF,
    audit_snapshot, references, write_private_json,
)
from scripts.atlas_ocr_backlog import build_backlog
from scripts.atlas_pdf_blob_inventory import scan_pdf_blobs
from scripts.atlas_pdf_vector_candidates import MAX_PDF_BYTES

REPO = Path(__file__).resolve().parents[1]
OCR_WORKER = REPO / "services/verification/ocr/ingest.py"


def _digest(path):
    h = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def _snapshot_proof(db, kind, manifest):
    if db.is_symlink() or not db.is_file():
        raise ValueError("STABLE_SNAPSHOT_REQUIRED")
    sha = _digest(db)
    if kind == "transactional_backup":
        if manifest is None or manifest.is_symlink() or not manifest.is_file():
            raise ValueError("OWNER_BACKUP_MANIFEST_REQUIRED")
        proof = json.loads(manifest.read_text(encoding="utf-8"))
        if (proof.get("kind") != "OWNER_AUTHORIZED_SQLITE_BACKUP" or
                proof.get("backup_api_completed") is not True or
                proof.get("standard_readonly_query_succeeded") is not True or
                proof.get("backup_integrity_check") != "ok" or
                proof.get("backup_sha256") != sha):
            raise ValueError("OWNER_BACKUP_MANIFEST_MISMATCH")
    elif kind != "mainfile_diagnostic":
        raise ValueError("EXPLICIT_SNAPSHOT_KIND_REQUIRED")
    return sha


def _approved_roots(roots, output, db):
    result = []
    for root in roots:
        if root.is_symlink() or not root.is_dir():
            raise ValueError("APPROVED_ARCHIVE_ROOT_REQUIRED")
        root = root.resolve(strict=True)
        if any(root == prior or root.is_relative_to(prior) or prior.is_relative_to(root)
               for prior in result):
            raise ValueError("ARCHIVE_ROOTS_MUST_NOT_OVERLAP")
        if output == root or output.is_relative_to(root):
            raise ValueError("OUTPUT_MUST_BE_OUTSIDE_SOURCE_ROOTS")
        result.append(root)
    if not result:
        raise ValueError("APPROVED_ARCHIVE_ROOT_REQUIRED")
    if output == db or db.is_relative_to(output):
        raise ValueError("OUTPUT_MUST_NOT_CONTAIN_SNAPSHOT")
    return result


def _safe_relative(root, relative):
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise ValueError("INVALID_PRIVATE_RELATIVE_PATH")
    current = root
    for name in path.parts:
        current = current / name
        if current.is_symlink():
            raise ValueError("SYMLINK_REVIEW_REQUIRED")
    if not current.is_file() or not current.resolve().is_relative_to(root):
        raise ValueError("ARCHIVE_FILE_UNAVAILABLE")
    return current


def _stage_verified(source, destination, expected):
    # Stage at most 64 MiB in a private work directory; rehash at point of use.
    h = hashlib.sha256()
    total = 0
    with source.open("rb") as src, destination.open("xb") as dst:
        os.chmod(destination, 0o600)
        while chunk := src.read(1024*1024):
            total += len(chunk)
            if total > MAX_PDF_BYTES:
                raise ValueError("PDF_BYTE_LIMIT")
            h.update(chunk)
            dst.write(chunk)
    if h.hexdigest() != expected:
        raise ValueError("PDF_CHANGED_AFTER_INVENTORY")
    return total


def _run_child(command, seconds):
    # No child stdout/stderr, private path or OCR text is printed to the operator.
    # The existing workers are bounded, and the operator must provide OS isolation.
    try:
        result = subprocess.run(command, cwd=REPO, capture_output=True,
                                timeout=seconds, check=False)
    except subprocess.TimeoutExpired:
        return None, "WORKER_TIMEOUT"
    except OSError:
        return None, "WORKER_UNAVAILABLE"
    if result.returncode:
        return None, "WORKER_REJECTED_INPUT_OR_FAILED"
    try:
        value = json.loads(result.stdout)
        if not isinstance(value, dict):
            raise ValueError("WORKER_JSON_OBJECT_REQUIRED")
        return value, "COMPLETED"
    except (ValueError, UnicodeError):
        return None, "WORKER_INVALID_OUTPUT"


def _ocr_page_evidence(result):
    pages = []
    for page in result.get("pages", []):
        lines = page.get("lines", [])
        text = "\n".join(row.get("text", "") for row in lines
                         if isinstance(row, dict) and isinstance(row.get("text"), str))
        pages.append({
            "page": page.get("page"),
            "method": page.get("method"),
            "text_present": bool(text.strip()),
            "cadastral_term_present": bool(CADASTRAL_WORDS.search(text)),
            "family_hints": sorted(key for key, pattern in FAMILY_PATTERNS.items()
                                   if pattern.search(text)),
            "title_reference_candidates": sorted(references(TITLE_REF, text)),
            "parcel_reference_candidates": sorted(references(PARCEL_REF, text)),
            "raster_outline_candidate_private": page.get("parcelPlanScan"),
        })
    return pages


def run_batch(db, kind, roots, output, *, manifest=None, offset=0,
              max_documents=30, ocr=False, timeout_seconds=180):
    if type(offset) is not int or offset < 0 or not 1 <= max_documents <= 100:
        raise ValueError("BOUNDED_BATCH_WINDOW_REQUIRED")
    if not 10 <= timeout_seconds <= 300:
        raise ValueError("BOUNDED_WORKER_TIMEOUT_REQUIRED")
    db, output = Path(db), Path(output)
    if output.exists() or output.is_symlink():
        raise FileExistsError("NEW_PRIVATE_RUN_DIRECTORY_REQUIRED")
    if any(parent.is_symlink() for parent in output.parents):
        raise ValueError("SYMLINK_OUTPUT_DIRECTORY")
    roots = _approved_roots([Path(root) for root in roots], output.resolve(), db.resolve())
    manifest = Path(manifest) if manifest is not None else None
    snapshot_hash = _snapshot_proof(db, kind, manifest)
    audit = audit_snapshot(db, snapshot_kind=kind)
    backlog = build_backlog(db, kind)
    if _digest(db) != snapshot_hash:
        raise ValueError("SNAPSHOT_CHANGED_DURING_INVENTORY")
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    os.chmod(output, 0o700)
    write_private_json(output / "audit.json", audit)
    write_private_json(output / "backlog.json", backlog)
    locations = {}
    root_summaries = []
    for index, root in enumerate(roots):
        report = scan_pdf_blobs(output / "backlog.json", root)
        write_private_json(output / f"root-{index+1:02d}-blobs.json", report)
        root_summaries.append({"root_number": index+1, "counts": report["counts"],
                               "scan_complete": report["scan_complete_within_selected_root"]})
        for row in report["catalogue_private"]:
            sha = row["document_sha256_private"]
            locations.setdefault(sha, []).extend(
                (index, match["relative_path_private"]) for match in row["matching_files"]
                if match["pdf_header_hint"])

    queue = sorted(backlog["backlog_private"] + backlog["text_ready_private"],
                   key=lambda row: (-row["priority_score"], row["document_sha256_private"]))
    eligible = [row for row in queue if locations.get(row["document_sha256_private"].lower())]
    chosen = eligible[offset:offset+max_documents]
    chosen_keys = {row["document_sha256_private"].lower() for row in chosen}
    status = {}
    for row in queue:
        key = row["document_sha256_private"].lower()
        status[key] = ("NOT_OBSERVED_WITH_PDF_HEADER" if not locations.get(key)
                       else "DEFERRED_TO_ANOTHER_BATCH")

    results_dir = output / "documents"
    results_dir.mkdir(mode=0o700)
    for index, row in enumerate(chosen, start=offset+1):
        sha = row["document_sha256_private"].lower()
        entry = {
            "document_sha256_private": sha,
            "resource_ids_private": row["resource_ids_private"],
            "priority_score": row["priority_score"],
            "official_plan_verified": None,
            "ground_verified": False,
            "vector_status": "NOT_RUN",
            "ocr_status": "NOT_REQUESTED" if not ocr else "NOT_RUN",
            "page_reference_evidence_private": [],
        }
        with tempfile.TemporaryDirectory(prefix="work-", dir=output) as tmp:
            copy = Path(tmp) / "verified.pdf"
            verified = False
            # Try duplicates if a previously observed path is no longer readable.
            for root_index, relative in locations[sha]:
                try:
                    source = _safe_relative(roots[root_index], relative)
                    _stage_verified(source, copy, sha)
                    verified = True
                    break
                except (OSError, ValueError):
                    copy.unlink(missing_ok=True)
            if not verified:
                entry["status"] = "BLOB_RECHECK_FAILED"
            else:
                vector_file = Path(tmp) / "vector.json"
                vector_result, vector_status = _run_child(
                    [sys.executable, "-m", "scripts.atlas_pdf_vector_candidates",
                     "--pdf", str(copy), "--expected-sha256", sha,
                     "--output", str(vector_file)], timeout_seconds)
                entry["vector_status"] = vector_status
                if vector_result is not None and vector_file.is_file():
                    vector = json.loads(vector_file.read_text(encoding="utf-8"))
                    if (vector.get("document_sha256_private") == sha and
                            vector.get("ground_verified") is False):
                        entry["vector_evidence_private"] = vector
                    else:
                        entry["vector_status"] = "VECTOR_EVIDENCE_BINDING_FAILED"
                elif vector_result is not None:
                    entry["vector_status"] = "VECTOR_EVIDENCE_MISSING"
                if ocr:
                    ocr_result, ocr_status = _run_child(
                        [sys.executable, str(OCR_WORKER), str(copy), "--mode", "auto"],
                        timeout_seconds)
                    entry["ocr_status"] = ocr_status
                    if ocr_result is not None and ocr_result.get("mayApproveOwnership") is False:
                        entry["page_reference_evidence_private"] = _ocr_page_evidence(ocr_result)
                    elif ocr_result is not None:
                        entry["ocr_status"] = "OCR_EVIDENCE_CONTRACT_FAILED"
                vector_ok = entry["vector_status"] == "COMPLETED"
                ocr_ok = entry["ocr_status"] == "COMPLETED"
                entry["status"] = ("PRIVATE_REVIEW_REQUIRED" if vector_ok or ocr_ok
                                   else "EXTRACTION_FAILED_RETAINED")
        status[sha] = entry["status"]
        write_private_json(results_dir / f"{index:06d}.json", entry)

    if len(status) != len(queue) or len(chosen_keys) != len(chosen):
        raise AssertionError("CATALOGUE_ACCOUNTING_MISMATCH")
    if _digest(db) != snapshot_hash:
        raise ValueError("SNAPSHOT_CHANGED_DURING_BATCH_NO_FINAL_MANIFEST")
    result = {
        "schema_version": 1,
        "kind": "ATLAS_PRIVATE_ARCHIVE_REVIEW_BATCH",
        "snapshot_kind": kind,
        "snapshot_sha256_private": snapshot_hash,
        "owner_backup_manifest_digest_matched": kind == "transactional_backup",
        "root_summaries": root_summaries,
        "counts": {
            "catalogue_hashed_pdfs": len(queue),
            "catalogue_unhashed_rows": backlog["counts"]["pdf_rows_without_sha"],
            "eligible_pdf_hashes_in_selected_roots": len(eligible),
            "batch_selected_hashes": len(chosen),
            "states": dict(sorted(Counter(status.values()).items())),
        },
        "batch_offset": offset,
        "next_offset": offset+len(chosen) if offset+len(chosen) < len(eligible) else None,
        "all_catalogue_states_private": status,
        "official_plan_count": None,
        "geographic_parcel_count": None,
        "source_modified": False,
        "public_export_permitted": False,
        "limitations": [
            "Stable owner-approved snapshot and archive roots only; no claim of archive-wide coverage.",
            "Each new offset is meaningful only against an unchanged snapshot and root inventory.",
            "OCR service input limits and missing dependencies may leave documents for manual review.",
            "Review issuer, exact title/parcel evidence, rights and independent ground controls separately.",
        ],
    }
    write_private_json(output / "completed-private-manifest.json", result)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--db", required=True, type=Path)
    p.add_argument("--snapshot-kind", required=True, choices=["mainfile_diagnostic", "transactional_backup"])
    p.add_argument("--backup-manifest", type=Path)
    p.add_argument("--archive-root", required=True, action="append", type=Path)
    p.add_argument("--output-directory", required=True, type=Path)
    p.add_argument("--offset", type=int, default=0)
    p.add_argument("--max-documents", type=int, default=30)
    p.add_argument("--ocr", action="store_true")
    p.add_argument("--timeout-seconds", type=int, default=180)
    a = p.parse_args()
    report = run_batch(a.db, a.snapshot_kind, a.archive_root, a.output_directory,
                       manifest=a.backup_manifest, offset=a.offset,
                       max_documents=a.max_documents, ocr=a.ocr,
                       timeout_seconds=a.timeout_seconds)
    print(json.dumps({"private_batch_completed": True, "counts": report["counts"],
                      "next_offset": report["next_offset"],
                      "official_plans_verified": "UNKNOWN"}))


if __name__ == "__main__":
    main()
