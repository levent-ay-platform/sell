#!/usr/bin/env python3
"""Private Atlas OCR/index backlog from an *existing stable* SQLite copy.

Creates no OCR, does not touch Atlas source objects or a live database. An
unindexed resource is a work item, not proof that its PDF bytes exist.
No unverified PDF is ever classified as an official cadastral plan.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from contextlib import closing
import json
from pathlib import Path
import re
import sqlite3
from urllib.parse import urlsplit

from scripts.atlas_cadastre_evidence_audit import write_private_json

CADASTRE = re.compile(r"cadastre|cadastral|parcellaire|bornage|المسح العقاري|تحديد عقاري", re.I)
LOCALITY = re.compile(r"nabeul|kelibia|kélibia|قليبية|نابل|karkouan|قرقوان", re.I)
PLAN = re.compile(r"plan|lotissement|مخطط|تقسيم|parcelle", re.I)
ORIGIN = {
    "otc.nat.tn": "OTC",
    "www.otc.nat.tn": "OTC",
    "geopau.gov.tn": "GEOPAU",
    "www.geopau.gov.tn": "GEOPAU",
    "afh.nat.tn": "AFH",
    "www.afh.nat.tn": "AFH",
    "afi.nat.tn": "AFI",
    "www.afi.nat.tn": "AFI",
}


def origin_of(url):
    if not isinstance(url, str):
        return "OTHER"
    try:
        return ORIGIN.get((urlsplit(url).hostname or "").lower(), "OTHER")
    except ValueError:
        return "OTHER"


def build_backlog(db: Path, snapshot_kind: str):
    if snapshot_kind not in ("transactional_backup", "mainfile_diagnostic"):
        raise ValueError("EXPLICIT_SNAPSHOT_KIND_REQUIRED")
    if not db.is_file() or db.is_symlink():
        raise ValueError("STABLE_PRIVATE_SNAPSHOT_REQUIRED")
    initial = db.stat()
    with closing(sqlite3.connect(db.resolve().as_uri()+"?mode=ro&immutable=1", uri=True)) as conn:
        conn.execute("PRAGMA query_only=ON")
        if conn.execute("PRAGMA quick_check(1)").fetchone()[0] != "ok":
            raise ValueError("SNAPSHOT_QUICK_CHECK_FAILED")
        tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"resources", "documents", "pages"}.issubset(tables):
            raise ValueError("ATLAS_TABLES_REQUIRED")
        # Never infer document coverage from the existence of one text row.
        doc_columns = {row[1] for row in conn.execute("PRAGMA table_info(documents)")}
        count_expr = "page_count" if "page_count" in doc_columns else "NULL"
        documents = {}
        for sha, state, expected in conn.execute(
                "SELECT sha256,state," + count_expr + " FROM documents"):
            key = str(sha)
            if key in documents:
                raise ValueError("DUPLICATE_DOCUMENT_IDENTITY")
            documents[key] = (state, expected if type(expected) is int and expected > 0 else None)
        page_status = defaultdict(dict)
        invalid_page_ids = Counter()
        for sha, page, has_text in conn.execute(
                "SELECT sha256,page,CASE WHEN text IS NOT NULL AND length(trim(text))>0 "
                "THEN 1 ELSE 0 END FROM pages"):
            key = str(sha)
            if type(page) is not int or page < 1:
                invalid_page_ids[key] += 1
                continue
            # Preserve distinct identities. Conflicting duplicate rows are
            # deliberately held for review rather than silently selecting one.
            page_status[key].setdefault(page, set()).add(bool(has_text))
        records = defaultdict(lambda: {
            "resource_rows":0,"resource_ids_private":[],
            "origins":set(),"locality_hint":False,"cadastre_hint":False,
            "plan_hint":False,
        })
        unhashable = []
        total_pdf_rows = 0
        for resource_id,name,extension,sha,source_url in conn.execute(
                "SELECT id,name,extension,sha256,url FROM resources"):
            if str(extension or "").lower() != ".pdf":
                continue
            total_pdf_rows += 1
            if not sha:
                unhashable.append(str(resource_id) if resource_id is not None else None)
                continue
            key=str(sha)
            entry=records[key]
            entry["resource_rows"]+=1
            entry["resource_ids_private"].append(str(resource_id) if resource_id is not None else None)
            entry["origins"].add(origin_of(source_url))
            name=str(name or "")
            entry["locality_hint"] |= bool(LOCALITY.search(name))
            entry["cadastre_hint"] |= bool(CADASTRE.search(name))
            entry["plan_hint"] |= bool(PLAN.search(name))

        queue, already_text = [], []
        source_breakdown = Counter()
        for sha,entry in records.items():
            state, expected_pages = documents.get(sha, (None, None))
            observed = page_status.get(sha, {})
            total_pages = len(observed)
            text_ids = sorted(page for page, values in observed.items() if values == {True})
            no_text_ids = sorted(page for page, values in observed.items() if values == {False})
            conflicting_ids = sorted(page for page, values in observed.items() if len(values) > 1)
            out_of_range = sorted(page for page in observed
                                  if expected_pages is not None and page > expected_pages)
            in_range = sum(1 for page in observed
                           if expected_pages is not None and page <= expected_pages)
            missing_pages = None if expected_pages is None else expected_pages - in_range
            text_pages = len(text_ids)
            if invalid_page_ids[sha] or conflicting_ids or out_of_range:
                coverage = "CONFLICT_REVIEW"
            elif expected_pages is None:
                coverage = "EXPECTED_PAGE_COUNT_UNKNOWN"
            elif missing_pages:
                coverage = "PARTIAL_PAGE_COVERAGE"
            elif no_text_ids:
                coverage = "PAGES_PRESENT_TEXT_INCOMPLETE"
            else:
                coverage = "ALL_DECLARED_PAGES_HAVE_TEXT"
            if state is None:
                action="VERIFY_BLOB_AND_INDEX"
            elif state=="processing_failed":
                action="REVIEW_INDEX_FAILURE"
            elif coverage == "CONFLICT_REVIEW":
                action="REVIEW_PAGE_IDENTITY_CONFLICT"
            elif state in ("ocr_pending","mixed_ocr_pending") or text_pages==0:
                action="OCR_REQUIRED_AFTER_BLOB_CHECK"
            elif coverage != "ALL_DECLARED_PAGES_HAVE_TEXT":
                action="REVIEW_PARTIAL_OR_UNKNOWN_COVERAGE"
            else:
                action="TEXT_AVAILABLE_REVIEW"
            origins = sorted(entry["origins"])
            for group in origins:
                source_breakdown[(group,"distinct_source_association")]+=1
                if state is None:
                    source_breakdown[(group,"without_document_index")]+=1
                if state in ("ocr_pending","mixed_ocr_pending"):
                    source_breakdown[(group,"ocr_pending")]+=1
            score = (
                (150 if entry["cadastre_hint"] else 0) +
                (110 if entry["locality_hint"] else 0) +
                (35 if entry["plan_hint"] else 0) +
                (45 if "OTC" in origins else 0) +
                (20 if "AFH" in origins else 0)
            )
            item = {
                "document_sha256_private":sha,
                "resource_rows":entry["resource_rows"],
                "resource_ids_private":entry["resource_ids_private"],
                "origins":origins,
                "locality_filename_hint":entry["locality_hint"],
                "cadastre_filename_hint":entry["cadastre_hint"],
                "plan_filename_hint":entry["plan_hint"],
                "indexed_state":state,
                "indexed_pages":total_pages,
                "text_pages":text_pages,
                "expected_pages":expected_pages,
                "coverage_state":coverage,
                "observed_text_page_ids":text_ids,
                "observed_no_text_page_ids":no_text_ids,
                "conflicting_page_ids":conflicting_ids,
                "invalid_page_identity_rows":invalid_page_ids[sha],
                "out_of_range_page_ids":out_of_range,
                "missing_page_count":missing_pages,
                "required_action":action,
                "priority_score":score,
                "blob_signature_verified":False,
                "verified_official_plan":None,
            }
            (already_text if action=="TEXT_AVAILABLE_REVIEW" else queue).append(item)
        queue.sort(key=lambda x:(-x["priority_score"],x["required_action"],x["document_sha256_private"]))
        already_text.sort(key=lambda x:(-x["priority_score"],x["document_sha256_private"]))
        counts = {
            "pdf_resource_rows":total_pdf_rows,
            "pdf_rows_without_sha":len(unhashable),
            "distinct_hashed_pdfs":len(records),
            "backlog_documents":len(queue),
            "text_ready_documents":len(already_text),
            "actions":dict(sorted(Counter(row["required_action"] for row in queue+already_text).items())),
            "page_coverage_states":dict(sorted(Counter(row["coverage_state"] for row in queue+already_text).items())),
            "source_associations": {
                key:{label:source_breakdown.get((key,label),0)
                     for label in ("distinct_source_association","without_document_index","ocr_pending")}
                for key in ("OTC","GEOPAU","AFH","AFI","OTHER")
            },
        }
        if counts["backlog_documents"]+counts["text_ready_documents"]!=len(records):
            raise AssertionError("NO_HASHED_PDF_MAY_BE_DROPPED")
    last=db.stat()
    if (initial.st_size,initial.st_mtime_ns)!=(last.st_size,last.st_mtime_ns):
        raise RuntimeError("SNAPSHOT_CHANGED_WHILE_READING")
    return {
        "schema_version":1,
        "kind":"ATLAS_PRIVATE_OCR_BACKLOG_CANDIDATES_ONLY",
        "snapshot_kind":snapshot_kind,
        "live_consistent":False if snapshot_kind=="mainfile_diagnostic" else None,
        "counts":counts,
        "backlog_private":queue,
        "text_ready_private":already_text,
        "unhashed_pdf_resource_ids_private":unhashable,
        "limitations":[
            "No PDF bytes or signatures were verified by this metadata-only report.",
            "Coverage uses declared document page_count and distinct stored page identities, not proof of extraction quality or current live completeness.",
            "A blank or missing page requires review; it is not by itself proof that OCR is necessary.",
            "Names and URL origins are triage hints, not official plan certification.",
            "OCR work must use owner-approved local copies, not mutate source archives.",
        ],
    }


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db",type=Path,required=True)
    parser.add_argument("--snapshot-kind",required=True,
                        choices=("transactional_backup","mainfile_diagnostic"))
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    report=build_backlog(args.db,args.snapshot_kind)
    write_private_json(args.output,report)
    print(json.dumps({
        "private_backlog_written":True,
        "counts":report["counts"]["actions"],
        "unresolved_hashes":report["counts"]["pdf_rows_without_sha"],
        "official_plans_verified":"UNKNOWN",
        "live_consistent":report["live_consistent"],
    }))


if __name__=="__main__":
    main()
