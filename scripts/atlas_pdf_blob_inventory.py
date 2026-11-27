#!/usr/bin/env python3
"""Private, read-only SHA-256 inventory of PDF bytes within an approved local root.

This compares *existing files* with a previously generated private OCR backlog.
It neither fetches nor modifies PDFs, and a matching hash/PDF header does NOT
prove an official cadastral plan, a legal title, or archive completeness.

This tool is intentionally independent of Atlas's operational SQLite DB.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path
import re
import stat

from scripts.atlas_cadastre_evidence_audit import write_private_json

_SHA256 = re.compile(r"[a-fA-F0-9]{64}\Z")


def _load_private_backlog(backlog: Path) -> tuple[dict[str, dict], dict]:
    if backlog.is_symlink() or not backlog.is_file():
        raise ValueError("PRIVATE_BACKLOG_FILE_REQUIRED")
    with backlog.open("r", encoding="utf-8") as handle:
        report = json.load(handle)
    if report.get("kind") != "ATLAS_PRIVATE_OCR_BACKLOG_CANDIDATES_ONLY":
        raise ValueError("UNRECOGNIZED_PRIVATE_BACKLOG")
    if report.get("snapshot_kind") not in ("mainfile_diagnostic", "transactional_backup"):
        raise ValueError("EXPLICIT_SNAPSHOT_KIND_REQUIRED")
    all_rows = report.get("backlog_private", []) + report.get("text_ready_private", [])
    if not isinstance(all_rows, list):
        raise ValueError("INVALID_BACKLOG_ROWS")
    records: dict[str, dict] = {}
    for row in all_rows:
        if not isinstance(row, dict):
            raise ValueError("INVALID_BACKLOG_ROW")
        sha = row.get("document_sha256_private")
        if not isinstance(sha, str) or not _SHA256.fullmatch(sha):
            raise ValueError("INVALID_SHA256_IN_BACKLOG")
        sha = sha.lower()
        if sha in records:
            raise ValueError("DUPLICATE_SHA256_IN_BACKLOG")
        source_count = row.get("resource_rows")
        refs = row.get("resource_ids_private")
        if not isinstance(refs, list) or type(source_count) is not int or source_count != len(refs) or source_count < 1:
            raise ValueError("SOURCE_ASSOCIATIONS_NOT_LOSSLESS")
        records[sha] = {"resource_rows": source_count, "resource_ids_private": refs,
                        "required_action": row.get("required_action")}
    if report.get("counts", {}).get("distinct_hashed_pdfs") != len(records):
        raise ValueError("BACKLOG_HASH_COUNT_MISMATCH")
    if report.get("counts", {}).get("pdf_rows_without_sha") != len(
            report.get("unhashed_pdf_resource_ids_private", [])):
        raise ValueError("UNHASHED_RESOURCES_NOT_ACCOUNTED_FOR")
    return records, report


def _hash_file(path: Path, *, maximum: int) -> dict:
    """Open without following final symlinks; detect file changes while reading."""
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    fd = os.open(path, flags)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            return {"status": "NOT_REGULAR_FILE", "bytes": 0}
        if before.st_size > maximum:
            return {"status": "FILE_TOO_LARGE", "bytes": before.st_size}
        h = hashlib.sha256()
        prefix = bytearray()
        count = 0
        with os.fdopen(os.dup(fd), "rb", buffering=1024 * 1024) as handle:
            while True:
                chunk = handle.read(1024 * 1024)
                if not chunk:
                    break
                count += len(chunk)
                if count > maximum:
                    return {"status": "FILE_GREW_OVER_LIMIT", "bytes": count}
                h.update(chunk)
                if len(prefix) < 1024:
                    prefix.extend(chunk[:1024-len(prefix)])
        after = os.fstat(fd)
        path_now = os.stat(path, follow_symlinks=False)
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            return {"status": "CHANGED_WHILE_SCANNING", "bytes": count}
        if (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) != (
                path_now.st_dev, path_now.st_ino, path_now.st_size, path_now.st_mtime_ns):
            return {"status": "PATH_CHANGED_WHILE_SCANNING", "bytes": count}
        return {"status": "HASHED", "sha256_private": h.hexdigest(), "bytes": count,
                "pdf_header_hint": b"%PDF-" in prefix[:1024]}
    finally:
        os.close(fd)


def scan_pdf_blobs(backlog: Path, root: Path, *, max_files: int = 20000,
                   max_total_bytes: int = 12 * 1024**3,
                   max_single_bytes: int = 300 * 1024**2,
                   probe_other_extensions: bool = False, max_probe_files: int = 100000) -> dict:
    if min(max_files, max_total_bytes, max_single_bytes, max_probe_files) <= 0:
        raise ValueError("POSITIVE_SCAN_BOUNDS_REQUIRED")
    if root.is_symlink() or not root.is_dir():
        raise ValueError("APPROVED_REAL_ARCHIVE_ROOT_REQUIRED")
    root = root.resolve(strict=True)
    records, source = _load_private_backlog(backlog)
    matches: dict[str, list[dict]] = defaultdict(list)
    unmatched = []
    errors = []
    examined = 0
    probed = 0
    header_discoveries = 0
    hashed = 0
    scanned_bytes = 0
    skipped_symlink_files = 0
    skipped_symlink_dirs = 0

    def onerror(err):
        errors.append({"status": "DIRECTORY_READ_FAILED", "error_type": type(err).__name__})

    for dirpath, directories, filenames in os.walk(root, followlinks=False, onerror=onerror):
        # Explicitly exclude directory symlinks, recording the skipped coverage.
        keep = []
        for name in directories:
            candidate = Path(dirpath) / name
            if candidate.is_symlink():
                skipped_symlink_dirs += 1
            else:
                keep.append(name)
        directories[:] = sorted(keep)
        for name in sorted(filenames):
            if not name.lower().endswith(".pdf"):
                if not probe_other_extensions:
                    continue
                file = Path(dirpath) / name
                if file.is_symlink():
                    skipped_symlink_files += 1
                    continue
                probed += 1
                if probed > max_probe_files:
                    raise ValueError("MAX_HEADER_PROBES_EXCEEDED_NO_REPORT_WRITTEN")
                try:
                    fd = os.open(file, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
                                 | getattr(os, "O_NONBLOCK", 0))
                    try:
                        is_pdf = stat.S_ISREG(os.fstat(fd).st_mode) and b"%PDF-" in os.read(fd, 1024)
                    finally:
                        os.close(fd)
                    if not is_pdf:
                        continue
                    header_discoveries += 1
                except OSError as err:
                    errors.append({"relative_path_private": str(file.relative_to(root)),
                                   "status": "HEADER_PROBE_FAILED", "error_type": type(err).__name__})
                    continue
            examined += 1
            if examined > max_files:
                raise ValueError("MAX_PDF_FILES_EXCEEDED_NO_REPORT_WRITTEN")
            file = Path(dirpath) / name
            relative = str(file.relative_to(root))
            if file.is_symlink():
                skipped_symlink_files += 1
                errors.append({"relative_path_private": relative, "status": "SYMLINK_SKIPPED"})
                continue
            try:
                size = os.stat(file, follow_symlinks=False).st_size
                if scanned_bytes + size > max_total_bytes:
                    raise ValueError("MAX_TOTAL_BYTES_EXCEEDED_NO_REPORT_WRITTEN")
                # Charge the actual observed bytes, not any promised archive size.
                outcome = _hash_file(file, maximum=max_single_bytes)
                if outcome["status"] != "HASHED":
                    errors.append({"relative_path_private": relative, **outcome})
                    continue
                hashed += 1
                scanned_bytes += outcome["bytes"]
                sha = outcome["sha256_private"]
                candidate = {"relative_path_private": relative, "bytes": outcome["bytes"],
                             "pdf_header_hint": outcome["pdf_header_hint"]}
                if sha in records:
                    matches[sha].append(candidate)
                else:
                    unmatched.append({"sha256_private": sha, **candidate})
            except ValueError:
                raise
            except (OSError, PermissionError) as err:
                errors.append({"relative_path_private": relative,
                               "status": "FILE_READ_FAILED", "error_type": type(err).__name__})

    catalogue = []
    for sha, entry in sorted(records.items()):
        locations = matches.get(sha, [])
        # Hash verified, PDF header hint not necessarily a valid/full PDF.
        verified = sum(x["pdf_header_hint"] for x in locations)
        catalogue.append({"document_sha256_private": sha,
                          "resource_rows": entry["resource_rows"],
                          "resource_ids_private": entry["resource_ids_private"],
                          "required_action": entry["required_action"],
                          "matching_files": locations,
                          "sha256_matching_file_count": len(locations),
                          "matching_pdf_header_hint_count": verified,
                          "blob_status": ("HASH_AND_PDF_HEADER_OBSERVED" if verified else
                                          "HASH_OBSERVED_NOT_PDF_HEADER" if locations else
                                          "NOT_OBSERVED_IN_THIS_SCAN")})
    if len(catalogue) != source["counts"]["distinct_hashed_pdfs"]:
        raise AssertionError("CATALOGUE_RECORD_LOSS")
    complete = not errors and not skipped_symlink_dirs and not skipped_symlink_files
    return {"schema_version": 1,
            "kind": "ATLAS_PRIVATE_PDF_BLOB_INVENTORY_ONLY",
            "snapshot_kind": source["snapshot_kind"],
            "catalogue_live_consistent": source.get("live_consistent"),
            "scan_complete_within_selected_root": complete,
            "archive_scope": ("APPROVED_ROOT_PDF_EXTENSION_OR_HEADER_NON_SYMLINK" if probe_other_extensions
                              else "ONLY_APPROVED_ROOT_PDF_EXTENSION_NON_SYMLINK"),
            "header_probe_enabled": probe_other_extensions,
            "non_pdf_extension_paths_probed": probed,
            "pdf_header_discoveries_other_extensions": header_discoveries,
            "counts": {
                "catalogue_distinct_hashed_pdfs_preserved": len(catalogue),
                "catalogue_pdf_rows_without_sha_preserved": len(source["unhashed_pdf_resource_ids_private"]),
                "catalogue_resource_references_preserved": sum(x["resource_rows"] for x in catalogue),
                "candidate_pdf_paths_examined": examined,
                "local_pdf_files_hashed": hashed,
                "local_pdf_bytes_hashed": scanned_bytes,
                "catalogue_hashes_with_matching_bytes": sum(bool(x["matching_files"]) for x in catalogue),
                "catalogue_hashes_with_pdf_header_hint": sum(bool(x["matching_pdf_header_hint_count"]) for x in catalogue),
                "local_pdf_files_unmatched_to_catalogue": len(unmatched),
                "local_pdf_files_skipped_or_unreadable": len(errors),
                "symlink_dirs_skipped": skipped_symlink_dirs,
                "symlink_files_skipped": skipped_symlink_files,
            },
            "catalogue_private": catalogue,
            "unmatched_local_pdf_files_private": unmatched,
            "unhashed_pdf_resource_ids_private": source["unhashed_pdf_resource_ids_private"],
            "scan_errors_private": errors,
            "limitations": [
                "Selected directory may not contain all source archives or extensionless PDFs.",
                "SHA match and PDF header hint do not prove a valid official cadastral plan.",
                "Missing matches indicate not observed in this scan, never globally absent.",
                "No source file was changed, deleted, reindexed or OCR-processed.",
            ]}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--backlog", required=True, type=Path)
    p.add_argument("--archive-root", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--max-files", type=int, default=20000)
    p.add_argument("--max-total-bytes", type=int, default=12 * 1024**3)
    p.add_argument("--max-single-bytes", type=int, default=300 * 1024**2)
    p.add_argument("--probe-other-extensions", action="store_true")
    p.add_argument("--max-probe-files", type=int, default=100000)
    a = p.parse_args()
    report = scan_pdf_blobs(a.backlog, a.archive_root, max_files=a.max_files,
                            max_total_bytes=a.max_total_bytes, max_single_bytes=a.max_single_bytes,
                            probe_other_extensions=a.probe_other_extensions, max_probe_files=a.max_probe_files)
    write_private_json(a.output, report)
    print(json.dumps({"report_written": True, "scope_complete": report["scan_complete_within_selected_root"],
                      "counts": report["counts"], "header_discoveries_other_extensions": report["pdf_header_discoveries_other_extensions"], "official_plans_verified": "UNKNOWN"}))


if __name__ == "__main__":
    main()
