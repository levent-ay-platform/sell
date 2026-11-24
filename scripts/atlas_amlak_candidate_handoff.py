#!/usr/bin/env python3
"""Stage document-bound candidate parcels for the existing Amlak importer.

Offline private preparation only. No authorization, DB write or publication.
Never upgrades candidate classification; the approved bridge stays separate.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import tempfile
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_georeference_case import assess, digest, new_output
from scripts.atlas_property_dossier import number, suffix, refs
from scripts.atlas_property_index import _sha
from scripts.atlas_public_projection import _validated_geometry
from scripts.atlas_spatial_context import private_json

def text(value,limit=100):
    if (not isinstance(value,str) or not value.strip() or len(value)>limit
        or any(ord(c)<32 or ord(c)==127 for c in value)):
        raise ValueError("INVALID_REVIEWED_SELECTION_TEXT")
    return value

def reference_pair(value,minimum):
    if not isinstance(value,dict) or set(value)!={"number","suffix"}:
        raise ValueError("EXPLICIT_REFERENCE_PAIR_REQUIRED")
    return number(value["number"],minimum),suffix(value["suffix"])

def present(obs,kind,requested,minimum):
    for n,s in refs(obs,kind):
        try:
            if (number(n,minimum),suffix(s))==requested:return True
        except (ValueError,TypeError):
            continue
    return False

def stage_one(index,index_sha,selection,case_path,controls_path,qa_path):
    if (not isinstance(selection,dict) or selection.get("kind")!="PRIVATE_ATLAS_CANDIDATE_SELECTION"
        or selection.get("index_sha256")!=index_sha):
        raise ValueError("SELECTION_INDEX_BINDING_MISMATCH")
    case,ch=private_json(case_path)
    controls,ph=private_json(controls_path)
    qa,qh=private_json(qa_path)
    sha=_sha(selection.get("document_sha256"))
    if (selection.get("case_sha256")!=ch or selection.get("controls_sha256")!=ph
        or selection.get("qa_sha256")!=qh or case.get("index_sha256_private")!=index_sha
        or case.get("document_sha256_private")!=sha):
        raise ValueError("SELECTION_EVIDENCE_BINDING_MISMATCH")
    docs=[r for r in index["documents_private"] if r["document_sha256"]==sha]
    if (len(docs)!=1 or docs[0].get("official_plan_verified") is not None
        or docs[0].get("ground_verified") is not False):
        raise ValueError("UNIQUE_CANDIDATE_DOCUMENT_REQUIRED")
    doc=docs[0]
    scope=text(selection.get("registry_scope"))
    locality=text(selection.get("locality_key"))
    if doc.get("registry_scope")!=scope:
        raise ValueError("REVIEWED_INDEX_SCOPE_REQUIRED")
    title=reference_pair(selection.get("title"),3)
    parcel=reference_pair(selection.get("parcel"),1)
    page=case["page"]
    if selection.get("page")!=page or selection.get("outline_ordinal")!=controls.get("outline_ordinal"):
        raise ValueError("SELECTION_PAGE_OUTLINE_MISMATCH")
    observed=[o for o in doc["observations_private"] if o.get("page")==page
              and present(o,"title",title,3) and present(o,"parcel",parcel,1)]
    if not observed:
        raise ValueError("SAME_OBSERVATION_TITLE_PARCEL_REQUIRED")
    # Recompute QA from exact case and controls, rather than trusting a copied
    # result or a UI success flag. No new approval is inferred.
    with tempfile.TemporaryDirectory(prefix="atlas-candidate-qa-") as t:
        fresh=assess(case_path,controls_path,Path(t).resolve()/"qa.json")
    if fresh!=qa:
        raise ValueError("QA_RECOMPUTE_MISMATCH")
    g=qa["qa"]
    geometry=None
    if g.get("geometry_status")=="GEOREFERENCED_CANDIDATE":
        geometry=_validated_geometry(g["geometry4326"])
    classification="CANDIDATE" if geometry is not None else "PLAN_NOT_GEOREFERENCED"
    def formatted(pair):
        return pair[0] if pair[1] is None else pair[0]+"/"+pair[1]
    # Internal candidate namespace; this is not an issuer or an official
    # identifier. Exact document/page/outline identity and revision bind the DTO.
    external=digest([sha,page,qa["outline_sha256_private"]])
    version=digest([ch,ph,qh,selection])
    record={"namespace":"ATLAS_PRIVATE_REVIEW","sourceId":"ARCHIVE_DOCUMENT_CANDIDATES",
        "layerId":"PAGE_OUTLINE","externalId":external,"version":version,"sha256":sha,
        "registryScope":scope,"titleReference":formatted(title),"plotReference":formatted(parcel),
        "localityKey":locality,"classification":classification,"allowBoundaryRedistribution":False,
        "geometry4326":geometry}
    evidence={"document_sha256_private":sha,"case_sha256_private":ch,"controls_sha256_private":ph,
        "qa_sha256_private":qh,"page":page,"outline_ordinal":controls["outline_ordinal"],
        "matching_observation_count":len(observed),"classification":classification,
        "scope_reviewed_in_index":True,"locality_operator_selected":True,"authority_verified":False,
        "title_parcel_link":"UNVERIFIED","ground_verified":False,
        "import_authorized":False,"public_export_permitted":False}
    return record,evidence

def stage_batch(index_path,manifest_path,output):
    index,ih=private_json(index_path)
    manifest,mh=private_json(manifest_path)
    if (index.get("kind")!="PRIVATE_ATLAS_PROPERTY_INDEX" or index.get("public_export_permitted") is not False
        or index.get("official_plan_count") is not None):
        raise ValueError("PRIVATE_CANDIDATE_INDEX_REQUIRED")
    if (manifest.get("kind")!="PRIVATE_ATLAS_CANDIDATE_HANDOFF_MANIFEST" or manifest.get("index_sha256")!=ih
        or not isinstance(manifest.get("items"),list) or not 1<=len(manifest["items"])<=1000):
        raise ValueError("EXPLICIT_BOUNDED_HANDOFF_MANIFEST_REQUIRED")
    output=new_output(output)
    prepared=[]
    ledger=[]
    for i,item in enumerate(manifest["items"],1):
        try:
            selection,sh=private_json(Path(item["selection_path"]))
            rec,ev=stage_one(index,ih,selection,Path(item["case_path"]),Path(item["controls_path"]),Path(item["qa_path"]))
            prepared.append((i,rec,ev,sh))
        except (ValueError,TypeError,KeyError,OSError) as exc:
            # Retain the failed item ordinal; do not echo private paths or
            # exception content to console or an application client.
            ledger.append({"ordinal":i,"state":"REVIEW_REQUIRED","reason_type":type(exc).__name__})
    keys=Counter(r["externalId"] for _,r,_,_ in prepared)
    records=[]
    for i,r,e,sh in prepared:
        if keys[r["externalId"]]>1:
            ledger.append({"ordinal":i,"state":"DUPLICATE_OUTLINE_SELECTION_REVIEW","selection_sha256_private":sh})
            continue
        records.append(r)
        ledger.append({"ordinal":i,"state":"CANDIDATE_STAGED","selection_sha256_private":sh,"evidence_private":e})
    if len(ledger)!=len(manifest["items"]):
        raise ValueError("HANDOFF_ACCOUNTING_MISMATCH")
    report={"kind":"PRIVATE_ATLAS_CANDIDATE_HANDOFF","schema_version":1,
        "index_sha256_private":ih,"manifest_sha256_private":mh,
        "counts":{"requested":len(ledger),"staged":len(records),"unresolved":len(ledger)-len(records),
                  "classifications":dict(Counter(r["classification"] for r in records))},
        "ledger_private":sorted(ledger,key=lambda e:e["ordinal"]),
        "import_authorized":False,"database_updated":False,"public_export_permitted":False}
    output.mkdir(mode=0o700)
    write_private_json(output/"candidate-import-draft.json",{"schemaVersion":1,
        "distribution":"PRIVATE_CANDIDATE_REVIEW_NOT_AUTHORIZED_FOR_IMPORT",
        "apiContract":"ReviewedAtlasParcel","records":records})
    write_private_json(output/"handoff-report.json",report)
    return report

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--index",type=Path,required=True)
    p.add_argument("--manifest",type=Path,required=True)
    p.add_argument("--output-directory",type=Path,required=True)
    a=p.parse_args()
    r=stage_batch(a.index,a.manifest,a.output_directory)
    print(json.dumps({"private_handoff_draft_written":True,"counts":r["counts"],
        "database_updated":False,"import_authorized":False}))
if __name__=="__main__":main()
