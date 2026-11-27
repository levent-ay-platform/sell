"""Private federated document reference index; no source DB, network or publication."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

from scripts.atlas_cadastre_evidence_audit import DIGITS, write_private_json
from scripts.atlas_document_linkage import build_candidate_graph
from scripts.atlas_public_projection import _safe_file

SHA = re.compile(r"[0-9a-f]{64}")
TERMINAL = {"PRIVATE_REVIEW_REQUIRED", "EXTRACTION_FAILED_RETAINED", "BLOB_RECHECK_FAILED"}


def _digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise ValueError("INVALID_DOCUMENT_DIGEST")
    return value


def _refs(values):
    if not isinstance(values, list) or any(not isinstance(v, str) or not re.fullmatch(r"[0-9]{1,12}", v) for v in values):
        raise ValueError("INVALID_PAGE_REFERENCES")
    return sorted(set(values))


def _observations(doc, origin):
    result = []
    for page in doc.get("page_reference_evidence_private", []):
        p = page.get("page")
        if p is not None and (type(p) is not int or p < 1):
            raise ValueError("INVALID_PAGE_NUMBER")
        result.append({"page": p, "origin": origin,
                       "title_reference_candidates": _refs(page.get("title_reference_candidates", [])),
                       "parcel_reference_candidates": _refs(page.get("parcel_reference_candidates", [])),
                       "cadastral_term_present": page.get("cadastral_term_present") is True})
    return result


def _read_batch_chain(dirs, audit, docs, audit_sha):
    previous = None
    entries = {}
    for folder in dirs:
        folder = Path(folder)
        if folder.is_symlink() or not folder.is_dir() or folder.stat().st_mode & 0o077:
            raise ValueError("PRIVATE_BATCH_DIRECTORY_REQUIRED")
        path = folder / "completed-private-manifest.json"
        m = _safe_file(path)
        if (m.get("kind") != "ATLAS_PRIVATE_ARCHIVE_REVIEW_BATCH" or
                m.get("schema_version") != 2 or m.get("snapshot_kind") != audit["snapshot_kind"] or
                _digest(folder / "audit.json") != audit_sha):
            raise ValueError("BATCH_AUDIT_BINDING_MISMATCH")
        if previous is None:
            if m.get("batch_offset") != 0 or m.get("previous_completed_manifest_sha256_private") is not None:
                raise ValueError("CHAIN_MUST_START_AT_ZERO")
        else:
            old, digest = previous
            if (old.get("next_offset") is None or m.get("batch_offset") != old["next_offset"] or
                    m.get("previous_completed_manifest_sha256_private") != digest or
                    any(m.get(k) != old.get(k) for k in ("snapshot_sha256_private", "eligible_queue_sha256_private", "ocr_requested", "snapshot_kind"))):
                raise ValueError("BATCH_CHAIN_MISMATCH")
        offset = m.get("batch_offset")
        count = m.get("counts", {}).get("batch_selected_hashes")
        states = m.get("all_catalogue_states_private")
        if (type(offset) is not int or type(count) is not int or not 0 <= count <= 100 or offset < 0 or
                not isinstance(states, dict) or set(states) != set(docs) or
                m["counts"].get("cumulative_attempted_hashes") != offset+count):
            raise ValueError("BATCH_ACCOUNTING_MISMATCH")
        results = folder / "documents"
        if results.is_symlink() or not results.is_dir():
            raise ValueError("BATCH_DOCUMENT_DIRECTORY_REQUIRED")
        expected = {f"{i:06d}.json" for i in range(offset+1, offset+count+1)}
        if {p.name for p in results.iterdir()} != expected:
            raise ValueError("BATCH_RESULT_FILES_MISMATCH")
        for name in sorted(expected):
            item = _safe_file(results / name)
            key = _sha(item.get("document_sha256_private"))
            if (key not in docs or key in entries or
                    item.get("resource_ids_private") != docs[key]["resource_ids_private"] or
                    item.get("status") not in TERMINAL or states[key] != item["status"] or
                    item.get("ground_verified") is not False or item.get("official_plan_verified") is not None):
                raise ValueError("DOCUMENT_RESULT_BINDING_MISMATCH")
            entries[key] = item
        if {k for k,v in states.items() if v in TERMINAL} != set(entries) or any(states[k]!=e["status"] for k,e in entries.items()):
            raise ValueError("CUMULATIVE_STATE_MISMATCH")
        previous = (m, _digest(path))
    return entries, previous


def build_index(audit_path, batch_dirs, scopes, sources):
    audit_path = Path(audit_path)
    audit = _safe_file(audit_path)
    if audit.get("schema_version") != 3:
        raise ValueError("ATLAS_AUDIT_V3_REQUIRED")
    docs = {}
    for doc in audit["review_queue"] + audit["unresolved_pdf_documents"]:
        key = _sha(doc["document_sha256"])
        if key in docs:
            raise ValueError("DUPLICATE_DOCUMENT")
        docs[key] = doc
    if len(docs) != audit["counts"]["distinct_pdf_sha256"]:
        raise ValueError("AUDIT_ACCOUNTING_MISMATCH")
    entries, previous = _read_batch_chain(batch_dirs, audit, docs, _digest(audit_path))
    rows = []
    enriched = []
    for key, doc in sorted(docs.items()):
        item = entries.get(key, {})
        observations = _observations(doc, "CATALOGUE_INDEX")
        if item.get("ocr_status") == "COMPLETED":
            observations += _observations(item, "SHA_MATCHED_BATCH_OCR")
        # Never combine a title from one observation with a parcel from another,
        # even if the page numbers are identical.
        known_pages = [obs for obs in observations if obs["page"] is not None]
        enriched.append({**doc, "page_reference_evidence_private": known_pages})
        vector = item.get("vector_evidence_private", {})
        vector_count = vector.get("candidate_outline_count") if (
            item.get("vector_status") == "COMPLETED" and vector.get("document_sha256_private") == key
            and vector.get("ground_verified") is False) else None
        rows.append({"document_sha256": key, "resource_ids_private": doc["resource_ids_private"],
                     "registry_scope": scopes.get(key), "observations_private": observations,
                     "family_hints": sorted(set(doc.get("family_hints", [])) |
                         {h for pg in item.get("page_reference_evidence_private", []) for h in pg.get("family_hints", [])}),
                     "batch_status": item.get("status", "NOT_ATTEMPTED_IN_PROVIDED_CHAIN"),
                     "vector_candidate_count": vector_count,
                     "official_plan_verified": None, "ground_verified": False})
    graph = build_candidate_graph({**audit, "review_queue": enriched,
                                  "unresolved_pdf_documents": []}, scopes, sources)
    return {"schema_version": 1, "kind": "PRIVATE_ATLAS_PROPERTY_INDEX",
            "snapshot_kind": audit["snapshot_kind"],
            "audit_sha256_private": _digest(audit_path),
            "last_batch_manifest_sha256_private": previous[1] if previous else None,
            "counts": {"document_hashes": len(rows), "batch_attempted": len(entries),
                       "unhashed_resource_rows": len(audit["unhashed_pdf_resource_ids_private"]),
                       "documents_with_title_evidence": sum(any(o["title_reference_candidates"] for o in r["observations_private"]) for r in rows),
                       "documents_with_candidate_links": graph["counts"]["documents_with_candidates"]},
            "documents_private": rows, "candidate_graph_private": graph,
            "unhashed_pdf_resource_ids_private": audit["unhashed_pdf_resource_ids_private"],
            "official_plan_count": None, "public_export_permitted": False}


def lookup(index, query):
    if index.get("kind") != "PRIVATE_ATLAS_PROPERTY_INDEX" or index.get("public_export_permitted") is not False:
        raise ValueError("PRIVATE_INDEX_REQUIRED")
    def normalize(v, minimum):
        if not isinstance(v, str):
            raise ValueError("INVALID_QUERY_REFERENCE")
        v = v.strip().translate(DIGITS)
        if not re.fullmatch(r"[0-9]{%d,12}" % minimum, v):
            raise ValueError("INVALID_QUERY_REFERENCE")
        return v
    title = normalize(query.get("title_reference"), 3)
    plot = normalize(query["parcel_reference"], 1) if query.get("parcel_reference") is not None else None
    scope = query.get("registry_scope")
    if scope is not None and (not isinstance(scope, str) or not scope or len(scope)>100):
        raise ValueError("INVALID_QUERY_SCOPE")
    matches = []
    for row in index["documents_private"]:
        observations = [o for o in row["observations_private"]
                        if title in o["title_reference_candidates"] and
                        (plot is None or (o["page"] is not None and plot in o["parcel_reference_candidates"]))]
        if not observations:
            continue
        actual_scope = row["registry_scope"]
        state = ("SCOPE_UNVERIFIED" if actual_scope is None else
                 "OTHER_SCOPE" if scope is not None and actual_scope != scope else
                 "SCOPE_MATCH" if scope is not None else "SCOPE_NOT_REQUESTED")
        matches.append({"document_sha256": row["document_sha256"], "scope_status": state,
                        "registry_scope": actual_scope, "observations_private": observations,
                        "batch_status": row["batch_status"], "vector_candidate_count": row["vector_candidate_count"],
                        "official_plan_verified": None})
    priority = {"SCOPE_MATCH": 0, "SCOPE_NOT_REQUESTED": 1, "SCOPE_UNVERIFIED": 2, "OTHER_SCOPE": 3}
    matches.sort(key=lambda r: (priority[r["scope_status"]], r["document_sha256"]))
    return {"kind":"PRIVATE_ATLAS_TITLE_LOOKUP", "matches_private":matches,
            "counts":dict(Counter(r["scope_status"] for r in matches)),
            "searched_document_hashes":index["counts"]["document_hashes"],
            "official_plan_verified":None, "public_export_permitted":False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="command", required=True)
    build = sub.add_parser("build")
    build.add_argument("--audit", type=Path, required=True)
    build.add_argument("--batch-directory", type=Path, action="append", default=[])
    build.add_argument("--scopes", type=Path, required=True)
    build.add_argument("--sources", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    search = sub.add_parser("lookup")
    search.add_argument("--index", type=Path, required=True)
    search.add_argument("--query", type=Path, required=True)
    search.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    if a.command == "build":
        result = build_index(a.audit, a.batch_directory, _safe_file(a.scopes), _safe_file(a.sources))
    else:
        result = lookup(_safe_file(a.index), _safe_file(a.query))
    write_private_json(a.output, result)
    print(json.dumps({"private_output_written":True, "counts":result["counts"], "official_plans":"UNKNOWN"}))


if __name__ == "__main__":
    main()
