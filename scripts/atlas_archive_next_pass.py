#!/usr/bin/env python3
"""Private second-pass prioritization and bounded pilot for existing Atlas runs.

Preserve all first-pass records and manifests. Rescan approved roots once with
header detection, reconcile by digest, and stage prioritized page review. Never
edit the previous run, infer official status, or publish any document.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path

from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_pdf_blob_inventory import scan_pdf_blobs, _load_private_backlog
from scripts.atlas_archive_review_batch import _safe_relative, _approved_roots, _digest
from scripts.atlas_archive_deep_review import review_pdf
from scripts.atlas_property_index import _read_batch_chain, _sha
from scripts.atlas_spatial_context import private_json

TERMINAL={"PRIVATE_REVIEW_REQUIRED","EXTRACTION_FAILED_RETAINED","BLOB_RECHECK_FAILED"}
FAMILY={"BOUNDARY_SURVEY_HINT","TOPOGRAPHIC_PLAN_HINT","LOTISSEMENT_PLAN_HINT"}
MAX_PILOT_DOCS=20
MAX_PILOT_PAGES=100


def priority(meta, audit, first, recovered):
    observations=audit.get("page_reference_evidence_private",[])+first.get("page_reference_evidence_private",[])
    families=set(audit.get("family_hints",[]))
    for obs in observations:
        families.update(obs.get("family_hints",[]))
    titles=any(obs.get("title_reference_candidates") for obs in observations)
    cadastre=bool(meta.get("cadastre_filename_hint") or audit.get("cadastral_term_present")
                  or any(obs.get("cadastral_term_present") for obs in observations))
    outlines=(first.get("vector_evidence_private") or {}).get("candidate_outline_count",0)
    reasons=[]
    def add(condition, reason, score):
        if condition: reasons.append((reason,score))
    add(cadastre,"CADASTRAL_HINT",220)
    add(meta.get("locality_filename_hint"),"NABEUL_KELIBIA_FILENAME_HINT",100)
    add("OTC" in meta.get("origins",[]),"OTC_ORIGIN_HINT",120)
    add(first.get("status") in ("EXTRACTION_FAILED_RETAINED","BLOB_RECHECK_FAILED"),"FIRST_PASS_FAILURE",150)
    add(recovered,"NEWLY_OBSERVED_LOCAL_BLOB",110)
    add(bool(families & FAMILY),"PLAN_FAMILY_HINT",65)
    add(titles,"EXISTING_TITLE_REFERENCE_HINT",45)
    add(type(outlines) is int and outlines>0,"VECTOR_OUTLINES_PRESENT",30)
    add(not titles,"TITLE_EVIDENCE_NOT_OBSERVED",15)
    add(not first,"NO_FIRST_PASS_RESULT",20)
    return sum(s for _,s in reasons),[r for r,_ in reasons]


def plan_rows(backlog, audit_docs, first_entries, final_states, inventories):
    locations={}
    for number,inventory in enumerate(inventories,1):
        seen=set()
        for row in inventory["catalogue_private"]:
            sha=_sha(row["document_sha256_private"])
            if sha in seen or sha not in backlog or row["resource_ids_private"]!=backlog[sha]["resource_ids_private"]:
                raise ValueError("INVENTORY_CATALOGUE_BINDING_MISMATCH")
            seen.add(sha)
            for match in row["matching_files"]:
                if match.get("pdf_header_hint") is True:
                    locations.setdefault(sha,[]).append({"root_number":number,**match})
        if seen!=set(backlog):
            raise ValueError("INVENTORY_ACCOUNTING_MISMATCH")
    rows=[]
    for sha,meta in sorted(backlog.items()):
        matches=locations.get(sha,[])
        first=first_entries.get(sha,{})
        recovered=bool(matches) and final_states[sha]=="NOT_OBSERVED_WITH_PDF_HEADER"
        score,reasons=priority(meta,audit_docs[sha],first,recovered)
        state="AVAILABLE_FOR_DEEP_REVIEW" if matches else "NOT_OBSERVED_IN_SELECTED_ROOTS"
        rows.append({"document_sha256_private":sha,"first_pass_state":final_states[sha],
                     "state":state,"newly_observed_after_first_pass":recovered,
                     "priority_score":score,"reasons":reasons,"locations_private":matches})
    rows.sort(key=lambda r:(r["state"]!="AVAILABLE_FOR_DEEP_REVIEW",-r["priority_score"],r["document_sha256_private"]))
    return rows


def run_pilot(run, roots, output, *, max_documents=10, pages_per_document=5, raster=False,
              max_scan_files=20000, max_scan_bytes=12*1024**3, max_probe_files=100000):
    if not 0<=max_documents<=MAX_PILOT_DOCS or not 1<=pages_per_document<=20 or max_documents*pages_per_document>MAX_PILOT_PAGES:
        raise ValueError("BOUNDED_PILOT_REQUIRED")
    run,output=Path(run),Path(output)
    if run.is_symlink() or not run.is_dir() or run.stat().st_mode&0o077:
        raise ValueError("PRIVATE_FIRST_PASS_RUN_REQUIRED")
    if output.exists() or output.is_symlink() or any(p.is_symlink() for p in output.parents):
        raise ValueError("NEW_PRIVATE_OUTPUT_REQUIRED")
    if output.resolve().is_relative_to(run.resolve()) or run.resolve().is_relative_to(output.resolve()):
        raise ValueError("SEPARATE_RUN_OUTPUT_REQUIRED")
    dirs=[]
    for folder in run.glob("batch*"):
        manifest=folder/"completed-private-manifest.json"
        if manifest.is_file():
            m,_=private_json(manifest)
            dirs.append((m["batch_offset"],folder,m))
    dirs.sort(key=lambda x:x[0])
    if not dirs or dirs[-1][2]["next_offset"] is not None:
        raise ValueError("FIRST_PASS_NOT_COMPLETE")
    first=dirs[0][1]
    audit,_=private_json(first/"audit.json")
    docs={_sha(d["document_sha256"]):d for d in audit["review_queue"]+audit["unresolved_pdf_documents"]}
    if len(docs)!=audit["counts"]["distinct_pdf_sha256"]:
        raise ValueError("AUDIT_ACCOUNTING_MISMATCH")
    entries,last=_read_batch_chain([d[1] for d in dirs],audit,docs,_digest(first/"audit.json"))
    if last is None or last[0]["next_offset"] is not None:
        raise ValueError("COMPLETE_CHAIN_REQUIRED")
    raw_backlog,backlog_report=_load_private_backlog(first/"backlog.json")
    # Bind the local backlog and source associations to the accepted audit.
    meta={_sha(r["document_sha256_private"].lower()):r for r in backlog_report["backlog_private"]+backlog_report["text_ready_private"]}
    if set(meta)!=set(docs) or any(meta[k]["resource_ids_private"]!=docs[k]["resource_ids_private"] for k in docs):
        raise ValueError("BACKLOG_AUDIT_BINDING_MISMATCH")
    roots=_approved_roots([Path(r) for r in roots],output.resolve(),(run/"catalogue.sqlite").resolve())
    if any(run.resolve().is_relative_to(r) or r.is_relative_to(run.resolve()) for r in roots):
        raise ValueError("ARCHIVE_ROOT_MUST_EXCLUDE_FIRST_PASS_RUN")
    output.mkdir(parents=True,mode=0o700)
    os.chmod(output,0o700)
    inventories=[]
    for i,root in enumerate(roots,1):
        inventory=scan_pdf_blobs(first/"backlog.json",root,probe_other_extensions=True,
                                 max_files=max_scan_files,max_total_bytes=max_scan_bytes,
                                 max_probe_files=max_probe_files)
        inventories.append(inventory)
        write_private_json(output/f"root-{i:02d}-recovery.json",inventory)
    rows=plan_rows(meta,docs,entries,last[0]["all_catalogue_states_private"],inventories)
    plan={"kind":"PRIVATE_ATLAS_SECOND_PASS_PLAN","schema_version":1,
          "first_pass_manifest_sha256_private":last[1],
          "snapshot_kind":audit["snapshot_kind"],
          "approved_roots_private":[str(p) for p in roots],
          "counts":{"catalogue_hashes":len(rows),
                    "available_in_selected_roots":sum(r["state"]=="AVAILABLE_FOR_DEEP_REVIEW" for r in rows),
                    "newly_observed_hashes":sum(r["newly_observed_after_first_pass"] for r in rows),
                    "not_observed":sum(r["state"]!="AVAILABLE_FOR_DEEP_REVIEW" for r in rows),
                    "unhashed_resource_rows":len(backlog_report["unhashed_pdf_resource_ids_private"])},
          "rows_private":rows,"public_export_permitted":False,"official_plan_count":None}
    write_private_json(output/"second-pass-plan.json",plan)
    selected=[r for r in rows if r["state"]=="AVAILABLE_FOR_DEEP_REVIEW"][:max_documents]
    pilot=output/"pilot"
    pilot.mkdir(mode=0o700)
    outcomes=[]
    for i,row in enumerate(selected,1):
        sha=row["document_sha256_private"]
        result=None
        for loc in row["locations_private"]:
            try:
                path=_safe_relative(roots[loc["root_number"]-1],loc["relative_path_private"])
                result=review_pdf(path,sha,pilot/f"{i:04d}.json",max_pages=pages_per_document,raster=raster)
                break
            except (OSError,ValueError):
                continue
        if result is None:
            outcomes.append({"document_sha256_private":sha,"status":"DEEP_REVIEW_FAILED_RETAINED"})
            continue
        observations=[o for p in result["pages_private"] for o in p["observations"]]
        evidences=[o["evidence"] for o in observations if o["status"]=="COMPLETED" and isinstance(o["evidence"],dict)]
        outcomes.append({"document_sha256_private":sha,"status":"PAGE_WINDOW_REVIEWED",
                         "pages_attempted":len(result["pages_private"]),
                         "observations_completed":len(evidences),"next_page":result["next_page"],
                         "legacy_size_limit_exceeded":result["legacy_size_limit_exceeded"],
                         "legacy_page_limit_exceeded":result["legacy_page_limit_exceeded"],
                         "title_evidence_observed":any(e["title_candidates"] for e in evidences),
                         "parcel_evidence_observed":any(e["parcel_candidates"] for e in evidences),
                         "cadastral_hint_observed":any(e["cadastral_term_present"] for e in evidences)})
    summary={"kind":"PRIVATE_ATLAS_SECOND_PASS_PILOT","counts":{**plan["counts"],
              "pilot_attempted":len(outcomes),"pilot_states":dict(Counter(o["status"] for o in outcomes)),
              "pilot_documents_with_title_evidence":sum(bool(o.get("title_evidence_observed")) for o in outcomes),
              "pilot_documents_with_parcel_evidence":sum(bool(o.get("parcel_evidence_observed")) for o in outcomes),
              "pilot_documents_with_cadastral_hints":sum(bool(o.get("cadastral_hint_observed")) for o in outcomes),
              "pilot_pages_attempted":sum(o.get("pages_attempted",0) for o in outcomes),
              "pilot_documents_with_legacy_limit_exceeded":sum(bool(o.get("legacy_size_limit_exceeded") or o.get("legacy_page_limit_exceeded")) for o in outcomes)},
              "outcomes_private":outcomes,"official_plan_count":None,"public_export_permitted":False}
    write_private_json(output/"pilot-summary.json",summary)
    return summary


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--first-pass-run",type=Path,required=True)
    p.add_argument("--archive-root",type=Path,required=True,action="append")
    p.add_argument("--output-directory",type=Path,required=True)
    p.add_argument("--max-documents",type=int,default=10)
    p.add_argument("--pages-per-document",type=int,default=5)
    p.add_argument("--raster",action="store_true")
    a=p.parse_args()
    r=run_pilot(a.first_pass_run,a.archive_root,a.output_directory,
                max_documents=a.max_documents,pages_per_document=a.pages_per_document,raster=a.raster)
    print(json.dumps({"private_pilot_completed":True,"counts":r["counts"],
                      "official_plans_verified":"UNKNOWN"}))


if __name__=="__main__":
    main()
