#!/usr/bin/env python3
"""Private local cadastral evidence search across first/deep index and GIS context.

Candidate search only. No service, auth bypass, source download or public payload.
"""
from __future__ import annotations
import argparse
import hashlib
from collections import Counter, defaultdict
import json
from pathlib import Path
import re
import unicodedata
from scripts.atlas_cadastre_evidence_audit import DIGITS, write_private_json
from scripts.atlas_property_index import _sha
from scripts.atlas_spatial_context import private_json

def number(value,minimum):
    if not isinstance(value,str):
        raise ValueError("STRING_REFERENCE_REQUIRED")
    result=unicodedata.normalize("NFKC",value).translate(DIGITS).strip()
    if not re.fullmatch(r"[0-9]{%d,12}"%minimum,result):
        raise ValueError("INVALID_QUERY_NUMBER")
    return result

def suffix(value):
    if value is None:return None
    if not isinstance(value,str) or not re.fullmatch(r"[A-Za-z0-9\u0600-\u06ff-]{1,32}",value):
        raise ValueError("INVALID_QUERY_SUFFIX")
    return unicodedata.normalize("NFKC",value).casefold()

def refs(obs,kind):
    qualified=obs.get("qualified_"+kind+"_candidates_private")
    if qualified is not None:
        return [(r["number"],r.get("suffix")) for r in qualified]
    return [(r,None) for r in obs.get(kind+"_reference_candidates",[])]

def matches(refs_,target,ending):
    found=[(n,s) for n,s in refs_ if n==target]
    if ending is not None:
        return [r for r in found if r[1] is not None and suffix(r[1])==ending]
    return found

def lookup(index,query,spatial=None):
    if index.get("kind")!="PRIVATE_ATLAS_PROPERTY_INDEX" or index.get("public_export_permitted") is not False:
        raise ValueError("PRIVATE_PROPERTY_INDEX_REQUIRED")
    title=number(query.get("title_reference"),3)
    ts=suffix(query.get("title_suffix"))
    parcel=number(query["parcel_reference"],1) if query.get("parcel_reference") is not None else None
    ps=suffix(query.get("parcel_suffix"))
    if ps is not None and parcel is None:
        raise ValueError("PARCEL_SUFFIX_REQUIRES_PARCEL")
    scope=query.get("registry_scope")
    if scope is not None and (not isinstance(scope,str) or not scope or len(scope)>100):
        raise ValueError("INVALID_SCOPE")
    graph=index["candidate_graph_private"]
    if graph.get("kind")!="PRIVATE_CANDIDATE_GRAPH" or graph.get("public_export_permitted") is not False:
        raise ValueError("PRIVATE_CANDIDATE_GRAPH_REQUIRED")
    edges=defaultdict(list)
    for e in graph["edges"]:
        if e.get("legally_verified") is not False:
            raise ValueError("CANDIDATE_EDGE_REQUIRED")
        edges[_sha(e["document_sha256"])].append(e)
    locations=defaultdict(list)
    if spatial is not None:
        if spatial.get("kind")!="PRIVATE_ATLAS_SPATIAL_CONTEXT" or spatial.get("public_export_permitted") is not False:
            raise ValueError("PRIVATE_SPATIAL_CONTEXT_REQUIRED")
        if "federated_graph_sha256_private" in index or "federated_graph_sha256_private" in spatial:
            digest=hashlib.sha256(json.dumps(graph,sort_keys=True,ensure_ascii=False,separators=(",",":"),
                                             allow_nan=False).encode("utf-8")).hexdigest()
            if index.get("federated_graph_sha256_private")!=digest or spatial.get("federated_graph_sha256_private")!=digest:
                raise ValueError("FEDERATED_SPATIAL_GRAPH_BINDING_MISMATCH")
        for row in spatial["rows_private"]:
            if row.get("legal_boundary_verified") is not False:
                raise ValueError("CANDIDATE_GEOMETRY_REQUIRED")
            key=row.get("source_scoped_key")
            if isinstance(key,list) and len(key)==5 and row.get("link_state")=="SCOPED_DOCUMENT_CANDIDATES":
                locations[tuple(key)].append(row)
    results=[]
    seen=set()
    for row in index["documents_private"]:
        sha=_sha(row["document_sha256"])
        if sha in seen:
            raise ValueError("DUPLICATE_DOCUMENT_HASH")
        seen.add(sha)
        observations=[]
        for obs in row["observations_private"]:
            title_hits=matches(refs(obs,"title"),title,ts)
            if not title_hits:continue
            parcel_hits=matches(refs(obs,"parcel"),parcel,ps) if parcel is not None else []
            if parcel is not None and (not parcel_hits or obs.get("page") is None):
                continue
            observations.append({"page":obs.get("page"),"origin":obs["origin"],
                "title_matches_private":[{"number":n,"suffix":s} for n,s in title_hits],
                "parcel_matches_private":[{"number":n,"suffix":s} for n,s in parcel_hits],
                "area_candidates_private":obs.get("area_candidates_private",[]),
                "cadastral_hint":obs.get("cadastral_term_present") is True})
        if not observations:continue
        actual=row.get("registry_scope")
        scope_state=("SCOPE_UNVERIFIED" if actual is None else "SCOPE_NOT_REQUESTED" if scope is None
                     else "SCOPE_MATCH" if scope==actual else "OTHER_SCOPE")
        links=[]
        # Existing graph edges use only unqualified references. A qualified
        # query cannot borrow an edge for a different suffix or missing suffix.
        if ts is None and ps is None and scope_state not in ("OTHER_SCOPE","SCOPE_UNVERIFIED"):
            for edge in edges[sha]:
                page_evidence=[p for p in edge["page_evidence"] if p.get("title_reference")==title and
                               (parcel is None or p.get("parcel_reference")==parcel)
                               and any(o["page"]==p.get("page") and any(t["suffix"] is None for t in o["title_matches_private"])
                                       and (parcel is None or any(t["suffix"] is None for t in o["parcel_matches_private"])) for o in observations)]
                if not page_evidence:continue
                k=edge["source_scoped_key"]
                contexts=[]
                for loc in locations[tuple(k)]:
                    if sha not in loc.get("candidate_document_hashes_private",[]):continue
                    context={"geometry_kind":loc["geometry_kind"],
                        "source_area_m2":loc.get("source_area_m2"),
                        "governorate_intersections":loc["governorate_intersections"],
                        "delegation_intersections":loc["delegation_intersections"],
                        "admin_parent_conflicts":loc.get("admin_parent_conflicts",[]),
                        "legal_boundary_verified":False}
                    for field in ("sector_intersections","sector_boundary_contacts","sector_parent_conflicts",
                                  "district_intersections","district_boundary_contacts"):
                        if field in loc:
                            context[field]=loc[field]
                    contexts.append(context)
                links.append({"source_scoped_key_private":k,"status":edge["status"],
                              "page_evidence_private":page_evidence,
                              "spatial_context_candidates_private":contexts,"legally_verified":False})
        results.append({"document_sha256_private":sha,"scope_status":scope_state,"registry_scope":actual,
                        "observations_private":observations,"family_hints":row.get("family_hints",[]),
                        "first_pass_state":row.get("batch_status"),"deep_review_private":row.get("deep_review_private"),
                        "vector_candidate_count":row.get("vector_candidate_count"),
                        "candidate_links_private":links,"official_plan_verified":None,"ground_verified":False})
    if len(seen)!=index["counts"]["document_hashes"]:
        raise ValueError("INDEX_ACCOUNTING_MISMATCH")
    rank={"SCOPE_MATCH":0,"SCOPE_NOT_REQUESTED":1,"SCOPE_UNVERIFIED":2,"OTHER_SCOPE":3}
    results.sort(key=lambda r:(rank[r["scope_status"]],not bool(r["candidate_links_private"]),r["document_sha256_private"]))
    return {"kind":"PRIVATE_ATLAS_PROPERTY_DOSSIER_SEARCH","schema_version":1,
            "counts":{"searched_document_hashes":len(seen),"matched_documents":len(results),
                      "scope_states":dict(Counter(r["scope_status"] for r in results)),
                      "documents_with_candidate_links":sum(bool(r["candidate_links_private"]) for r in results),
                      "documents_with_spatial_context":sum(any(e["spatial_context_candidates_private"] for e in r["candidate_links_private"]) for r in results)},
            "matches_private":results,"official_plan_verified":None,
            "ground_verified":False,"public_export_permitted":False,
            "limitations":["Search candidates, not land ownership or parcel identity proof.",
             "Unqualified search may return multiple suffixes; suffix-qualified search requires an observed suffix.",
             "Spatial context describes source geometry, not independent georeferencing of the PDF."]}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--index",type=Path,required=True)
    p.add_argument("--query",type=Path,required=True)
    p.add_argument("--spatial-context",type=Path)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    i,ih=private_json(a.index);q,qh=private_json(a.query)
    s,sh=private_json(a.spatial_context) if a.spatial_context else (None,None)
    r=lookup(i,q,s)
    r["input_sha256_private"]={"index":ih,"query":qh,"spatial_context":sh}
    write_private_json(a.output,r)
    print(json.dumps({"private_dossier_written":True,"counts":r["counts"],"official_plan_verified":"UNKNOWN"}))

if __name__=="__main__":main()
