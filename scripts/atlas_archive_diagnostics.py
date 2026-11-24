#!/usr/bin/env python3
"""Read completed V1 evidence and optionally run a paired V2 pilot.

No original database or archive modification. Aggregate output only.
"""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
from contextlib import closing
import json
from pathlib import Path
import sqlite3
from scripts.atlas_archive_full_review import first_chain,private_dir
from scripts.atlas_archive_review_batch import _digest
from scripts.atlas_archive_next_pass import run_pilot
from scripts.atlas_archive_deep_review import extract_candidates
from scripts.atlas_cadastre_evidence_audit import TITLE_REF,PARCEL_REF,references,write_private_json
from scripts.atlas_property_index import _sha
from scripts.atlas_spatial_context import private_json

def summarize(docs,entries,meta,inventory,states):
    sizes={}
    for row in inventory["catalogue_private"]:
        k=_sha(row["document_sha256_private"])
        if k in sizes or k not in docs or row["resource_ids_private"]!=docs[k]["resource_ids_private"]:
            raise ValueError("INVENTORY_BINDING_MISMATCH")
        observed={m["bytes"] for m in row["matching_files"] if m["pdf_header_hint"] is True}
        if len(observed)>1:raise ValueError("MATCHED_HASH_SIZE_CONFLICT")
        sizes[k]=next(iter(observed),None)
    if set(sizes)!=set(docs) or set(meta)!=set(docs) or set(states)!=set(docs):
        raise ValueError("DIAGNOSTIC_ACCOUNTING_MISMATCH")
    totals=Counter();pairs=Counter();by_source=defaultdict(Counter)
    for sha,doc in docs.items():
        e=entries.get(sha,{})
        v=e.get("vector_evidence_private") or {}
        vs=e.get("vector_status","NOT_ATTEMPTED")
        os=e.get("ocr_status","NOT_ATTEMPTED")
        pages=e.get("page_reference_evidence_private",[])
        size=sizes[sha]
        # Observed vector page count, not catalogue metadata or guessed page count.
        pc=v.get("document_page_count") if vs=="COMPLETED" else None
        outline=v.get("candidate_outline_count") if vs=="COMPLETED" else None
        flags=[]
        if e:
            flags.append("attempted")
            pairs[vs+" / "+os]+=1
        if vs=="COMPLETED":
            flags.append("vector_completed")
            if type(outline) is int and outline>0:flags.append("vector_with_outlines")
            elif outline==0:flags.append("vector_zero_outlines")
        if os=="COMPLETED":
            flags.append("ocr_completed")
            if any(p.get("text_present") is True for p in pages):flags.append("ocr_with_text")
            else:flags.append("ocr_completed_without_text")
            if any(p.get("title_reference_candidates") for p in pages):flags.append("ocr_with_title")
            if any(p.get("parcel_reference_candidates") for p in pages):flags.append("ocr_with_parcel")
        if any(p.get("title_reference_candidates") for p in doc.get("page_reference_evidence_private",[])):
            flags.append("catalogue_with_title")
        if any(p.get("title_reference_candidates") for p in pages+doc.get("page_reference_evidence_private",[])):
            flags.append("combined_with_title")
        if states[sha]=="EXTRACTION_FAILED_RETAINED":
            flags.append("both_workers_failed")
            if size is not None and size>12*1024**2:flags.append("failed_over_legacy_ocr_size")
            if size is not None and size>64*1024**2:flags.append("failed_over_vector_size")
        if size is not None and size>12*1024**2:flags.append("over_legacy_ocr_size")
        if type(pc) is int and pc>8:flags.append("observed_over_legacy_ocr_pages")
        if states[sha]=="NOT_OBSERVED_WITH_PDF_HEADER":flags.append("not_observed")
        if meta[sha].get("cadastre_filename_hint"):flags.append("cadastre_filename_hint")
        totals.update(flags)
        for origin in meta[sha]["origins"]:
            if origin not in ("OTC","GEOPAU","AFH","AFI","OTHER"):
                raise ValueError("UNKNOWN_SOURCE_GROUP")
            by_source[origin].update(["catalogue_documents",*flags])
    return {"documents":len(docs),"state_counts":dict(Counter(states.values())),
            "metrics":dict(totals),"worker_status_pairs":dict(pairs),
            "source_associations_nonexclusive":{k:dict(v) for k,v in sorted(by_source.items())}}

def compare_stored_text(db,expected,docs):
    if db.is_symlink() or not db.is_file() or any(Path(str(db)+s).exists() for s in ("-wal","-shm")):
        raise ValueError("STANDALONE_SNAPSHOT_REQUIRED")
    if _digest(db)!=expected:raise ValueError("SNAPSHOT_BINDING_MISMATCH")
    sets=defaultdict(set);counts=Counter()
    with closing(sqlite3.connect(db.resolve().as_uri()+"?mode=ro&immutable=1",uri=True)) as conn:
        conn.execute("PRAGMA query_only=ON")
        for sha,txt in conn.execute("SELECT sha256,text FROM pages"):
            if sha not in docs or not isinstance(txt,str) or not txt.strip():continue
            counts["nonempty_indexed_pages"]+=1
            sets["documents_with_stored_text"].add(sha)
            if len(txt)>2*1024**2:
                counts["oversize_text_pages_skipped"]+=1;continue
            old_t=references(TITLE_REF,txt);old_p=references(PARCEL_REF,txt)
            new=extract_candidates(txt)
            for kind,old,new_rows in (("title",old_t,new["title_candidates"]),("parcel",old_p,new["parcel_candidates"])):
                if old:sets["legacy_"+kind+"_documents"].add(sha)
                if new_rows:sets["v2_"+kind+"_documents"].add(sha)
    if _digest(db)!=expected:raise ValueError("SNAPSHOT_CHANGED")
    for kind in ("title","parcel"):
        a=sets["legacy_"+kind+"_documents"];b=sets["v2_"+kind+"_documents"]
        counts[kind+"_documents_only_legacy"]=len(a-b)
        counts[kind+"_documents_only_v2"]=len(b-a)
    counts.update({k:len(v) for k,v in sets.items()})
    return dict(counts)

def run(run_path,root,output,pilot_docs=0,pilot_pages=5):
    run_path=private_dir(run_path)
    output=Path(output).absolute()
    private_dir(output.parent)
    if output.exists() or output.is_symlink() or output.resolve().is_relative_to(run_path):
        raise ValueError("NEW_SEPARATE_OUTPUT_REQUIRED")
    dirs,audit,docs,entries,last=first_chain(run_path)
    b,_=private_json(dirs[0]/"backlog.json")
    meta={_sha(r["document_sha256_private"].lower()):r for r in b["backlog_private"]+b["text_ready_private"]}
    inv,_=private_json(dirs[-1]/"root-01-blobs.json")
    if len(last[0]["root_summaries"])!=1:raise ValueError("SINGLE_V1_ROOT_DIAGNOSTIC_REQUIRED")
    report=summarize(docs,entries,meta,inv,last[0]["all_catalogue_states_private"])
    report["stored_text_parser_comparison"]=compare_stored_text(run_path/"catalogue.sqlite",last[0]["snapshot_sha256_private"],docs)
    report["official_plans_verified"]="UNKNOWN"
    output.mkdir(mode=0o700)
    write_private_json(output/"v1-diagnostic.json",report)
    print(json.dumps({"v1_diagnostic":report}),flush=True)
    if pilot_docs:
        p=run_pilot(run_path,[root],output/"v2-pilot",max_documents=pilot_docs,pages_per_document=pilot_pages,
                    raster=True,max_scan_files=50000,max_scan_bytes=40*1024**3,max_probe_files=500000)
        paired=Counter()
        for o in p["outcomes_private"]:
            sha=o["document_sha256_private"];e=entries.get(sha,{})
            old=any(pg.get("title_reference_candidates") for pg in e.get("page_reference_evidence_private",[])+docs[sha].get("page_reference_evidence_private",[]))
            paired["selected"]+=1
            paired["first_pass_title_documents"]+=old
            paired["v2_window_title_documents"]+=bool(o.get("title_evidence_observed"))
            paired["additional_title_documents"]+=not old and bool(o.get("title_evidence_observed"))
            paired["first_pass_both_failed"]+=e.get("status")=="EXTRACTION_FAILED_RETAINED"
            paired["first_pass_failed_with_completed_v2_observation"]+=e.get("status")=="EXTRACTION_FAILED_RETAINED" and o.get("observations_completed",0)>0
        summary={"pilot_counts":p["counts"],"paired_document_comparison":dict(paired),
                 "sample_is_priority_selected_not_representative":True,"official_plans_verified":"UNKNOWN"}
        write_private_json(output/"paired-pilot-summary.json",summary)
        print(json.dumps({"v2_pilot":summary}),flush=True)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--first-pass-run",required=True,type=Path)
    p.add_argument("--archive-root",required=True,type=Path)
    p.add_argument("--output-directory",required=True,type=Path)
    p.add_argument("--pilot-documents",type=int,default=12)
    p.add_argument("--pilot-pages",type=int,default=5)
    a=p.parse_args()
    if not 0<=a.pilot_documents<=20 or not 1<=a.pilot_pages<=20 or a.pilot_documents*a.pilot_pages>100:
        p.error("bounded pilot required")
    run(a.first_pass_run,a.archive_root,a.output_directory,a.pilot_documents,a.pilot_pages)
if __name__=="__main__":main()
