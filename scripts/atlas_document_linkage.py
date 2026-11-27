#!/usr/bin/env python3
"""PRIVATE source-scoped document ↔ parcel candidate graph for Atlas.

A shared number, name or reference is not a legal title/parcel match. Requires
explicit scope annotation and page-local title/plot pairs before creating even
a provisional edge. All unresolved documents and source records are retained.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_public_projection import _safe_file


def _identifier(value, maximum=256):
    return isinstance(value,str) and 1 <= len(value) <= maximum and not any(
        ord(c)<32 or ord(c)==127 for c in value)


def _scope(value):
    return _identifier(value,100)


def _source_key(row):
    fields=("namespace","service_id","layer_id","object_id","version")
    if not isinstance(row,dict) or any(not _identifier(row.get(f)) for f in fields):
        raise ValueError("COMPLETE_SOURCE_SCOPED_KEY_REQUIRED")
    return tuple(row[f] for f in fields)


def build_candidate_graph(report, scope_annotations, source_records):
    if (not isinstance(report,dict) or report.get("schema_version") != 3 or
            not isinstance(report.get("review_queue"),list) or
            not isinstance(report.get("unresolved_pdf_documents"),list)):
        raise ValueError("ATLAS_AUDIT_V3_REQUIRED")
    if not isinstance(scope_annotations,dict) or not isinstance(source_records,list):
        raise ValueError("INVALID_LINKAGE_INPUT")
    if len(source_records)>250_000 or len(scope_annotations)>100_000:
        raise ValueError("BOUNDED_LINKAGE_INPUT_REQUIRED")
    docs=report["review_queue"]+report["unresolved_pdf_documents"]
    seen_docs=set()
    for doc in docs:
        if not isinstance(doc,dict) or not _identifier(doc.get("document_sha256"),128):
            raise ValueError("INVALID_DOCUMENT_HASH")
        doc_key=doc["document_sha256"]
        if doc_key in seen_docs:
            raise ValueError("DUPLICATE_DOCUMENT_HASH")
        seen_docs.add(doc_key)
    if report.get("counts",{}).get("distinct_pdf_sha256")!=len(seen_docs):
        raise ValueError("DOCUMENT_ACCOUNTING_MISMATCH")
    if any(k not in seen_docs for k in scope_annotations):
        raise ValueError("ANNOTATION_FOR_UNKNOWN_DOCUMENT")
    by_refs={}
    all_sources=[]
    seen_sources=set()
    for row in source_records:
        key=_source_key(row)
        if key in seen_sources:
            raise ValueError("DUPLICATE_SOURCE_SCOPED_KEY")
        seen_sources.add(key)
        all_sources.append(key)
        scope=row.get("registry_scope")
        title=row.get("title_reference")
        parcel=row.get("parcel_reference")
        if _scope(scope) and _identifier(title,100) and _identifier(parcel,100):
            by_refs.setdefault((scope,title,parcel),[]).append(key)

    edges=[]
    unresolved_docs=[]
    referenced_source_keys=set()
    for doc in docs:
        doc_key=doc["document_sha256"]
        scope=scope_annotations.get(doc_key)
        if not _scope(scope):
            unresolved_docs.append({"document_sha256":doc_key,"reason":"SCOPE_UNVERIFIED"})
            continue
        per_page=doc.get("page_reference_evidence_private",[])
        if not isinstance(per_page,list):
            raise ValueError("INVALID_PRIVATE_PAGE_EVIDENCE")
        matches={}
        for item in per_page:
            if not isinstance(item,dict):
                raise ValueError("INVALID_PRIVATE_PAGE_EVIDENCE")
            titles=item.get("title_reference_candidates",[])
            plots=item.get("parcel_reference_candidates",[])
            if not isinstance(titles,list) or not isinstance(plots,list):
                raise ValueError("INVALID_REFERENCE_ARRAY")
            for title in titles:
                for plot in plots:
                    if not _identifier(title,100) or not _identifier(plot,100):
                        continue
                    # A title and plot must be observed on the same document page.
                    for source_key in by_refs.get((scope,title,plot),[]):
                        matches.setdefault(source_key,[]).append({
                            "page":item.get("page"),
                            "title_reference":title,
                            "parcel_reference":plot,
                        })
        if not matches:
            unresolved_docs.append({
                "document_sha256":doc_key,
                "reason":"NO_SAME_PAGE_SCOPED_PAIR",
            })
            continue
        ambiguous=len(matches)>1
        for key,page_evidence in sorted(matches.items()):
            referenced_source_keys.add(key)
            edges.append({
                "document_sha256":doc_key,
                "source_scoped_key":list(key),
                "status":"AMBIGUOUS_CANDIDATE" if ambiguous else "CANDIDATE",
                "method":"SAME_PAGE_TF_PARCEL_AND_REVIEWED_SCOPE",
                "page_evidence":page_evidence,
                "legally_verified":False,
            })
    unresolved_source_keys=[
        list(key) for key in sorted(all_sources) if key not in referenced_source_keys
    ]
    if len(edges)==0 and len(unresolved_docs)!=len(docs):
        raise AssertionError("UNRESOLVED_DOCUMENT_ACCOUNTING")
    if len({row["document_sha256"] for row in edges})+len(unresolved_docs)!=len(docs):
        raise AssertionError("DOCUMENT_PARTITION_MISMATCH")
    return {
        "schema_version":1,
        "kind":"PRIVATE_CANDIDATE_GRAPH",
        "counts":{
            "all_document_hashes_retained":len(docs),
            "all_source_rows_retained":len(all_sources),
            "candidate_edges":len(edges),
            "documents_with_candidates":len({e["document_sha256"] for e in edges}),
            "unresolved_documents":len(unresolved_docs),
            "unlinked_source_records":len(unresolved_source_keys),
        },
        "edges":edges,
        "unresolved_documents":unresolved_docs,
        "unlinked_source_records":unresolved_source_keys,
        "verified_legal_title_links":None,
        "source_ownership_verified":False,
        "public_export_permitted":False,
    }


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit",type=Path,required=True)
    parser.add_argument("--scope-annotations",type=Path,required=True)
    parser.add_argument("--source-records",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    result=build_candidate_graph(
        _safe_file(args.audit),_safe_file(args.scope_annotations),
        _safe_file(args.source_records))
    write_private_json(args.output,result)
    print(json.dumps({"private_graph_staged":True,"counts":result["counts"],
                      "legal_links_verified":False}))


if __name__=="__main__":
    main()
