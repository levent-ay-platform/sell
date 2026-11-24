#!/usr/bin/env python3
"""Private Atlas OCR/index backlog from an *existing stable* SQLite copy.

Creates no OCR, does not touch Atlas source objects or a live database. An
unindexed resource is a work item, not proof that its PDF bytes exist.
No unverified PDF is ever classified as an official cadastral plan.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
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
    with sqlite3.connect(db.resolve().as_uri()+"?mode=ro&immutable=1", uri=True) as conn:
        conn.execute("PRAGMA query_only=ON")
        if conn.execute("PRAGMA quick_check(1)").fetchone()[0] != "ok":
            raise ValueError("SNAPSHOT_QUICK_CHECK_FAILED")
        tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"resources", "documents", "pages"}.issubset(tables):
            raise ValueError("ATLAS_TABLES_REQUIRED")
        documents = {str(s):state for s,state in conn.execute("SELECT sha256,state FROM documents")}
        page_status = {
            str(s):(int(total),int(with_text))
            for s,total,with_text in conn.execute(
                "SELECT sha256,COUNT(*),"
                "SUM(CASE WHEN text IS NOT NULL AND length(trim(text))>0 THEN 1 ELSE 0 END)"
                " FROM pages GROUP BY sha256"
            )
        }
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
            state = documents.get(sha)
            total_pages, text_pages = page_status.get(sha,(0,0))
            if state is None:
                action="VERIFY_BLOB_AND_INDEX"
            elif state=="processing_failed":
                action="REVIEW_INDEX_FAILURE"
            elif state in ("ocr_pending","mixed_ocr_pending") or text_pages==0:
                action="OCR_REQUIRED_AFTER_BLOB_CHECK"
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
