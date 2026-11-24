#!/usr/bin/env python3
"""Private spatial context for source-scoped Atlas records, not legal boundaries.

Uses the pinned existing administrative geography. A spatial intersection is
context only: it does not infer a registry scope, identity, ownership, or
georeference a PDF. No output geometry or source URLs are published.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path

from pyproj import Transformer
from shapely import get_coordinates
from shapely.geometry import shape
from shapely.ops import transform
from shapely.strtree import STRtree
from shapely.errors import GEOSException

from scripts.atlas_cadastre_evidence_audit import write_private_json

REFERENCE = Path(__file__).resolve().parents[1]/"apps/android/app/src/main/assets/atlas-admin"
PINNED = {
    "governorates.geojson": ("146ddd3be8be14f401f718afb40c1a87394b6426ed5b15d048953ef19c47de1b",24),
    "delegations.geojson": ("947ae6be5a0e4f493c6a7e0fb2be6140621d79f82ee1d4d31ee98c34391ca078",279),
}
FIELDS = ("namespace","service_id","layer_id","object_id","version")
MAX_RECORDS = 100000
MAX_VERTICES = 250000
MAX_INPUT_BYTES = 128*1024**2
PROJECT = Transformer.from_crs("EPSG:4326","EPSG:6933",always_xy=True).transform


def digest_bytes(raw):
    return hashlib.sha256(raw).hexdigest()


def private_json(path):
    path=Path(path)
    if (path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077
            or path.stat().st_size>MAX_INPUT_BYTES):
        raise ValueError("BOUNDED_PRIVATE_INPUT_REQUIRED")
    raw=path.read_bytes()
    if len(raw)>MAX_INPUT_BYTES:
        raise ValueError("INPUT_SIZE_LIMIT")
    return json.loads(raw),digest_bytes(raw)


def key(value):
    if isinstance(value,dict):
        value=[value.get(f) for f in FIELDS]
    if not isinstance(value,list) or len(value)!=5 or any(
            not isinstance(x,str) or not 1<=len(x)<=256 or any(ord(c)<32 for c in x) for x in value):
        return None
    return tuple(value)


def polygon(value):
    if not isinstance(value,dict) or value.get("type") not in ("Polygon","MultiPolygon"):
        raise ValueError("POLYGON_REQUIRED")
    try:
        g=shape(value)
        coords=get_coordinates(g,include_z=False)
        if (g.is_empty or g.has_z or len(coords)>MAX_VERTICES or len(coords)<4 or
                not g.is_valid or not math.isfinite(g.area) or g.area<=0 or
                any(not math.isfinite(float(v)) for xy in coords for v in xy)):
            raise ValueError("INVALID_OR_OVERSIZED_POLYGON")
        x0,y0,x1,y1=g.bounds
        if not (7<=x0<=x1<=12.5 and 30<=y0<=y1<=38):
            raise ValueError("OUTSIDE_TUNISIA_OR_WRONG_CRS")
        projected=transform(PROJECT,g)
        if not projected.is_valid or not math.isfinite(projected.area) or projected.area<=0:
            raise ValueError("INVALID_PROJECTED_POLYGON")
        return g,projected
    except (GEOSException,TypeError,KeyError,IndexError,OverflowError) as exc:
        raise ValueError("INVALID_POLYGON") from exc


class AdminLayer:
    def __init__(self, features, level):
        if not isinstance(features,list):
            raise ValueError("ADMIN_FEATURES_REQUIRED")
        self.level=level
        self.rows=[]
        self.invalid=[]
        seen=set()
        for ordinal,f in enumerate(features,1):
            p=f.get("properties",{}) if isinstance(f,dict) else {}
            code=p.get("id") if isinstance(p,dict) else None
            parent=p.get("governorate") if level=="delegation" and isinstance(p,dict) else None
            if not isinstance(code,str) or not code or code in seen:
                raise ValueError("ADMIN_REFERENCE_ID_MISSING_OR_DUPLICATE")
            seen.add(code)
            try:
                if level=="delegation" and not isinstance(parent,str):
                    raise ValueError("ADMIN_PARENT_MISSING")
                g,pg=polygon(f.get("geometry"))
                self.rows.append((code,parent,g,pg))
            except ValueError as exc:
                self.invalid.append({"id":code,"reason":str(exc),"ordinal":ordinal})
        self.tree=STRtree([r[2] for r in self.rows])
        self.codes=seen

    def intersections(self,g,pg):
        hits=[]
        touches=[]
        for i in self.tree.query(g,predicate="intersects"):
            code,parent,ref,pref=self.rows[int(i)]
            # Positive area excludes shared-edge/point-only contacts.
            area=pg.intersection(pref).area
            if not math.isfinite(area):
                raise ValueError("NONFINITE_INTERSECTION")
            if area<=0:
                touches.append(code)
            else:
                hits.append({"admin_id":code,"parent_governorate_id":parent,
                             "intersection_area_m2":round(area,3),
                             "source_area_fraction":round(area/pg.area,9)})
        return sorted(hits,key=lambda r:r["admin_id"]),sorted(touches)


def load_reference():
    layers=[]
    for file,(sha,count) in PINNED.items():
        raw=(REFERENCE/file).read_bytes()
        if digest_bytes(raw)!=sha:
            raise ValueError("ADMIN_REFERENCE_SHA_MISMATCH")
        data=json.loads(raw)
        if data.get("type")!="FeatureCollection" or len(data["features"])!=count:
            raise ValueError("ADMIN_REFERENCE_COUNT_MISMATCH")
        layers.append(AdminLayer(data["features"],"governorate" if count==24 else "delegation"))
    return tuple(layers)


def build_context(source,registry,graph,gov,deleg):
    if (source.get("kind")!="PRIVATE_ATLAS_SOURCE_GEOMETRIES" or source.get("crs")!="EPSG:4326"
            or source.get("public_export_permitted") is not False):
        raise ValueError("EXPLICIT_PRIVATE_WGS84_SOURCE_REQUIRED")
    if (registry.get("kind")!="PRIVATE_ATLAS_SOURCE_REGISTRY"
            or graph.get("kind")!="PRIVATE_CANDIDATE_GRAPH"
            or registry.get("public_export_permitted") is not False
            or graph.get("public_export_permitted") is not False):
        raise ValueError("PRIVATE_REGISTRY_AND_GRAPH_REQUIRED")
    if source.get("snapshot_sha256_private")!=registry.get("snapshot_sha256_private") or not source.get("snapshot_sha256_private"):
        raise ValueError("SOURCE_SNAPSHOT_BINDING_MISMATCH")
    items=source.get("records_private")
    if not isinstance(items,list) or len(items)>MAX_RECORDS:
        raise ValueError("BOUNDED_SOURCE_GEOMETRIES_REQUIRED")
    candidates={key(r) for r in registry["candidate_source_records_private"]}
    if None in candidates or len(candidates)!=len(registry["candidate_source_records_private"]):
        raise ValueError("INVALID_CANDIDATE_SOURCE_KEYS")
    edges=defaultdict(set)
    for e in graph["edges"]:
        k=key(e.get("source_scoped_key"))
        d=e.get("document_sha256")
        if (k not in candidates or e.get("legally_verified") is not False
                or e.get("status") not in ("CANDIDATE","AMBIGUOUS_CANDIDATE")
                or not isinstance(d,str) or len(d)!=64 or any(c not in "0123456789abcdef" for c in d)):
            raise ValueError("GRAPH_REGISTRY_BINDING_MISMATCH")
        edges[k].add(d)
    duplicate_counts=Counter(key(r.get("source_scoped_key")) for r in items if isinstance(r,dict))
    records=[]
    governorate_counts=Counter()
    delegation_counts=Counter()
    for ordinal,item in enumerate(items,1):
        item=item if isinstance(item,dict) else {}
        k=key(item.get("source_scoped_key"))
        record={"ordinal_private":ordinal,"source_scoped_key":list(k) if k else None,
                "geometry_input_state":item.get("geometry_input_state") if item.get("geometry_input_state") in ("GEOMETRY_NOT_MAPPED","MISSING_OR_UNSUPPORTED_GEOMETRY","WGS84_POLYGON_DECLARED_NOT_VALIDATED") else "UNSPECIFIED",
                "geometry_kind":item.get("geometry_kind") if item.get("geometry_kind") in
                ("CADASTRE_SOURCE","PLANNING_SOURCE","OTHER_SOURCE") else "UNKNOWN",
                "governorate_intersections":[],"delegation_intersections":[],
                "candidate_document_hashes_private":[],"legal_boundary_verified":False}
        try:
            g,pg=polygon(item.get("geometry"))
            record["source_area_m2"]=round(pg.area,3)
            gs,gt=gov.intersections(g,pg)
            ds,dt=deleg.intersections(g,pg)
            record.update(governorate_intersections=gs,delegation_intersections=ds,
                          governorate_boundary_contacts=gt,delegation_boundary_contacts=dt)
            gp={r["admin_id"] for r in gs}
            record["admin_parent_conflicts"]=[r["admin_id"] for r in ds if r["parent_governorate_id"] not in gp]
            record["status"]="SPATIAL_CONTEXT_CANDIDATE" if gs or ds else "NO_POSITIVE_AREA_INTERSECTION_OBSERVED"
            governorate_counts.update(gp)
            delegation_counts.update(r["admin_id"] for r in ds)
            if k is None:
                record["link_state"]="SOURCE_KEY_UNRESOLVED"
            elif duplicate_counts[k]>1:
                record["link_state"]="DUPLICATE_GEOMETRY_KEY"
            elif k not in candidates:
                record["link_state"]="SOURCE_NOT_IN_CANDIDATE_REGISTRY"
            else:
                record["candidate_document_hashes_private"]=sorted(edges[k])
                record["link_state"]="SCOPED_DOCUMENT_CANDIDATES" if edges[k] else "NO_DOCUMENT_CANDIDATE"
        except (ValueError,GEOSException) as exc:
            record.update(status="GEOMETRY_REVIEW_REQUIRED",reason=str(exc) if isinstance(exc,ValueError) else "GEOS_OPERATION_FAILED",
                          link_state="NOT_EVALUATED")
        records.append(record)
    return {"kind":"PRIVATE_ATLAS_SPATIAL_CONTEXT","schema_version":1,
            "source_snapshot_sha256_private":source["snapshot_sha256_private"],
            "reference_quality":{"governorate_units":len(gov.codes),"delegation_units":len(deleg.codes),
                "invalid_governorates":gov.invalid,"invalid_delegations":deleg.invalid,
                "complete_valid_geometry":not gov.invalid and not deleg.invalid},
            "counts":{"source_geometry_rows":len(records),
                      "states":dict(Counter(r["status"] for r in records)),
                      "link_states":dict(Counter(r["link_state"] for r in records)),
                      "rows_with_candidate_documents":sum(bool(r["candidate_document_hashes_private"]) for r in records)},
            "governorate_context_counts_private":[{"admin_id":c,"intersecting_source_rows":governorate_counts[c],
                    "official_plan_coverage":None} for c in sorted(gov.codes)],
            "delegation_context_counts_private":[{"admin_id":c,"intersecting_source_rows":delegation_counts[c],
                    "official_plan_coverage":None} for c in sorted(deleg.codes)],
            "rows_private":records,"official_plan_count":None,"ground_verified":False,
            "public_export_permitted":False}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--geometries",type=Path,required=True)
    p.add_argument("--registry",type=Path,required=True)
    p.add_argument("--graph",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    source,sh=private_json(a.geometries)
    registry,rh=private_json(a.registry)
    graph,gh=private_json(a.graph)
    gov,deleg=load_reference()
    result=build_context(source,registry,graph,gov,deleg)
    result["input_sha256_private"]={"geometries":sh,"registry":rh,"graph":gh}
    result["reference_sha256"]=dict((n,v[0]) for n,v in PINNED.items())
    write_private_json(a.output,result)
    print(json.dumps({"private_spatial_context_written":True,"counts":result["counts"],
                      "official_plan_coverage":"UNKNOWN","public_export_permitted":False}))


if __name__=="__main__":
    main()
