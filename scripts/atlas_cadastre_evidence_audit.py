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
    r"(?:\btitre\s+foncier\b|\bT\.?\s*F\.?(?=\s|[:#\d٠-٩۰-۹]|$)|الرسم\s+العقاري)"
    r"\s*(?:n[°ºo.]?\s*|num(?:é|e)ro\s*|(?:رقم|عدد)\s*)?[:#\s-]*([0-9٠-٩۰-۹]{3,12})",
    re.IGNORECASE,
)
PARCEL_REF = re.compile(
    r"(?:\bparcelle\b|\blot\b|المقسم|القطعة)"
    r"\s*(?:n[°ºo.]?\s*|num(?:é|e)ro\s*|(?:رقم|عدد)\s*)?[:#\s-]*([0-9٠-٩۰-۹]{1,12})",
    re.IGNORECASE,
)
# High-recall triage hints, NOT proof of official cadastre, ownership, or map coordinates.
# A document can have multiple hints; preserve ambiguity for later source review.
FAMILY_PATTERNS = {
    "LOTISSEMENT_PLAN_HINT": re.compile(
        r"plan\s+de\s+lotissement|lotissement|مخطط\s+تقسيم|تقسيم\s+عقاري",
        re.IGNORECASE,
    ),
    "BOUNDARY_SURVEY_HINT": re.compile(
        r"bornage|proc[eè]s.verbal\s+de\s+(?:bornage|d[eé]limitation)|"
        r"محضر\s+(?:تحديد|تحويز)|مخطط\s+تحديد",
        re.IGNORECASE,
    ),
    "TOPOGRAPHIC_PLAN_HINT": re.compile(
        r"plan\s+topograph|lev[eé]\s+topograph|plan\s+de\s+masse|"
        r"plan\s+d.implantation|مخطط\s+طبوغراف",
        re.IGNORECASE,
    ),
    "TRANSFER_DOCUMENT_HINT": re.compile(
        r"carte\s+de\s+mutation|acte\s+de\s+vente|"
        r"بطاقة\s+نقل|عقد\s+بيع",
        re.IGNORECASE,
    ),
    "OWNERSHIP_DOCUMENT_HINT": re.compile(
        r"certificat\s+(?:de\s+)?propri[eé]t[eé]|attestation\s+de\s+propri[eé]t[eé]|"
        r"شهادة\s+(?:اشتراك\s+)?ملكية|عقد\s+بيع",
        re.IGNORECASE,
    ),
    "PLANNING_MAP_HINT": re.compile(
        r"plan\s+d.am[eé]nagement|\bP\.?\s*A\.?\s*U\.?\b|"
        r"مخطط\s+التهيئة",
        re.IGNORECASE,
    ),
}


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
        # Private-only source row pointers preserve distinct references to the
        # same document hash, including repeated resource IDs.
        resource_ids_per_hash: dict[str, list[str | None]] = defaultdict(list)
        unhashed_pdf_resource_ids: list[str | None] = []
        filename_hits: set[str] = set()
        family_hints: dict[str, set[str]] = defaultdict(set)
        raw_count = 0
        for _resource_id, name, extension, sha in rows:
            raw_count += 1
            if (extension or "").lower() != ".pdf":
                continue
            pdf_count += 1
            if not sha:
                no_hash += 1
                unhashed_pdf_resource_ids.append(
                    str(_resource_id) if _resource_id is not None else None
                )
                continue
            key = str(sha)
            resources_per_hash[key] += 1
            resource_ids_per_hash[key].append(
                str(_resource_id) if _resource_id is not None else None
            )
            for family, family_pattern in FAMILY_PATTERNS.items():
                if family_pattern.search(name or ""):
                    family_hints[key].add(family)
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
        ocr_cadastral_hits: set[str] = set()
        reference_page_evidence: dict[str, list[dict]] = defaultdict(list)
        pdf_hashes_with_pages: set[str] = set()
        pdf_hashes_with_ocr_text: set[str] = set()
        title_refs: dict[str, set[str]] = defaultdict(set)
        parcel_refs: dict[str, set[str]] = defaultdict(set)
        pages_scanned = 0
        for sha, page, ocr in connection.execute(
            "SELECT sha256, page, text FROM pages"
        ):
            pages_scanned += 1
            key = str(sha)
            if key not in resources_per_hash:
                continue
            pdf_hashes_with_pages.add(key)
            if not ocr or not str(ocr).strip():
                continue
            pdf_hashes_with_ocr_text.add(key)
            cadastral_term = bool(CADASTRAL_WORDS.search(ocr))
            if cadastral_term:
                ocr_cadastral_hits.add(key)
                if page is not None:
                    page_hits[key].add(int(page))
            matched_families = [
                family for family, family_pattern in FAMILY_PATTERNS.items()
                if family_pattern.search(ocr)
            ]
            family_hints[key].update(matched_families)
            extracted_titles = references(TITLE_REF, ocr)
            extracted_parcels = references(PARCEL_REF, ocr)
            title_refs[key].update(extracted_titles)
            parcel_refs[key].update(extracted_parcels)
            if extracted_titles or extracted_parcels or cadastral_term:
                reference_page_evidence[key].append({
                    "page": int(page) if page is not None else None,
                    "title_reference_candidates": sorted(extracted_titles),
                    "parcel_reference_candidates": sorted(extracted_parcels),
                    "cadastral_term_present": cadastral_term,
                })

        candidates = []
        for key in sorted(filename_hits | ocr_cadastral_hits):
            indicators = []
            if key in filename_hits:
                indicators.append("FILENAME_TERM")
            if key in ocr_cadastral_hits:
                indicators.append("OCR_TEXT_TERM")
            candidates.append(
                {
                    "document_sha256": key,
                    "resource_rows": resources_per_hash[key],
                    "resource_ids_private": resource_ids_per_hash[key],
                    "page_reference_evidence_private": reference_page_evidence.get(key, []),
                    "indicators": indicators,
                    "ocr_page_candidates": sorted(page_hits.get(key, set())),
                    "title_reference_candidates": sorted(title_refs.get(key, set())),
                    "parcel_reference_candidates": sorted(parcel_refs.get(key, set())),
                    "indexed_document_present": key in indexed_docs,
                }
            )

        # This broader queue includes reference-bearing documents even without a
        # cadastral keyword. Do not silently exclude unrecognized PDFs.
        review_keys = sorted(
            key for key in resources_per_hash
            if family_hints.get(key) or title_refs.get(key) or parcel_refs.get(key)
            or key in filename_hits or key in ocr_cadastral_hits
        )
        review_queue = [
            {
                "document_sha256": key,
                "resource_rows": resources_per_hash[key],
                "resource_ids_private": resource_ids_per_hash[key],
                "page_reference_evidence_private": reference_page_evidence.get(key, []),
                "family_hints": sorted(family_hints.get(key, set())),
                "cadastral_term_present": key in filename_hits or key in ocr_cadastral_hits,
                "has_title_reference_candidate": bool(title_refs.get(key)),
                "has_parcel_reference_candidate": bool(parcel_refs.get(key)),
                "indexed_document_present": key in indexed_docs,
                "status": "NEEDS_PROVENANCE_REVIEW",
            }
            for key in review_keys
        ]

        unknown_keys = sorted(set(resources_per_hash) - set(review_keys))
        # The unknown set remains explicitly addressable in the private report,
        # including PDFs with no indexed document and PDFs without extracted text.
        unresolved_pdf_documents = [
            {
                "document_sha256": key,
                "resource_rows": resources_per_hash[key],
                "resource_ids_private": resource_ids_per_hash[key],
                "indexed_document_present": key in indexed_docs,
                "page_rows_present": key in pdf_hashes_with_pages,
                "ocr_text_present": key in pdf_hashes_with_ocr_text,
                "status": "UNCLASSIFIED_RETAINED",
                "reason": (
                    "NO_OCR_TEXT" if key not in pdf_hashes_with_ocr_text
                    else "NO_RECOGNIZED_HINTS"
                ),
            }
            for key in unknown_keys
        ]
        if len(review_queue) + len(unresolved_pdf_documents) != len(resources_per_hash):
            raise AssertionError("Every hashed PDF must be accounted for once")

        report = {
            "schema_version": 3,
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
                "pdf_hashes_with_page_rows": len(pdf_hashes_with_pages),
                "pdf_hashes_with_ocr_text": len(pdf_hashes_with_ocr_text),
                "pdf_hashes_missing_document_index": sum(
                    key not in indexed_docs for key in resources_per_hash
                ),
                "pdf_resource_rows_with_indexed_document": sum(
                    count for key, count in resources_per_hash.items()
                    if key in indexed_docs
                ),
                "cadastral_keyword_candidate_documents": len(candidates),
                "triage_review_documents": len(review_queue),
                "pdf_documents_with_family_hints": sum(bool(family_hints.get(key)) for key in resources_per_hash),
                "pdf_documents_with_reference_candidates": sum(
                    bool(title_refs.get(key) or parcel_refs.get(key))
                    for key in resources_per_hash
                ),
                "pdf_documents_untriaged_unknown": len(unresolved_pdf_documents),
                "pdf_documents_unknown_without_ocr_text": sum(
                    key not in pdf_hashes_with_ocr_text for key in unknown_keys
                ),
            },
            "candidates": candidates,
            "review_queue": review_queue,
            "unhashed_pdf_resource_ids_private": unhashed_pdf_resource_ids,
            "unresolved_pdf_documents": unresolved_pdf_documents,
            "review_queue_semantics": "HINTS_ONLY; unknown remains unknown; no official status implied",
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
    old_umask = os.umask(0o077)
    staging = None
    try:
        fd, staging = tempfile.mkstemp(prefix=".cadastre-", dir=str(parent))
        with os.fdopen(fd, "w", encoding="utf-8") as target:
            json.dump(data, target, ensure_ascii=False, indent=2, sort_keys=True)
            target.flush()
            os.fsync(target.fileno())
        os.chmod(staging, 0o600)
        # Atomic no-overwrite creation: unlike os.replace(), hard-link creation
        # fails even if a second writer created the destination meanwhile.
        os.link(staging, output)
    finally:
        if staging is not None and os.path.exists(staging):
            os.unlink(staging)
        os.umask(old_umask)


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
        "triage_review_documents": result["counts"]["triage_review_documents"],
        "unclassified_retained": result["counts"]["pdf_documents_untriaged_unknown"],
        "pdf_hashes_without_ocr_text": (
            result["counts"]["distinct_pdf_sha256"]
            - result["counts"]["pdf_hashes_with_ocr_text"]
        ),
        "official_verified": "UNKNOWN",
        "live_consistent": result["live_consistent"],
    }))


if __name__ == "__main__":
    main()
