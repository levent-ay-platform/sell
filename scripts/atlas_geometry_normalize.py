#!/usr/bin/env python3
"""Strict offline polygon conversion for reviewed Atlas source mappings.

Source CRS is explicit, never inferred from coordinates or a title number.
No repair/simplification, network grid downloads, ownership or legal assertions.
"""
from __future__ import annotations
import math
import re
from functools import lru_cache
from pyproj import CRS, Transformer, network
from pyproj.exceptions import ProjError
from shapely.geometry import Polygon, MultiPolygon, mapping
from shapely.ops import transform
from shapely.errors import GEOSException
from shapely.geometry.polygon import orient

MAX_RINGS=256
MAX_VERTICES=250000
ALIASES={102100:3857,102113:3857,900913:3857}

def crs_code(value):
    if not isinstance(value,str) or not re.fullmatch(r"(EPSG|ESRI):[0-9]{3,6}",value):
        raise ValueError("EXPLICIT_AUTHORITY_CRS_REQUIRED")
    authority,raw=value.split(":")
    n=int(raw)
    if n in ALIASES:
        return "EPSG:"+str(ALIASES[n])
    return authority+":"+str(n)

def embedded_crs(value):
    sr=value.get("spatialReference")
    if sr is None:
        return None
    if not isinstance(sr,dict) or set(sr)-{"wkid","latestWkid"}:
        raise ValueError("UNSUPPORTED_EMBEDDED_SPATIAL_REFERENCE")
    codes=[]
    for name in ("wkid","latestWkid"):
        if name in sr:
            n=sr[name]
            if type(n) is not int or not 100<=n<=999999:
                raise ValueError("INVALID_EMBEDDED_WKID")
            codes.append("EPSG:"+str(ALIASES.get(n,n)))
    if not codes or len(set(codes))!=1:
        raise ValueError("EMBEDDED_WKID_CONFLICT")
    return codes[0]

@lru_cache(maxsize=32)
def transformer_for(code):
    # No downloads, no ballpark datum shift. Missing required local grids fail.
    network.set_network_enabled(False)
    source=CRS.from_user_input(code)
    if not (source.is_projected or source.is_geographic):
        raise ValueError("UNSUPPORTED_SOURCE_CRS")
    return Transformer.from_crs(source,"EPSG:4326",always_xy=True,
                                allow_ballpark=False,only_best=True)

def ring(value,budget):
    if not isinstance(value,list) or len(value)<4:
        raise ValueError("INVALID_RING")
    budget[0]+=len(value)
    if budget[0]>MAX_VERTICES:
        raise ValueError("VERTEX_LIMIT")
    result=[]
    for xy in value:
        if (not isinstance(xy,list) or len(xy)!=2 or any(type(v) not in (int,float)
                or not math.isfinite(v) or abs(v)>1e9 for v in xy)):
            raise ValueError("INVALID_OR_NON_2D_COORDINATE")
        result.append(tuple(xy))
    if result[0]!=result[-1]:
        raise ValueError("UNCLOSED_RING")
    if any(a==b for a,b in zip(result,result[1:])):
        raise ValueError("ZERO_LENGTH_EDGE")
    p=Polygon(result)
    if not p.is_valid or p.is_empty or p.area<=0:
        raise ValueError("INVALID_RING_TOPOLOGY")
    return result,p

def arcgis_polygon(rings,budget):
    if not isinstance(rings,list) or not 1<=len(rings)<=MAX_RINGS:
        raise ValueError("ARC_GIS_RING_LIMIT")
    parsed=[ring(r,budget) for r in rings]
    polygons=[p for _,p in parsed]
    parents=[None]*len(polygons)
    for i,a in enumerate(polygons):
        containing=[]
        for j,b in enumerate(polygons):
            if i==j:continue
            # Crossing, coincident, or touching rings are not silently repaired.
            if a.boundary.intersects(b.boundary):
                raise ValueError("AMBIGUOUS_RING_CONTACT")
            if b.contains(a):
                containing.append(j)
            elif a.intersects(b) and not a.contains(b):
                raise ValueError("AMBIGUOUS_RING_OVERLAP")
        if containing:parents[i]=min(containing,key=lambda j:polygons[j].area)
    depths=[]
    for i in range(len(polygons)):
        depth=0
        j=parents[i]
        while j is not None:
            depth+=1
            if depth>len(polygons):raise ValueError("RING_CONTAINMENT_CYCLE")
            j=parents[j]
        depths.append(depth)
    pieces=[]
    for i,(coords,_) in enumerate(parsed):
        if depths[i]%2==0:
            holes=[parsed[j][0] for j,p in enumerate(parents) if p==i]
            pieces.append(Polygon(coords,holes))
    return pieces[0] if len(pieces)==1 else MultiPolygon(pieces)

def geojson_polygon(value,budget):
    kind=value.get("type")
    coords=value.get("coordinates")
    if kind=="Polygon":coords=[coords]
    elif kind!="MultiPolygon":raise ValueError("POLYGON_REQUIRED")
    if not isinstance(coords,list) or not coords:raise ValueError("POLYGON_REQUIRED")
    pieces=[]
    nr=0
    for poly in coords:
        if not isinstance(poly,list) or not poly:raise ValueError("POLYGON_RINGS_REQUIRED")
        nr+=len(poly)
        if nr>MAX_RINGS:raise ValueError("RING_LIMIT")
        parsed=[ring(r,budget)[0] for r in poly]
        pieces.append(Polygon(parsed[0],parsed[1:]))
    return pieces[0] if kind=="Polygon" else MultiPolygon(pieces)

def normalize_geometry(value,fmt,source_crs):
    if not isinstance(value,dict):
        raise ValueError("MISSING_OR_UNSUPPORTED_GEOMETRY")
    code=crs_code(source_crs)
    if fmt not in ("GeoJSON","ProjectedGeoJSON","ArcGIS"):
        raise ValueError("UNSUPPORTED_GEOMETRY_FORMAT")
    if fmt=="GeoJSON" and code!="EPSG:4326":
        raise ValueError("EXPLICIT_WGS84_GEOJSON_MAPPING_REQUIRED")
    if value.get("hasZ") or value.get("hasM") or "curveRings" in value:
        raise ValueError("CURVES_OR_EXTRA_DIMENSIONS_UNSUPPORTED")
    if "crs" in value:
        raise ValueError("EMBEDDED_GEOJSON_CRS_REQUIRES_REVIEW")
    embedded=embedded_crs(value)
    if embedded and embedded!=code:
        raise ValueError("SOURCE_CRS_CONFLICT")
    budget=[0]
    try:
        g=arcgis_polygon(value.get("rings"),budget) if fmt=="ArcGIS" else geojson_polygon(value,budget)
        if not g.is_valid or g.is_empty or not math.isfinite(g.area) or g.area<=0:
            raise ValueError("INVALID_SOURCE_TOPOLOGY")
        if code!="EPSG:4326":
            tr=transformer_for(code)
            g=transform(lambda x,y:tr.transform(x,y,errcheck=True),g)
        if not g.is_valid or g.is_empty or not math.isfinite(g.area) or g.area<=0:
            raise ValueError("INVALID_TRANSFORMED_TOPOLOGY")
        x0,y0,x1,y1=g.bounds
        if not (7<=x0<=x1<=12.5 and 30<=y0<=y1<=38):
            raise ValueError("OUTSIDE_TUNISIA_OR_WRONG_CRS")
        g=orient(g,sign=1.0) if isinstance(g,Polygon) else MultiPolygon([orient(p,sign=1.0) for p in g.geoms])
        # JSON conversion removes any non-geometry attributes from raw records.
        return mapping(g),{"source_crs":code,"embedded_crs":embedded,"target_crs":"EPSG:4326",
                            "vertex_count":budget[0],"transform":"IDENTITY" if code=="EPSG:4326" else "PROJ_NO_BALLPARK",
                            "ground_verified":False}
    except (GEOSException,ProjError,TypeError,OverflowError) as exc:
        raise ValueError("GEOMETRY_CONVERSION_FAILED") from exc
