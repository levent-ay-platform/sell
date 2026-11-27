#!/usr/bin/env python3
"""Private georeferencing QA. Never promotes a plan to legal cadastral geometry.

Input is owner/operator-supplied PAGE_POINT/PIXEL coordinates and independent
EPSG:4326 control points. No title number is interpreted as a location.
Source of truth: levent-ay-platform/sell Issue #20.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from scripts.atlas_cadastre_evidence_audit import write_private_json

TUNISIA = (7.0, 30.0, 12.5, 38.0)


def _point(p, *, geographic=False):
    if (not isinstance(p, (list, tuple)) or len(p) != 2 or
            any(type(v) not in (int, float) or not math.isfinite(v) for v in p)):
        raise ValueError("INVALID_POINT")
    x, y = (float(p[0]), float(p[1]))
    if geographic:
        if not (TUNISIA[0] <= x <= TUNISIA[2] and TUNISIA[1] <= y <= TUNISIA[3]):
            raise ValueError("OUTSIDE_TUNISIA")
    elif abs(x) > 1e7 or abs(y) > 1e7:
        raise ValueError("UNBOUNDED_PAGE_COORDINATE")
    return (x, y)


def _cross(a, b, c):
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def _segments_cross(a, b, c, d):
    def on(x, y, z):
        return (abs(_cross(x, y, z)) < 1e-9 and
                min(x[0], y[0]) - 1e-9 <= z[0] <= max(x[0], y[0]) + 1e-9 and
                min(x[1], y[1]) - 1e-9 <= z[1] <= max(x[1], y[1]) + 1e-9)
    return (on(a, b, c) or on(a, b, d) or on(c, d, a) or on(c, d, b) or
            (_cross(a, b, c) * _cross(a, b, d) < 0 and
             _cross(c, d, a) * _cross(c, d, b) < 0))


def _ring(points, *, geographic=False):
    if not isinstance(points, list) or not 4 <= len(points) <= 65:
        raise ValueError("INVALID_RING_LENGTH")
    ring = [_point(p, geographic=geographic) for p in points]
    if ring[0] != ring[-1]:
        raise ValueError("RING_NOT_CLOSED")
    n = len(ring) - 1
    area = sum(ring[i][0] * ring[i + 1][1] -
               ring[i + 1][0] * ring[i][1] for i in range(n)) / 2
    if abs(area) < 1e-12:
        raise ValueError("DEGENERATE_RING")
    for i in range(n):
        if ring[i] == ring[i + 1]:
            raise ValueError("ZERO_EDGE")
        for j in range(i + 2, n):
            if i == 0 and j == n - 1:
                continue
            if _segments_cross(ring[i], ring[i + 1], ring[j], ring[j + 1]):
                raise ValueError("SELF_INTERSECTION")
    return ring


def _solve(matrix, values):
    augmented = [list(matrix[i]) + [values[i]] for i in range(3)]
    for i in range(3):
        pivot = max(range(i, 3), key=lambda j: abs(augmented[j][i]))
        if abs(augmented[pivot][i]) < 1e-10:
            raise ValueError("COLLINEAR_CONTROL_POINTS")
        augmented[i], augmented[pivot] = augmented[pivot], augmented[i]
        divider = augmented[i][i]
        for k in range(i, 4):
            augmented[i][k] /= divider
        for j in range(3):
            if j != i:
                scale = augmented[j][i]
                for k in range(i, 4):
                    augmented[j][k] -= scale * augmented[i][k]
    return [row[3] for row in augmented]


def _affine(points):
    # Center/scale page coordinates for better conditioning on scanned PDFs.
    mean_x = sum(p[0][0] for p in points) / len(points)
    mean_y = sum(p[0][1] for p in points) / len(points)
    scale = max(max(abs(p[0][0]-mean_x), abs(p[0][1]-mean_y)) for p in points)
    if scale < 1e-8:
        raise ValueError("COLLINEAR_CONTROL_POINTS")
    basis = [[(p[0][0]-mean_x)/scale, (p[0][1]-mean_y)/scale, 1.0] for p in points]
    normal = [[sum(row[i]*row[j] for row in basis) for j in range(3)]
              for i in range(3)]
    lon = _solve(normal, [sum(row[i]*p[1][0] for row,p in zip(basis,points))
                          for i in range(3)])
    lat = _solve(normal, [sum(row[i]*p[1][1] for row,p in zip(basis,points))
                          for i in range(3)])

    def transform(p):
        x,y = (p[0]-mean_x)/scale, (p[1]-mean_y)/scale
        return (lon[0]*x+lon[1]*y+lon[2], lat[0]*x+lat[1]*y+lat[2])
    return transform


def _distance_m(a,b):
    lat = math.radians((a[1]+b[1])/2)
    dx = (a[0]-b[0])*111_320*math.cos(lat)
    dy = (a[1]-b[1])*111_132
    return math.hypot(dx,dy)


def _area_m2(ring):
    middle_lat = math.radians(sum(p[1] for p in ring[:-1])/(len(ring)-1))
    x_scale = 111_320*math.cos(middle_lat)
    y_scale = 111_132
    return abs(sum((ring[i][0]*ring[i+1][1] - ring[i+1][0]*ring[i][1])
                   for i in range(len(ring)-1)))*x_scale*y_scale/2


def assess_georeferencing(spec):
    """Return private QA evidence only; no 'verified' geometry is emitted."""
    if not isinstance(spec, dict) or spec.get("page_unit") not in ("PAGE_POINT","PIXEL"):
        raise ValueError("PAGE_UNIT_REQUIRED")
    if spec.get("target_crs") != "EPSG:4326":
        raise ValueError("EXPLICIT_WGS84_REQUIRED")
    ring = _ring(spec.get("page_ring"))
    controls = spec.get("control_points")
    if not isinstance(controls,list) or not 4 <= len(controls) <= 32:
        raise ValueError("FOUR_OR_MORE_GCPS_REQUIRED")
    fit, check = [], []
    for row in controls:
        if not isinstance(row,dict) or row.get("role") not in ("FIT","CHECK"):
            raise ValueError("INDEPENDENT_GCP_ROLES_REQUIRED")
        pair = (_point(row.get("page")), _point(row.get("wgs84"), geographic=True))
        (fit if row["role"]=="FIT" else check).append(pair)
    if len(fit) < 3 or not check:
        raise ValueError("THREE_FIT_ONE_INDEPENDENT_CHECK_REQUIRED")
    if len({p[0] for p in fit+check})!=len(fit)+len(check) or len({p[1] for p in fit+check})!=len(fit)+len(check):
        raise ValueError("DUPLICATE_OR_REUSED_CONTROL_POINT")
    tolerance = spec.get("maximum_holdout_error_m")
    if (type(tolerance) not in (int,float) or not math.isfinite(tolerance)
            or not 0.1 <= tolerance <= 20):
        raise ValueError("BOUNDED_TOLERANCE_REQUIRED")
    transformer = _affine(fit)
    mapped = [transformer(p) for p in ring]
    for point in mapped:
        _point(point, geographic=True)
    mapped = _ring([list(p) for p in mapped], geographic=True)
    fit_errors = [_distance_m(transformer(x),y) for x,y in fit]
    holdout = [_distance_m(transformer(x),y) for x,y in check]
    rms = math.sqrt(sum(e*e for e in fit_errors)/len(fit_errors))
    worst = max(holdout)
    area = _area_m2(mapped)
    printed = spec.get("printed_area_m2")
    if printed is not None and (type(printed) not in (int,float) or
                               not math.isfinite(printed) or printed <= 0):
        raise ValueError("INVALID_PRINTED_AREA")
    area_difference = abs(area-printed)/printed if printed is not None else None
    # Fitting well is not enough: independent holdouts and area may still disagree.
    passed = rms <= tolerance and worst <= tolerance and (area_difference is None or area_difference <= 0.15)
    return {
        "schema_version":1,
        "geometry_status":"GEOREFERENCED_CANDIDATE" if passed else "GEOREFERENCE_REVIEW_REQUIRED",
        "ground_verified":False,
        "source_page_unit":spec["page_unit"],
        "target_crs":"EPSG:4326",
        "control_fit_count":len(fit),
        "control_holdout_count":len(check),
        "fit_rms_m":round(rms,4),
        "holdout_max_error_m":round(worst,4),
        "holdout_tolerance_m":tolerance,
        "mapped_area_m2":round(area,2),
        "printed_area_difference_ratio":round(area_difference,4) if area_difference is not None else None,
        "geometry4326":{"type":"Polygon","coordinates":[[list(x) for x in mapped]]},
        "limitations":[
            "Page coordinates are not parcel geolocation by themselves.",
            "Control points must be independently documented and reviewed.",
            "Georeferenced candidate is not ground-verified/legal boundary.",
        ],
    }


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    if args.input.is_symlink() or not args.input.is_file():
        raise ValueError("PRIVATE_INPUT_REQUIRED")
    spec=json.loads(args.input.read_text(encoding="utf-8"))
    result=assess_georeferencing(spec)
    write_private_json(args.output,result)
    print(json.dumps({"private_qa_written":True,"geometry_status":result["geometry_status"],
                      "ground_verified":False}))


if __name__=="__main__":
    main()
