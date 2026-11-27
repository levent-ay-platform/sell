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
# Optional, independently versioned upstream ODbL context, not an INS crosswalk.
EXTENDED_PINNED = {
    "sector": ("bd698d6232a4478cc6d9300c64b1e0fa093515886e9a67c3c12de5e725249b5b",2083,2845889),
    "district": ("ff81f41f123f270a94703575bdb3e68d8e00a14f7318e66458236365ad6fd44d",5,178968),
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
            parent=p.get("governorate" if level=="delegation" else "delegation") if level in ("delegation","sector") and isinstance(p,dict) else None
            if not isinstance(code,str) or not code or code in seen:
                raise ValueError("ADMIN_REFERENCE_ID_MISSING_OR_DUPLICATE")
            seen.add(code)
            try:
                if level in ("delegation","sector") and not isinstance(parent,str):
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
                hit={"admin_id":code,"parent_governorate_id":parent if self.level!="sector" else None,
                     "intersection_area_m2":round(area,3),
                     "source_area_fraction":round(area/pg.area,9)}
                if self.level=="sector":
                    hit["parent_delegation_id"]=parent
                hits.append(hit)
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


def load_extended_reference(path,level,gov,deleg):
    """Require the exact previously audited medium-resolution public bytes."""
    sha,count,size=EXTENDED_PINNED[level]
    path=Path(path)
    if not path.is_file() or path.stat().st_size!=size:
        raise ValueError("EXTENDED_REFERENCE_SIZE_MISMATCH")
    raw=path.read_bytes()
    if len(raw)!=size or digest_bytes(raw)!=sha:
        raise ValueError("EXTENDED_REFERENCE_SHA_MISMATCH")
    data=json.loads(raw)
    if data.get("type")!="FeatureCollection" or len(data["features"])!=count:
        raise ValueError("EXTENDED_REFERENCE_COUNT_MISMATCH")
    layer=AdminLayer(data["features"],level)
    if layer.invalid:
        raise ValueError("EXTENDED_REFERENCE_INVALID_GEOMETRY")
    if level=="sector":
        parents={r[0]:r[1] for r in deleg.rows}
        for f in data["features"]:
            p=f["properties"]
            if p.get("delegation") not in parents or p.get("governorate")!=parents[p["delegation"]] or p["governorate"] not in gov.codes:
                raise ValueError("SECTOR_REFERENCE_PARENT_MISMATCH")
    return layer


def build_context(source,registry,graph,gov,deleg,sector=None,district=None):
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
    extra_layers={k:v for k,v in (("sector",sector),("district",district)) if v is not None}
    extra_counts={k:Counter() for k in extra_layers}
    for ordinal,item in enumerate(items,1):
        item=item if isinstance(item,dict) else {}
        k=key(item.get("source_scoped_key"))
        record={"ordinal_private":ordinal,"source_scoped_key":list(k) if k else None,
                "geometry_input_state":item.get("geometry_input_state") if item.get("geometry_input_state") in ("GEOMETRY_NOT_MAPPED","MISSING_OR_UNSUPPORTED_GEOMETRY","WGS84_POLYGON_DECLARED_NOT_VALIDATED","WGS84_NORMALIZED_CANDIDATE","GEOMETRY_CONVERSION_REVIEW_REQUIRED") else "UNSPECIFIED",
                "geometry_kind":item.get("geometry_kind") if item.get("geometry_kind") in
                ("CADASTRE_SOURCE","PLANNING_SOURCE","OTHER_SOURCE") else "UNKNOWN",
                "governorate_intersections":[],"delegation_intersections":[],
                "candidate_document_hashes_private":[],"legal_boundary_verified":False}
        for level in extra_layers:
            record[level+"_intersections"]=[]
            record[level+"_boundary_contacts"]=[]
        if sector is not None:
            record["sector_parent_conflicts"]=[]
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
            # These are upstream spatial candidates, not official current INS identities.
            extra_results={level:layer.intersections(g,pg) for level,layer in extra_layers.items()}
            for level,(hits,touches) in extra_results.items():
                record[level+"_intersections"]=hits
                record[level+"_boundary_contacts"]=touches
            if sector is not None:
                dp={r["admin_id"] for r in ds}
                record["sector_parent_conflicts"]=[r["admin_id"] for r in record["sector_intersections"]
                                                   if r["parent_delegation_id"] not in dp]
            for level,(hits,_) in extra_results.items():
                extra_counts[level].update(r["admin_id"] for r in hits)
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
    result={"kind":"PRIVATE_ATLAS_SPATIAL_CONTEXT","schema_version":1,
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
    if extra_layers:
        result["extended_reference_status"]="UPSTREAM_SPATIAL_CONTEXT_ONLY_NOT_INS_CROSSWALK"
        result["reference_quality"]["extended_layers"]={
            level:{"units":len(layer.codes),"invalid":layer.invalid}
            for level,layer in extra_layers.items()}
        result["reference_quality"]["complete_valid_geometry"] &= all(not layer.invalid for layer in extra_layers.values())
        for level,layer in extra_layers.items():
            result[level+"_context_counts_private"]=[
                {"admin_id":code,"intersecting_source_rows":extra_counts[level][code],
                 "official_plan_coverage":None} for code in sorted(layer.codes)]
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--geometries",type=Path,required=True)
    p.add_argument("--registry",type=Path,required=True)
    p.add_argument("--graph",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--sectors",type=Path,help="Exact pinned public medium sector GeoJSON; optional")
    p.add_argument("--districts",type=Path,help="Exact pinned public medium district GeoJSON; optional")
    a=p.parse_args()
    source,sh=private_json(a.geometries)
    registry,rh=private_json(a.registry)
    graph,gh=private_json(a.graph)
    gov,deleg=load_reference()
    sector=load_extended_reference(a.sectors,"sector",gov,deleg) if a.sectors else None
    district=load_extended_reference(a.districts,"district",gov,deleg) if a.districts else None
    result=build_context(source,registry,graph,gov,deleg,sector,district)
    result["input_sha256_private"]={"geometries":sh,"registry":rh,"graph":gh}
    result["reference_sha256"]=dict((n,v[0]) for n,v in PINNED.items())
    for level,path in (("sector",a.sectors),("district",a.districts)):
        if path:
            result["reference_sha256"][level]=EXTENDED_PINNED[level][0]
    if a.sectors or a.districts:
        result["extended_reference_attribution"]="© OpenStreetMap contributors · Map Tunisia by Alaa Taieb (ODbL 1.0)"
    write_private_json(a.output,result)
    print(json.dumps({"private_spatial_context_written":True,"counts":result["counts"],
                      "official_plan_coverage":"UNKNOWN","public_export_permitted":False}))


if __name__=="__main__":
    main()
