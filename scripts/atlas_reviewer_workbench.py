#!/usr/bin/env python3
"""Build a local-only, read-only reviewer workbench and review queue.

No HTTP service, publication, automatic approval, raw OCR or source URLs.
The generated HTML contains private title evidence; keep it on the owner's
machine, outside every served directory.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
import os
from pathlib import Path
import re
import tempfile
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_property_index import _sha
from scripts.atlas_property_dossier import refs, number, suffix
from scripts.atlas_archive_full_review import first_chain
from scripts.atlas_geometry_evidence import _ring
from scripts.atlas_spatial_context import private_json, key

MAX_DOCS=100000
MAX_PREVIEWS=24
MAX_PAGE_SIZE=100000
ORIGINS={"CATALOGUE_INDEX","SHA_MATCHED_BATCH_OCR","DEEP_NATIVE_LAYOUT","DEEP_RASTER_SPARSE_OCR"}
BATCH_STATES={"PRIVATE_REVIEW_REQUIRED","EXTRACTION_FAILED_RETAINED","BLOB_RECHECK_FAILED","NOT_ATTEMPTED_IN_PROVIDED_CHAIN"}
DEEP_STATES={"COMPLETE_PAGE_ATTEMPTS","COMPLETE_WITH_OBSERVATION_FAILURES","DOCUMENT_PAGE_CAP_RETAINED","DEEP_REVIEW_FAILED_RETAINED","PENDING","PARTIAL","NOT_OBSERVED_IN_SELECTED_ROOTS"}

def safe_ref(value,kind):
    n,s=value
    n=number(n,3 if kind=="title" else 1)
    suffix(s)
    return {"number":n,"suffix":s}

def tokens(values,kind):
    return [safe_ref(v,kind) for v in values]

def clean_observation(obs):
    p=obs.get("page")
    if p is not None and (type(p) is not int or not 1<=p<=100000):
        raise ValueError("INVALID_OBSERVATION_PAGE")
    area=[]
    for a in obs.get("area_candidates_private",[]):
        v,u=a.get("value"),a.get("unit")
        if isinstance(v,str) and re.fullmatch(r"[0-9]{1,12}(?:\.[0-9]{1,4})?",v) and u in ("m2","m²","ha","م2","م²","هكتار"):
            area.append({"value":v,"unit":u})
    # Older catalogue evidence sometimes contains short or malformed OCR tokens;
    # retain the original index and do not fail the whole review queue on one token.
    titles=[];parcels=[];rejected=0
    for kind,target in (("title",titles),("parcel",parcels)):
        for v in refs(obs,kind):
            try:target.append(safe_ref(v,kind))
            except (ValueError,TypeError):rejected+=1
    return {"page":p,"origin":obs["origin"] if obs.get("origin") in ORIGINS else "OTHER",
            "titles":titles,"parcels":parcels,"areas":area,"invalid_reference_observations":rejected,
            "cadastral_hint":obs.get("cadastral_term_present") is True}

def build_queue(index,spatial=None):
    if (index.get("kind")!="PRIVATE_ATLAS_PROPERTY_INDEX" or index.get("public_export_permitted") is not False
            or index.get("official_plan_count") is not None):
        raise ValueError("PRIVATE_CANDIDATE_INDEX_REQUIRED")
    docs=index["documents_private"]
    if not isinstance(docs,list) or len(docs)>MAX_DOCS:
        raise ValueError("BOUNDED_INDEX_REQUIRED")
    graph=index["candidate_graph_private"]
    if graph.get("kind")!="PRIVATE_CANDIDATE_GRAPH" or graph.get("public_export_permitted") is not False:
        raise ValueError("PRIVATE_GRAPH_REQUIRED")
    edge_counts=Counter()
    edge_keys=defaultdict(set)
    for e in graph["edges"]:
        if e.get("legally_verified") is not False or e.get("status") not in ("CANDIDATE","AMBIGUOUS_CANDIDATE"):
            raise ValueError("CANDIDATE_EDGE_REQUIRED")
        sha=_sha(e["document_sha256"])
        scoped=key(e.get("source_scoped_key"))
        if scoped is None:raise ValueError("CANDIDATE_SOURCE_KEY_REQUIRED")
        edge_counts[sha]+=1
        edge_keys[sha].add(scoped)
    places=defaultdict(set)
    conflicts=defaultdict(set)
    if spatial is not None:
        fingerprint=hashlib.sha256(json.dumps(graph,sort_keys=True,ensure_ascii=False,separators=(",",":"),
                                               allow_nan=False).encode("utf-8")).hexdigest()
        if (spatial.get("kind")!="PRIVATE_ATLAS_SPATIAL_CONTEXT" or spatial.get("public_export_permitted") is not False
            or index.get("federated_graph_sha256_private")!=fingerprint
            or spatial.get("federated_graph_sha256_private")!=fingerprint):
            raise ValueError("BOUND_FEDERATED_SPATIAL_CONTEXT_REQUIRED")
        for row in spatial["rows_private"]:
            if row.get("legal_boundary_verified") is not False:
                raise ValueError("CANDIDATE_SPATIAL_ROW_REQUIRED")
            if row.get("link_state")!="SCOPED_DOCUMENT_CANDIDATES":continue
            for sha in row.get("candidate_document_hashes_private",[]):
                sha=_sha(sha)
                if key(row.get("source_scoped_key")) not in edge_keys[sha]:
                    raise ValueError("SPATIAL_DOCUMENT_WITHOUT_SCOPED_EDGE")
                # Display only safe upstream administrative identifiers, never
                # infer a registry scope or promote a location to legal proof.
                for level,pattern in (("governorate",r"TN-[0-9]{2}"),("delegation",r"delegation-[0-9]{4}"),
                                      ("sector",r"sector-[A-Za-z0-9-]{1,48}"),("district",r"district-[1-5]")):
                    for g in row.get(level+"_intersections",[]):
                        aid=g.get("admin_id")
                        if isinstance(aid,str) and re.fullmatch(pattern,aid):
                            places[sha].add(aid)
                for field,pattern in (("admin_parent_conflicts",r"delegation-[0-9]{4}"),
                                      ("sector_parent_conflicts",r"sector-[A-Za-z0-9-]{1,48}")):
                    for aid in row.get(field,[]):
                        if isinstance(aid,str) and re.fullmatch(pattern,aid):
                            conflicts[sha].add(aid)
    rows=[]
    seen=set()
    for row in docs:
        sha=_sha(row["document_sha256"])
        if sha in seen or row.get("official_plan_verified") is not None or row.get("ground_verified") is not False:
            raise ValueError("DUPLICATE_OR_PROMOTED_DOCUMENT")
        seen.add(sha)
        observations=[clean_observation(o) for o in row["observations_private"]]
        useful=[o for o in observations if o["titles"] or o["parcels"] or o["areas"] or o["cadastral_hint"] or o["invalid_reference_observations"]]
        title_count=sum(len(o["titles"]) for o in useful)
        plot_count=sum(len(o["parcels"]) for o in useful)
        has_pair=any(o["page"] is not None and o["titles"] and o["parcels"] for o in useful)
        outlines=row.get("vector_candidate_count")
        if type(outlines) is not int or outlines<0:outlines=None
        batch=row.get("batch_status")
        batch=batch if batch in BATCH_STATES else "UNKNOWN"
        deep=(row.get("deep_review_private") or {}).get("state")
        deep=deep if deep in DEEP_STATES else "UNKNOWN"
        scope_known=isinstance(row.get("registry_scope"),str) and bool(row["registry_scope"])
        tasks=["VERIFY_AUTHORITY_AND_DOCUMENT_SHA","VERIFY_TITLE_PARCEL_ASSOCIATION","VERIFY_GROUND_GEOMETRY",
               "REVIEW_RIGHTS_AND_CONSENT"]
        if not scope_known:tasks.append("REVIEW_REGISTRY_SCOPE")
        if not title_count:tasks.append("TITLE_EVIDENCE_NOT_OBSERVED")
        if deep in ("PENDING","PARTIAL","DOCUMENT_PAGE_CAP_RETAINED","DEEP_REVIEW_FAILED_RETAINED","COMPLETE_WITH_OBSERVATION_FAILURES"):
            tasks.append("EXTRACTION_REVIEW_REQUIRED")
        if outlines:tasks.append("INSPECT_PAGE_OUTLINES")
        if edge_counts[sha]>1:tasks.append("RESOLVE_MULTIPLE_SOURCE_CANDIDATES")
        if conflicts[sha]:tasks.append("REVIEW_ADMIN_PARENT_CONFLICT")
        score=100*bool(edge_counts[sha])+70*any(o["cadastral_hint"] for o in useful)+40*has_pair+20*bool(title_count)+10*bool(outlines)
        rows.append({"sha":sha,"title_observations":title_count,"parcel_observations":plot_count,
                     "same_observation_pair":has_pair,"outlines":outlines,"batch":batch,"deep":deep,
                     "scope_reviewed":scope_known,"candidate_links":edge_counts[sha],
                     "admin_context":sorted(places[sha]),"admin_parent_conflicts":sorted(conflicts[sha]),
                     "observations":useful,"tasks":tasks,"priority":score,
                     "official_plan_verified":None,"ground_verified":False})
    if len(seen)!=index["counts"]["document_hashes"] or set(edge_counts)-seen:
        raise ValueError("REVIEW_QUEUE_ACCOUNTING_MISMATCH")
    rows.sort(key=lambda r:(-r["priority"],r["sha"]))
    return {"kind":"PRIVATE_ATLAS_REVIEW_QUEUE","schema_version":1,"rows":rows,
            "counts":{"documents":len(rows),"with_title":sum(bool(r["title_observations"]) for r in rows),
                      "with_parcel":sum(bool(r["parcel_observations"]) for r in rows),
                      "with_outlines":sum(bool(r["outlines"]) for r in rows),
                      "with_candidate_links":sum(bool(r["candidate_links"]) for r in rows),
                      "with_admin_context":sum(bool(r["admin_context"]) for r in rows),
                      "with_admin_conflicts":sum(bool(r["admin_parent_conflicts"]) for r in rows),
                      "without_scope":sum(not r["scope_reviewed"] for r in rows),
                      "unhashed_resource_rows":index["counts"].get("unhashed_resource_rows"),
                      "deep_states":dict(Counter(r["deep"] for r in rows))},
            "official_plan_count":None,"public_export_permitted":False}

def preview_outlines(entry,sha):
    vector=entry.get("vector_evidence_private") or {}
    if (entry.get("vector_status")!="COMPLETED" or vector.get("document_sha256_private")!=sha
        or vector.get("kind")!="ATLAS_PRIVATE_PDF_PAGE_OUTLINE_CANDIDATES" or vector.get("ground_verified") is not False):
        return []
    result=[]
    for pg in vector.get("pages_private",[]):
        w,h=pg.get("display_width_points"),pg.get("display_height_points")
        if (type(w) not in (float,int) or type(h) not in (float,int) or not
            0<w<=MAX_PAGE_SIZE or not 0<h<=MAX_PAGE_SIZE or pg.get("page_unit")!="PAGE_POINT"):
            continue
        for n,o in enumerate(pg.get("outlines_private",[]),1):
            if o.get("ground_verified") is not False or o.get("geometry_status")!="PAGE_OUTLINE_CANDIDATE":continue
            try:r=_ring(o.get("page_ring"))
            except ValueError:continue
            if any(not(0<=x<=w and 0<=y<=h) for x,y in r):continue
            result.append({"page":pg["page"],"ordinal":n,"width":w,"height":h,
                           "ring":[list(p) for p in r],"possible_border":o.get("possible_frame_or_border") is True})
            if len(result)>=MAX_PREVIEWS:return result
    return result

def write_html(path,queue,previews):
    # No user-controlled HTML interpolation: JSON is escaped for script context.
    # All displayed values are subsequently set with textContent in the browser.
    payload=json.dumps({"queue":queue,"previews":previews},ensure_ascii=False,allow_nan=False,separators=(",",":"))
    payload=payload.replace("&","\\u0026").replace("<","\\u003c").replace(">","\\u003e")
    template=Path(__file__).with_name("atlas_reviewer_workbench.html").read_text(encoding="utf-8")
    if template.count("__ATLAS_PRIVATE_PAYLOAD__")!=1:raise ValueError("WORKBENCH_TEMPLATE_INVALID")
    page=template.replace("__ATLAS_PRIVATE_PAYLOAD__",payload)
    path=Path(path)
    if path.exists() or path.is_symlink():raise FileExistsError("NEW_PRIVATE_HTML_REQUIRED")
    fd,tmp=tempfile.mkstemp(prefix=".review-",dir=path.parent)
    try:
        with os.fdopen(fd,"w",encoding="utf-8") as f:
            f.write(page);f.flush();os.fsync(f.fileno())
        os.chmod(tmp,0o600)
        os.link(tmp,path)
    finally:os.unlink(tmp)

def run(index_path,output,*,spatial_path=None,first_pass_run=None,document_sha=None):
    index,ih=private_json(index_path)
    spatial,sh=private_json(spatial_path) if spatial_path else (None,None)
    queue=build_queue(index,spatial)
    previews={}
    if document_sha is not None:
        sha=_sha(document_sha)
        if first_pass_run is None:raise ValueError("FIRST_PASS_REQUIRED_FOR_PREVIEW")
        if sha not in {r["sha"] for r in queue["rows"]}:raise ValueError("DOCUMENT_NOT_IN_INDEX")
        dirs,audit,docs,entries,last=first_chain(Path(first_pass_run))
        if index.get("last_batch_manifest_sha256_private")!=last[1]:
            raise ValueError("INDEX_FIRST_PASS_BINDING_MISMATCH")
        previews[sha]=preview_outlines(entries.get(sha,{}),sha)
    output=Path(output)
    if output.exists() or output.is_symlink() or any(p.is_symlink() for p in output.parents):
        raise ValueError("NEW_PRIVATE_WORKBENCH_REQUIRED")
    if not output.parent.is_dir() or output.parent.stat().st_mode&0o077:
        raise ValueError("PRIVATE_OUTPUT_PARENT_REQUIRED")
    output.mkdir(mode=0o700)
    queue["input_sha256_private"]={"index":ih,"spatial":sh}
    write_private_json(output/"review-queue.json",queue)
    write_html(output/"review.html",queue,previews)
    return queue["counts"]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--index",type=Path,required=True)
    p.add_argument("--output-directory",type=Path,required=True)
    p.add_argument("--spatial-context",type=Path)
    p.add_argument("--first-pass-run",type=Path)
    p.add_argument("--document-sha")
    a=p.parse_args()
    counts=run(a.index,a.output_directory,spatial_path=a.spatial_context,
               first_pass_run=a.first_pass_run,document_sha=a.document_sha)
    print(json.dumps({"private_workbench_written":True,"counts":counts,"official_plans_verified":"UNKNOWN"}))
if __name__=="__main__":main()
