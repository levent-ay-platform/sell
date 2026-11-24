#!/usr/bin/env python3
"""Resumable, local-only Atlas archive deep review, comparison and private index.

One operator invocation; immutable windows; no live DB, public export or app
deployment. Run under the owner in a resource-isolated environment.
"""
from __future__ import annotations
import argparse
from collections import Counter
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import time

from scripts.atlas_archive_deep_review import review_pdf
from scripts.atlas_archive_next_pass import run_pilot
from scripts.atlas_archive_review_batch import _approved_roots, _safe_relative
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_document_linkage import build_candidate_graph
from scripts.atlas_property_index import build_index, _read_batch_chain, _sha
from scripts.atlas_spatial_context import private_json
from scripts.atlas_environment_preflight import readiness

HIGH_RASTER={"CADASTRAL_HINT","OTC_ORIGIN_HINT","FIRST_PASS_FAILURE",
             "NEWLY_OBSERVED_LOCAL_BLOB","PLAN_FAMILY_HINT","NABEUL_KELIBIA_FILENAME_HINT",
             "VECTOR_OUTLINES_PRESENT"}
PAGE_WINDOW=20
TERMINAL={"COMPLETE_PAGE_ATTEMPTS","COMPLETE_WITH_OBSERVATION_FAILURES",
          "DOCUMENT_PAGE_CAP_RETAINED","DEEP_REVIEW_FAILED_RETAINED"}


def private_dir(path, *, create=False):
    p=Path(path)
    if any(a.is_symlink() for a in (p,*p.parents)):
        raise ValueError("SYMLINK_DIRECTORY_REJECTED")
    if create:
        p.mkdir(mode=0o700,exist_ok=True)
    if not p.is_dir() or p.stat().st_mode&0o077:
        raise ValueError("PRIVATE_DIRECTORY_REQUIRED")
    return p.resolve(strict=True)


@contextmanager
def lock_output(output):
    lock=output.parent/("."+output.name+".lock")
    fd=os.open(lock,os.O_CREAT|os.O_RDWR|getattr(os,"O_NOFOLLOW",0),0o600)
    try:
        if os.fstat(fd).st_mode&0o077:
            raise ValueError("PRIVATE_LOCK_REQUIRED")
        try:
            fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("ANOTHER_REVIEW_IS_RUNNING") from None
        yield
    finally:
        os.close(fd)


def first_chain(run):
    dirs=[]
    for folder in run.glob("batch*"):
        path=folder/"completed-private-manifest.json"
        if path.is_file():
            manifest,_=private_json(path)
            dirs.append((manifest["batch_offset"],folder))
    dirs=[folder for _,folder in sorted(dirs)]
    if not dirs:
        raise ValueError("FIRST_PASS_REQUIRED")
    audit,asha=private_json(dirs[0]/"audit.json")
    docs={}
    for row in audit["review_queue"]+audit["unresolved_pdf_documents"]:
        key=_sha(row["document_sha256"])
        if key in docs:
            raise ValueError("DUPLICATE_AUDIT_HASH")
        docs[key]=row
    if len(docs)!=audit["counts"]["distinct_pdf_sha256"]:
        raise ValueError("AUDIT_ACCOUNTING_MISMATCH")
    entries,last=_read_batch_chain(dirs,audit,docs,asha)
    if last is None or last[0]["next_offset"] is not None:
        raise ValueError("FIRST_PASS_NOT_COMPLETE")
    return dirs,audit,docs,entries,last


def load_plan(output, roots, last, docs):
    plan,sha=private_json(output/"second-pass-plan.json")
    if (plan.get("kind")!="PRIVATE_ATLAS_SECOND_PASS_PLAN" or
        plan.get("schema_version")!=1 or plan.get("public_export_permitted") is not False or
        plan.get("official_plan_count") is not None or
        plan.get("first_pass_manifest_sha256_private")!=last[1] or
        plan.get("approved_roots_private")!=[str(r) for r in roots]):
        raise ValueError("SECOND_PASS_PLAN_BINDING_MISMATCH")
    seen=set()
    for row in plan["rows_private"]:
        key=_sha(row["document_sha256_private"])
        if key in seen or key not in docs or row["first_pass_state"]!=last[0]["all_catalogue_states_private"][key]:
            raise ValueError("PLAN_CATALOGUE_BINDING_MISMATCH")
        seen.add(key)
        locs=row["locations_private"]
        if not isinstance(locs,list) or row["state"]!=("AVAILABLE_FOR_DEEP_REVIEW" if locs else "NOT_OBSERVED_IN_SELECTED_ROOTS"):
            raise ValueError("PLAN_LOCATION_STATE_MISMATCH")
        for loc in locs:
            if type(loc.get("root_number")) is not int or not 1<=loc["root_number"]<=len(roots) or loc.get("pdf_header_hint") is not True:
                raise ValueError("PLAN_LOCATION_INVALID")
    if seen!=set(docs):
        raise ValueError("PLAN_DOCUMENT_ACCOUNTING_MISMATCH")
    return plan,sha


def observe_window(result, sha, start, limit, total, raster, adaptive):
    if (result.get("kind")!="PRIVATE_ARCHIVE_DEEP_REVIEW" or result.get("schema_version")!=1
        or result.get("document_sha256_private")!=sha or result.get("page_start")!=start
        or result.get("ground_verified") is not False or result.get("official_plan_verified") is not None
        or result.get("public_export_permitted") is not False
        or result.get("raster_requested") is not raster or result.get("adaptive_raster") is not adaptive):
        raise ValueError("WINDOW_BINDING_MISMATCH")
    count=result.get("document_page_count")
    if type(count) is not int or count<start or (total is not None and count!=total):
        raise ValueError("WINDOW_DOCUMENT_PAGE_COUNT_CHANGED")
    end=min(count,start+limit-1)
    if result.get("page_end")!=end or result.get("next_page")!=(end+1 if end<count else None):
        raise ValueError("WINDOW_RANGE_MISMATCH")
    pages=result.get("pages_private")
    if not isinstance(pages,list) or [p.get("page") for p in pages]!=list(range(start,end+1)):
        raise ValueError("WINDOW_PAGE_ACCOUNTING_MISMATCH")
    for page in pages:
        obs=page["observations"]
        methods=[o.get("method") for o in obs]
        if methods not in (["native_layout"],["native_layout","raster_sparse_ocr"]):
            raise ValueError("WINDOW_OBSERVATION_METHOD_MISMATCH")
        if not raster and len(obs)!=1 or raster and not adaptive and len(obs)!=2:
            raise ValueError("WINDOW_RASTER_POLICY_MISMATCH")
    return count,end


def review_document(row,roots,folder,config,remaining):
    sha=row["document_sha256_private"]
    raster=config["raster_mode"]!="none"
    adaptive=config["raster_mode"]=="adaptive" and not set(row["reasons"])&HIGH_RASTER
    cap=config["max_pages_per_document"]
    start=1
    total=None
    windows=[]
    new=0
    failures=0
    observed_files=set()
    if folder.exists():
        private_dir(folder)
    else:
        if remaining==0:
            return [],{"state":"PENDING","pages_attempted":0,"new_windows":0}
        private_dir(folder,create=True)
    failpath=folder/"failure.json"
    failure=None
    if failpath.exists() or failpath.is_symlink():
        failure,_=private_json(failpath)
    while start<=cap and (total is None or start<=total):
        limit=min(PAGE_WINDOW,cap-start+1)
        path=folder/f"pages-{start:08d}.json"
        if path.exists() or path.is_symlink():
            result,_=private_json(path)
        elif failure is not None:
            if (failure.get("kind")!="PRIVATE_ATLAS_DEEP_FAILURE" or
                failure.get("document_sha256_private")!=sha or failure.get("page_start")!=start):
                raise ValueError("FAILURE_CHECKPOINT_MISMATCH")
            observed_files.add(failpath.name)
            break
        elif remaining is not None and new>=remaining:
            break
        else:
            result=None
            failure_reasons=set()
            for loc in row["locations_private"]:
                try:
                    src=_safe_relative(roots[loc["root_number"]-1],loc["relative_path_private"])
                    result=review_pdf(src,sha,path,start_page=start,max_pages=limit,
                                      raster=raster,adaptive_raster=adaptive)
                    break
                except (OSError,ValueError) as exc:
                    # Only known non-sensitive reason codes leave the worker.
                    code=str(exc)
                    allowed={"ARCHIVE_PDF_SIZE_LIMIT","PDF_SHA_OR_HEADER_MISMATCH",
                        "PDF_PAGE_COUNT_UNAVAILABLE_COMMAND_FAILED","PDF_PAGE_COUNT_UNAVAILABLE_TIMEOUT",
                        "PDF_PAGE_COUNT_UNAVAILABLE_COMPLETED","PDF_PAGE_COUNT_UNAVAILABLE_OUTPUT_LIMIT",
                        "REQUIRED_TOOLS_MISSING","PAGE_START_OUT_OF_RANGE","ARCHIVE_FILE_UNAVAILABLE",
                        "SYMLINK_REVIEW_REQUIRED"}
                    failure_reasons.add(code if code in allowed else "OTHER_WORKER_OR_FILE_ERROR")
                    if path.exists() or path.is_symlink():
                        raise
            new+=1
            if result is None:
                failure={"kind":"PRIVATE_ATLAS_DEEP_FAILURE","document_sha256_private":sha,
                         "page_start":start,"status":"ALL_APPROVED_COPIES_REJECTED_OR_UNREADABLE",
                         "reason_codes":sorted(failure_reasons)}
                write_private_json(failpath,failure)
                observed_files.add(failpath.name)
                break
        total,end=observe_window(result,sha,start,limit,total,raster,adaptive)
        observed_files.add(path.name)
        windows.append(result)
        failures+=sum(o["status"]!="COMPLETED" for p in result["pages_private"] for o in p["observations"])
        start=end+1
    # Reject gaps or extra evidence rather than silently discarding a checkpoint.
    existing={p.name for p in folder.iterdir()}
    # A process interruption can leave private worker temporary directories.
    # Do not reuse or delete them automatically; the operator can inspect them.
    extra=existing-observed_files
    if any(not n.startswith("deep-review-") or not (folder/n).is_dir() or (folder/n).is_symlink() for n in extra):
        raise ValueError("UNEXPECTED_DOCUMENT_CHECKPOINT")
    if failure is not None:
        state="DEEP_REVIEW_FAILED_RETAINED"
    elif total is not None and start>total:
        state="COMPLETE_WITH_OBSERVATION_FAILURES" if failures else "COMPLETE_PAGE_ATTEMPTS"
    elif start>cap:
        state="DOCUMENT_PAGE_CAP_RETAINED"
    else:
        state="PENDING" if not windows else "PARTIAL"
    return windows,{"state":state,"pages_attempted":start-1,"document_pages":total,
                    "observation_failures":failures,"new_windows":new,
                    "failure_reason_codes":failure.get("reason_codes",["UNSPECIFIED_LEGACY_FAILURE"]) if failure else [],
                    "next_page":None if total is not None and start>total else start}


def deep_observations(windows):
    observations=[]
    family=set()
    for window in windows:
        for page in window["pages_private"]:
            for obs in page["observations"]:
                if obs["status"]!="COMPLETED" or not isinstance(obs.get("evidence"),dict):
                    continue
                e=obs["evidence"]
                titles=e["title_candidates"]
                parcels=e["parcel_candidates"]
                family.update(e["family_hints"])
                # A suffix may distinguish registry branches. Never discard it to
                # create a candidate graph edge on a bare numeric reference.
                def bare(rows):
                    return sorted({r["number"] for r in rows if r["suffix"] is None})
                if not (titles or parcels or e["area_candidates"] or e["cadastral_term_present"] or e["family_hints"]):
                    continue
                observations.append({"page":page["page"],"origin":"DEEP_"+obs["method"].upper(),
                   "title_reference_candidates":bare(titles),"parcel_reference_candidates":bare(parcels),
                   "qualified_title_candidates_private":titles,"qualified_parcel_candidates_private":parcels,
                   "area_candidates_private":e["area_candidates"],"cadastral_term_present":e["cadastral_term_present"]})
    return observations,family


def merge_index(base,audit,window_map,states,scopes,sources,plan_sha):
    before_titles=0
    after_titles=0
    added=0
    before_parcels=0
    after_parcels=0
    newly_cadastral=0
    enriched=[]
    for row in base["documents_private"]:
        sha=row["document_sha256"]
        old=row["observations_private"]
        new,family=deep_observations(window_map.get(sha,[]))
        old_title=any(o["title_reference_candidates"] for o in old)
        new_title=any(o["qualified_title_candidates_private"] for o in new)
        old_parcel=any(o["parcel_reference_candidates"] for o in old)
        new_parcel=any(o["qualified_parcel_candidates_private"] for o in new)
        before_titles+=old_title
        after_titles+=old_title or new_title
        added+=new_title and not old_title
        before_parcels+=old_parcel
        after_parcels+=old_parcel or new_parcel
        newly_cadastral+=any(o["cadastral_term_present"] for o in new) and not any(o["cadastral_term_present"] for o in old)
        row["observations_private"]=old+new
        row["family_hints"]=sorted(set(row["family_hints"])|family)
        row["deep_review_private"]=states[sha]
        enriched.append({"document_sha256":sha,"page_reference_evidence_private":[
                         o for o in row["observations_private"] if o["page"] is not None]})
    graph=build_candidate_graph({**audit,"review_queue":enriched,"unresolved_pdf_documents":[]},scopes,sources)
    base["candidate_graph_private"]=graph
    base["second_pass_plan_sha256_private"]=plan_sha
    base["counts"].update({"documents_with_title_evidence":after_titles,
                           "documents_with_parcel_evidence":after_parcels,
                           "documents_with_candidate_links":graph["counts"]["documents_with_candidates"]})
    base["comparison_private"]={"first_pass_title_documents":before_titles,
        "combined_title_documents":after_titles,"additional_title_documents":added,
        "first_pass_parcel_documents":before_parcels,"combined_parcel_documents":after_parcels,
        "additional_cadastral_hint_documents":newly_cadastral,
        "documents_with_deep_observations":sum(bool(deep_observations(w)[0]) for w in window_map.values())}
    return base


def diagnostic_cohorts(plan,window_map,states,metadata):
    groups={}
    for row in plan["rows_private"]:
        sha=row["document_sha256_private"]
        windows=window_map.get(sha,[])
        state=states[sha]
        obs=[o for w in windows for p in w["pages_private"] for o in p["observations"]]
        evidence=[o["evidence"] for o in obs if o["status"]=="COMPLETED" and isinstance(o.get("evidence"),dict)]
        native=[o["evidence"] for o in obs if o["method"]=="native_layout" and o["status"]=="COMPLETED" and isinstance(o.get("evidence"),dict)]
        pages=state.get("document_pages")
        flags={"documents":1,"reviewed_documents":bool(windows),
            "with_completed_observation":bool(evidence),"with_native_text":any(e["text_present"] for e in native),
            "with_title_evidence":any(e["title_candidates"] for e in evidence),
            "with_parcel_evidence":any(e["parcel_candidates"] for e in evidence),
            "with_cadastral_hint":any(e["cadastral_term_present"] for e in evidence),
            "observed_over_8_pages":type(pages) is int and pages>8,
            "observed_over_32_pages":type(pages) is int and pages>32,
            "pages_attempted":state["pages_attempted"],
            "native_pages_without_text":sum(not e["text_present"] for e in native),
            "native_pages_with_text":sum(e["text_present"] for e in native)}
        labels=["ALL","V1_"+row["first_pass_state"]]+["SOURCE_"+v for v in metadata[sha]["origins"]]
        for label in labels:
            g=groups.setdefault(label,{"metrics":Counter(),"states":Counter(),"failure_reasons_nonexclusive":Counter()})
            g["metrics"].update(flags)
            g["states"][state["state"]]+=1
            g["failure_reasons_nonexclusive"].update(state.get("failure_reason_codes",[]))
    return groups


def run_full(run,roots,output,*,raster_mode="adaptive",max_pages=500,max_windows=0,
             scopes_path=None,sources_path=None,max_scan_files=20000,max_scan_bytes=12*1024**3,max_probe_files=100000):
    if raster_mode not in ("adaptive","all","none") or not 1<=max_pages<=10000 or max_windows<0:
        raise ValueError("INVALID_REVIEW_BOUNDS")
    if not readiness(ocr=raster_mode!="none")["ready"]:
        raise ValueError("ENVIRONMENT_NOT_READY")
    run=private_dir(run)
    output=Path(output).absolute()
    private_dir(output.parent)
    if output.is_symlink() or output.resolve().is_relative_to(run) or run.is_relative_to(output.resolve()):
        raise ValueError("SEPARATE_OUTPUT_REQUIRED")
    roots=_approved_roots([Path(r) for r in roots],output.resolve(),(run/"catalogue.sqlite").resolve())
    if any(run.is_relative_to(r) or r.is_relative_to(run) for r in roots):
        raise ValueError("ROOT_MUST_EXCLUDE_FIRST_RUN")
    dirs,audit,docs,entries,last=first_chain(run)
    with lock_output(output):
        if not output.exists():
            run_pilot(run,roots,output,max_documents=0,max_scan_files=max_scan_files,
                      max_scan_bytes=max_scan_bytes,max_probe_files=max_probe_files)
        private_dir(output)
        plan,plan_sha=load_plan(output,roots,last,docs)
        config={"kind":"PRIVATE_ATLAS_FULL_REVIEW_CONFIG","schema_version":1,"plan_sha256_private":plan_sha,
                "first_pass_manifest_sha256_private":last[1],"raster_mode":raster_mode,
                "max_pages_per_document":max_pages,"page_window":PAGE_WINDOW}
        cfg=output/"full-review-config.json"
        if cfg.exists() or cfg.is_symlink():
            old,_=private_json(cfg)
            if old!=config:
                raise ValueError("REVIEW_CONFIG_CHANGED_USE_NEW_OUTPUT")
        else:
            write_private_json(cfg,config)
        scopes,_=private_json(scopes_path or run/"scopes.json")
        sources,_=private_json(sources_path or run/"sources.json")
        base=build_index(dirs[0]/"audit.json",dirs,scopes,sources)
        documents=private_dir(output/"full-documents",create=True)
        states={}
        window_map={}
        used=0
        for i,row in enumerate(plan["rows_private"],1):
            sha=row["document_sha256_private"]
            if row["state"]!="AVAILABLE_FOR_DEEP_REVIEW":
                states[sha]={"state":"NOT_OBSERVED_IN_SELECTED_ROOTS","pages_attempted":0,"new_windows":0}
                continue
            remaining=None if max_windows==0 else max(0,max_windows-used)
            windows,state=review_document(row,roots,documents/sha,config,remaining)
            states[sha]=state
            window_map[sha]=windows
            used+=state["new_windows"]
            if i%25==0 or i==len(plan["rows_private"]):
                print(json.dumps({"rows_checked":i,"catalogue_rows":len(plan["rows_private"]),
                                  "new_windows":used}),flush=True)
        if set(states)!=set(docs):
            raise ValueError("FULL_REVIEW_ACCOUNTING_MISMATCH")
        index=merge_index(base,audit,window_map,states,scopes,sources,plan_sha)
        stamp=str(time.time_ns())
        index_name=f"property-index-combined-{stamp}.json"
        write_private_json(output/index_name,index)
        pending=sum(s["state"] in ("PENDING","PARTIAL") for s in states.values())
        backlog,_=private_json(dirs[0]/"backlog.json")
        metadata={_sha(r["document_sha256_private"].lower()):r for r in backlog["backlog_private"]+backlog["text_ready_private"]}
        if set(metadata)!=set(docs):raise ValueError("BACKLOG_DIAGNOSTIC_BINDING_MISMATCH")
        cohorts=diagnostic_cohorts(plan,window_map,states,metadata)
        report={"kind":"PRIVATE_ATLAS_FULL_REVIEW_SUMMARY","schema_version":1,
            "counts":{**plan["counts"],"review_states":dict(Counter(s["state"] for s in states.values())),
                     "page_attempts":sum(s["pages_attempted"] for s in states.values()),
                     "observation_failures":sum(s.get("observation_failures",0) for s in states.values()),
                     "new_windows_this_invocation":used,"pending_available_documents":pending,
                     "candidate_document_links":index["counts"]["documents_with_candidate_links"]},
            "comparison":index["comparison_private"],"diagnostic_cohorts_nonexclusive":cohorts,"available_queue_attempted":pending==0,
            "combined_index_name_private":index_name,"official_plan_count":None,"public_export_permitted":False}
        write_private_json(output/f"summary-{stamp}.json",report)
        return report


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--first-pass-run",type=Path,required=True)
    p.add_argument("--archive-root",type=Path,required=True,action="append")
    p.add_argument("--output-directory",type=Path,required=True)
    p.add_argument("--raster-mode",choices=["adaptive","all","none"],default="adaptive")
    p.add_argument("--max-pages-per-document",type=int,default=500)
    p.add_argument("--max-windows",type=int,default=0)
    p.add_argument("--max-scan-files",type=int,default=20000)
    p.add_argument("--max-scan-bytes",type=int,default=12*1024**3)
    p.add_argument("--max-probe-files",type=int,default=100000)
    p.add_argument("--scopes",type=Path)
    p.add_argument("--sources",type=Path)
    a=p.parse_args()
    r=run_full(a.first_pass_run,a.archive_root,a.output_directory,raster_mode=a.raster_mode,
               max_pages=a.max_pages_per_document,max_windows=a.max_windows,
               scopes_path=a.scopes,sources_path=a.sources,max_scan_files=a.max_scan_files,
               max_scan_bytes=a.max_scan_bytes,max_probe_files=a.max_probe_files)
    print(json.dumps({"private_review_summary_written":True,"counts":r["counts"],
                      "comparison":r["comparison"],"diagnostic_cohorts_nonexclusive":r["diagnostic_cohorts_nonexclusive"],
                      "available_queue_attempted":r["available_queue_attempted"],
                      "official_plans_verified":"UNKNOWN"}))


if __name__=="__main__":
    main()
