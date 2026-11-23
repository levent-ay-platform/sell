#!/usr/bin/env python3
"""Private, conservative cadastre evidence inventory. No live DB writes or publication.

This audit NEVER certifies an official cadastral plan. It finds candidate documents
for human/provenance review. Treat a main-file diagnostic as potentially stale.
Atlas source of truth: levent-ay-platform/sell Issue #20.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile

CADASTRAL_WORDS = re.compile(
    r"(?:\bplan\s+(?:de\s+)?cadastre\b|"
    r"\bplan\s+cadastral\b|\bplan\s+parcellaire\b|"
    r"مخطط\s+(?:المسح\s+)?العقاري|مخطط\s+مسح\s+عقاري)",
    re.IGNORECASE,
)
TITLE_REF = re.compile(
    r"(?:\btitre\s+foncier\b|\bT\.?\s*F\.?\b|الرسم\s+العقاري)"
    r"\s*(?:n[°ºo.]?\s*|num(?:é|e)ro\s*|رقم\s*)?[:#\s-]*([0-9٠-٩۰-۹]{3,12})",
    re.IGNORECASE,
)
PARCEL_REF = re.compile(
    r"(?:\bparcelle\b|\blot\b|المقسم|القطعة)"
    r"\s*(?:n[°ºo.]?\s*|num(?:é|e)ro\s*|رقم\s*)?[:#\s-]*([0-9٠-٩۰-۹]{1,12})",
    re.IGNORECASE,
)
DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")


def references(pattern: re.Pattern[str], value: str) -> set[str]:
    # Preserve leading zeros: references are identifiers, not integers.
    return {m.translate(DIGITS) for m in pattern.findall(value or "")}


def audit_snapshot(db: Path, *, snapshot_kind: str) -> dict:
    if snapshot_kind not in {"mainfile_diagnostic", "transactional_backup"}:
        raise ValueError("An explicit snapshot kind is required")
    if not db.is_file() or db.is_symlink():
        raise ValueError("A regular existing SQLite file is required")

    before = db.stat()
    # immutable=1 avoids lock/shm changes to the source. Only use on a stable copy
    # or for explicitly labelled main-file diagnostics, never as live-state proof.
    uri = db.resolve().as_uri() + "?mode=ro&immutable=1"
    connection = sqlite3.connect(uri, uri=True)
    try:
        connection.execute("PRAGMA query_only=ON")
        integrity = connection.execute("PRAGMA quick_check(1)").fetchone()[0]
        if integrity != "ok":
            raise ValueError("SQLite quick_check failed")
        tables = {
            name for (name,) in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        needed = {"resources", "documents", "pages"}
        if not needed.issubset(tables):
            raise ValueError("Snapshot missing required Atlas tables")

        # No inner join: raw resource counts and unmatched identifiers survive.
        rows = connection.execute(
            "SELECT id, name, extension, sha256 FROM resources"
        )
        pdf_count = 0
        no_hash = 0
        resources_per_hash: dict[str, int] = defaultdict(int)
        filename_hits: set[str] = set()
        raw_count = 0
        for _resource_id, name, extension, sha in rows:
            raw_count += 1
            if (extension or "").lower() != ".pdf":
                continue
            pdf_count += 1
            if not sha:
                no_hash += 1
                continue
            key = str(sha)
            resources_per_hash[key] += 1
            if CADASTRAL_WORDS.search(name or ""):
                filename_hits.add(key)

        indexed_docs = {
            str(sha): (state, count)
            for sha, state, count in connection.execute(
                "SELECT sha256, state, page_count FROM documents"
            )
            if sha is not None
        }
        page_hits: dict[str, set[int]] = defaultdict(set)
        title_refs: dict[str, set[str]] = defaultdict(set)
        parcel_refs: dict[str, set[str]] = defaultdict(set)
        pages_scanned = 0
        for sha, page, ocr in connection.execute(
            "SELECT sha256, page, text FROM pages"
        ):
            pages_scanned += 1
            key = str(sha)
            if key not in resources_per_hash or not ocr:
                continue
            if CADASTRAL_WORDS.search(ocr):
                if page is not None:
                    page_hits[key].add(int(page))
            title_refs[key].update(references(TITLE_REF, ocr))
            parcel_refs[key].update(references(PARCEL_REF, ocr))

        candidates = []
        for key in sorted(filename_hits | set(page_hits)):
            indicators = []
            if key in filename_hits:
                indicators.append("FILENAME_TERM")
            if page_hits.get(key):
                indicators.append("OCR_TEXT_TERM")
            candidates.append(
                {
                    "document_sha256": key,
                    "resource_rows": resources_per_hash[key],
                    "indicators": indicators,
                    "ocr_page_candidates": sorted(page_hits.get(key, set())),
                    "title_reference_candidates": sorted(title_refs.get(key, set())),
                    "parcel_reference_candidates": sorted(parcel_refs.get(key, set())),
                    "indexed_document_present": key in indexed_docs,
                }
            )

        report = {
            "schema_version": 1,
            "audit_kind": "CADASTRAL_CANDIDATES_ONLY",
            "snapshot_kind": snapshot_kind,
            "live_consistent": False if snapshot_kind == "mainfile_diagnostic" else None,
            "snapshot_quick_check": integrity,
            "counts": {
                "all_resource_rows_preserved": raw_count,
                "pdf_resource_rows": pdf_count,
                "pdf_resource_rows_without_sha": no_hash,
                "distinct_pdf_sha256": len(resources_per_hash),
                "indexed_document_rows": connection.execute(
                    "SELECT COUNT(*) FROM documents"
                ).fetchone()[0],
                "ocr_page_rows_scanned": pages_scanned,
                "pdf_resource_rows_with_indexed_document": sum(
                    count for key, count in resources_per_hash.items()
                    if key in indexed_docs
                ),
                "cadastral_keyword_candidate_documents": len(candidates),
            },
            "candidates": candidates,
            "verified_official_cadastral_plans": None,
            "verified_georeferenced_parcels": None,
            "publication_rights_verified": False,
            "limitations": [
                "Keywords in filenames/OCR indicate review candidates only.",
                "A matching title or parcel number alone does not locate the land.",
                "Official status, provenance, georeferencing and rights need independent review.",
                "Atlas PAU/AFI planning polygons are not legal cadastral boundaries.",
            ],
        }
    finally:
        connection.close()

    after = db.stat()
    if before.st_size != after.st_size or before.st_mtime_ns != after.st_mtime_ns:
        raise RuntimeError("Input database changed while it was being scanned")
    return report


def write_private_json(output: Path, data: dict) -> None:
    parent = output.parent
    if parent.exists() and parent.is_symlink():
        raise ValueError("Output directory must not be a symlink")
    parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if output.exists() or output.is_symlink():
        raise FileExistsError("Refusing to overwrite existing audit output")
    os.umask(0o077)
    fd, staging = tempfile.mkstemp(prefix=".cadastre-", dir=str(parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as target:
            json.dump(data, target, ensure_ascii=False, indent=2, sort_keys=True)
            target.flush()
            os.fsync(target.fileno())
        os.chmod(staging, 0o600)
        os.replace(staging, output)
    finally:
        if os.path.exists(staging):
            os.unlink(staging)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--snapshot-kind",
        choices=["mainfile_diagnostic", "transactional_backup"],
        required=True,
    )
    args = parser.parse_args()
    result = audit_snapshot(args.db, snapshot_kind=args.snapshot_kind)
    write_private_json(args.output, result)
    print(json.dumps({
        "audit_written": True,
        "snapshot_kind": args.snapshot_kind,
        "candidate_documents": result["counts"]["cadastral_keyword_candidate_documents"],
        "official_verified": "UNKNOWN",
        "live_consistent": result["live_consistent"],
    }))


if __name__ == "__main__":
    main()
