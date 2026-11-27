#!/usr/bin/env python3
"""Federate owner-reviewed source snapshots and a private property index.

Offline only. Every source row is retained; cross-snapshot key collisions are
quarantined. Spatial intersections and document links remain candidates.
"""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
import hashlib
import json
import os
from pathlib import Path

from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_source_registry import export_registry
from scripts.atlas_document_linkage import build_candidate_graph
from scripts.atlas_property_index import _sha
from scripts.atlas_spatial_context import private_json, key, load_reference, build_context, PINNED, EXTENDED_PINNED, load_extended_reference
from scripts.atlas_property_dossier import lookup

def fingerprint(obj):
    return hashlib.sha256(json.dumps(obj,sort_keys=True,ensure_ascii=False,separators=(",",":"),
                                    allow_nan=False).encode("utf-8")).hexdigest()

def federate(index, scopes, source_pairs, gov, deleg, sector=None, district=None):
    if (index.get("kind")!="PRIVATE_ATLAS_PROPERTY_INDEX" or index.get("public_export_permitted") is not False
            or index.get("official_plan_count") is not None):
        raise ValueError("PRIVATE_CANDIDATE_INDEX_REQUIRED")
    if not isinstance(scopes,dict):
        raise ValueError("REVIEWED_SCOPE_MAPPING_REQUIRED")
    docs=index["documents_private"]
    seen=set()
    enriched=[]
    for row in docs:
        sha=_sha(row["document_sha256"])
        if sha in seen or row.get("official_plan_verified") is not None or row.get("ground_verified") is not False:
            raise ValueError("INVALID_OR_DUPLICATE_INDEX_DOCUMENT")
        seen.add(sha)
        obs=[o for o in row["observations_private"] if o.get("page") is not None]
        enriched.append({"document_sha256":sha,"page_reference_evidence_private":obs})
    if len(seen)!=index["counts"]["document_hashes"] or set(scopes)-seen:
        raise ValueError("INDEX_SCOPE_ACCOUNTING_MISMATCH")
    all_keys=Counter()
    for registry,geo in source_pairs:
        for row in registry["rows_private"]:
            k=key(row["source_record_private"])
            if k is not None:all_keys[k]+=1
        if len(registry["rows_private"])!=registry["counts"]["mapped_rows"] or len(geo["records_private"])!=len(registry["rows_private"]):
            raise ValueError("SOURCE_ROW_GEOMETRY_ACCOUNTING_MISMATCH")
    conflicts={k for k,n in all_keys.items() if n>1}
    candidates=[]
    for registry,geo in source_pairs:
        candidates.extend(r for r in registry["candidate_source_records_private"] if key(r) not in conflicts)
    audit={"schema_version":3,"review_queue":enriched,"unresolved_pdf_documents":[],
           "counts":{"distinct_pdf_sha256":len(seen)}}
    graph=build_candidate_graph(audit,scopes,candidates)
    graph_sha=fingerprint(graph)
    extended={k:v for k,v in (("sector",sector),("district",district)) if v is not None}
    contexts=[]
    ledger=[]
    source_stats=[]
    for n,(registry,geo) in enumerate(source_pairs,1):
        allowed=[r for r in registry["candidate_source_records_private"] if key(r) not in conflicts]
        local_keys={key(r) for r in allowed}
        local_graph={**graph,"edges":[e for e in graph["edges"] if key(e["source_scoped_key"]) in local_keys]}
        context=build_context(geo,{**registry,"candidate_source_records_private":allowed},local_graph,gov,deleg,sector,district)
        for row,spatial in zip(registry["rows_private"],context["rows_private"]):
            k=key(row["source_record_private"])
            reasons=list(row["reasons"])
            if k in conflicts and "CROSS_SNAPSHOT_OR_SOURCE_KEY_COLLISION" not in reasons:
                reasons.append("CROSS_SNAPSHOT_OR_SOURCE_KEY_COLLISION")
                spatial["link_state"]="FEDERATED_KEY_COLLISION"
                spatial["candidate_document_hashes_private"]=[]
            ledger.append({**row,"source_number_private":n,"reasons":reasons})
            contexts.append({**spatial,"source_number_private":n})
        source_stats.append({"source_number":n,"snapshot_kind":registry["snapshot_kind"],
            "mapped_rows":len(registry["rows_private"]),"eligible_source_rows":len(allowed),
            "geometry_states":context["counts"]["states"]})
    for row in docs:
        row["registry_scope"]=scopes.get(row["document_sha256"])
    merged={**index,"candidate_graph_private":graph,
            "counts":{**index["counts"],"documents_with_candidate_links":graph["counts"]["documents_with_candidates"]},
            "federated_graph_sha256_private":graph_sha}
    geo_counts=Counter()
    gov_counts=Counter()
    del_counts=Counter()
    for row in contexts:
        geo_counts[row["status"]]+=1
        gov_counts.update({r["admin_id"] for r in row["governorate_intersections"]})
        del_counts.update({r["admin_id"] for r in row["delegation_intersections"]})
    spatial={"kind":"PRIVATE_ATLAS_SPATIAL_CONTEXT","schema_version":1,
        "federated_graph_sha256_private":graph_sha,"rows_private":contexts,
        "reference_sha256":{name:v[0] for name,v in PINNED.items()},
        "reference_quality":{"governorate_units":len(gov.codes),"delegation_units":len(deleg.codes),
                             "invalid_governorates":gov.invalid,"invalid_delegations":deleg.invalid},
        "counts":{"source_geometry_rows":len(contexts),"states":dict(geo_counts),
                  "link_states":dict(Counter(r["link_state"] for r in contexts)),
                  "rows_with_candidate_documents":sum(bool(r["candidate_document_hashes_private"]) for r in contexts)},
        "governorate_context_counts_private":[{"admin_id":c,"intersecting_source_rows":gov_counts[c],
                                             "official_plan_coverage":None} for c in sorted(gov.codes)],
        "delegation_context_counts_private":[{"admin_id":c,"intersecting_source_rows":del_counts[c],
                                             "official_plan_coverage":None} for c in sorted(deleg.codes)],
        "official_plan_count":None,"ground_verified":False,"public_export_permitted":False}
    if extended:
        spatial["extended_reference_status"]="UPSTREAM_SPATIAL_CONTEXT_ONLY_NOT_INS_CROSSWALK"
        spatial["reference_quality"]["extended_layers"]={
            level:{"units":len(layer.codes),"invalid":layer.invalid}
            for level,layer in extended.items()}
        spatial["reference_quality"]["complete_valid_geometry"]=not gov.invalid and not deleg.invalid and all(not x.invalid for x in extended.values())
        for level,layer in extended.items():
            counts=Counter(hit["admin_id"] for row in contexts for hit in row.get(level+"_intersections",[]))
            spatial[level+"_context_counts_private"]=[
                {"admin_id":code,"intersecting_source_rows":counts[code],
                 "official_plan_coverage":None} for code in sorted(layer.codes)]
    registry={"kind":"PRIVATE_ATLAS_FEDERATED_REGISTRY","schema_version":1,
              "rows_private":ledger,"candidate_source_records_private":candidates,
              "counts":{"mapped_rows":len(ledger),"eligible_source_rows":len(candidates),
                        "unresolved_rows":len(ledger)-len(candidates),"colliding_keys":len(conflicts),
                        "unresolved_reasons":dict(Counter(reason for row in ledger for reason in row["reasons"]))},
              "public_export_permitted":False,"official_plan_count":None}
    if len(ledger)!=len(contexts):
        raise ValueError("FEDERATED_ACCOUNTING_MISMATCH")
    report={"kind":"PRIVATE_ATLAS_FEDERATED_REVIEW_SUMMARY","schema_version":1,
        "counts":{"document_hashes":len(docs),"source_snapshots":len(source_pairs),
                  **registry["counts"],"documents_with_candidate_links":graph["counts"]["documents_with_candidates"],
                  "candidate_edges":graph["counts"]["candidate_edges"],
                  "geometry_states":dict(geo_counts),"rows_with_candidate_documents":spatial["counts"]["rows_with_candidate_documents"]},
        "sources_private":source_stats,"official_plan_count":None,"public_export_permitted":False}
    return merged,registry,spatial,report

def run(index_path,manifest_path,scopes_path,output,query_path=None,sectors_path=None,districts_path=None):
    index,ih=private_json(index_path)
    scopes,sh=private_json(scopes_path)
    manifest,mh=private_json(manifest_path)
    if manifest.get("kind")!="PRIVATE_ATLAS_FEDERATED_INPUTS":
        raise ValueError("EXPLICIT_SOURCE_MANIFEST_REQUIRED")
    items=manifest.get("sources")
    if not isinstance(items,list) or not 1<=len(items)<=32:
        raise ValueError("BOUNDED_SOURCE_LIST_REQUIRED")
    output=Path(output)
    if output.exists() or output.is_symlink() or any(p.is_symlink() for p in output.parents):
        raise ValueError("NEW_PRIVATE_OUTPUT_DIRECTORY_REQUIRED")
    if not output.parent.is_dir() or output.parent.stat().st_mode&0o077:
        raise ValueError("EXISTING_PRIVATE_OUTPUT_PARENT_REQUIRED")
    pairs=[]
    proofs=[]
    seen=set()
    for entry in items:
        db=Path(entry["snapshot_path"])
        mp=Path(entry["mapping_path"])
        mapping,ph=private_json(mp)
        if db.resolve() in seen:
            raise ValueError("DUPLICATE_SOURCE_SNAPSHOT_PATH")
        seen.add(db.resolve())
        registry=export_registry(db,mapping,include_geometries=True)
        geo=registry.pop("geometry_projection_private")
        pairs.append((registry,geo))
        proofs.append({"snapshot_sha256_private":registry["snapshot_sha256_private"],"mapping_sha256_private":ph})
    gov,deleg=load_reference()
    sector=load_extended_reference(sectors_path,"sector",gov,deleg) if sectors_path else None
    district=load_extended_reference(districts_path,"district",gov,deleg) if districts_path else None
    merged,registry,spatial,report=federate(index,scopes,pairs,gov,deleg,sector,district)
    for level,path in (("sector",sectors_path),("district",districts_path)):
        if path:
            spatial["reference_sha256"][level]=EXTENDED_PINNED[level][0]
    if sector is not None or district is not None:
        spatial["extended_reference_attribution"]="© OpenStreetMap contributors · Map Tunisia by Alaa Taieb (ODbL 1.0)"
        report["extended_reference_quality"]=spatial["reference_quality"]["extended_layers"]
        report["reference_sha256"]=spatial["reference_sha256"]
    report["inputs_private"]={"index_sha256":ih,"scopes_sha256":sh,"manifest_sha256":mh,"source_proofs":proofs}
    dossier=None
    if query_path is not None:
        query,qh=private_json(query_path)
        dossier=lookup(merged,query,spatial)
        report["query_sha256_private"]=qh
        report["query_counts"]=dossier["counts"]
    output.mkdir(mode=0o700)
    os.chmod(output,0o700)
    for name,obj in (("property-index-federated.json",merged),("registry.json",registry),
                     ("spatial-context.json",spatial),("summary.json",report)):
        write_private_json(output/name,obj)
    if dossier is not None:write_private_json(output/"property-dossier.json",dossier)
    return report

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--index",type=Path,required=True)
    p.add_argument("--sources-manifest",type=Path,required=True)
    p.add_argument("--scopes",type=Path,required=True)
    p.add_argument("--output-directory",type=Path,required=True)
    p.add_argument("--query",type=Path)
    p.add_argument("--sectors",type=Path)
    p.add_argument("--districts",type=Path)
    a=p.parse_args()
    r=run(a.index,a.sources_manifest,a.scopes,a.output_directory,a.query,a.sectors,a.districts)
    print(json.dumps({"private_federated_report_written":True,"counts":r["counts"],
                      "official_plans_verified":"UNKNOWN"}))

if __name__=="__main__":main()
