#!/usr/bin/env python3
"""Private, candidate-only closed straight-line outlines from a verified PDF.

Extracts direct page-content PDF path operators, not text or nested XObjects.
PDF coordinates are transformed to top-left display PAGE_POINT coordinates.
No inferred CRS, parcel identity, official status or publication authority.
Run on a trusted, SHA-matched local copy in an isolated operator process.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import io
import json
import math
from pathlib import Path
import re

from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_geometry_evidence import _ring

MAX_PDF_BYTES = 64 * 1024 * 1024
MAX_SELECTED_PAGES = 32
MAX_OPERATORS = 100_000
MAX_CANDIDATES = 1_000
IDENTITY = (1., 0., 0., 1., 0., 0.)
PAINT = {b"S", b"s", b"f", b"F", b"f*", b"B", b"B*", b"b", b"b*"}
CLOSE_PAINT = {b"s", b"b", b"b*"}


def _numbers(values, size):
    if len(values) != size:
        raise ValueError("INVALID_PDF_OPERATOR_OPERANDS")
    result = tuple(float(value) for value in values)
    if any(not math.isfinite(value) or abs(value) > 1e7 for value in result):
        raise ValueError("UNBOUNDED_PDF_OPERAND")
    return result


def _apply(matrix, point):
    a, b, c, d, e, f = matrix
    x, y = point
    return (a*x + c*y + e, b*x + d*y + f)


def _compose(current, next_):
    a, b, c, d, e, f = current
    g, h, i, j, k, l = next_
    return (a*g + c*h, b*g + d*h, a*i + c*j, b*i + d*j,
            a*k + c*l + e, b*k + d*l + f)


def _display_frame(page):
    left, bottom, right, top = _numbers(list(page.cropbox), 4)
    width, height = right-left, top-bottom
    unit = float(page.user_unit)
    rotation = int(page.rotation)
    if (not math.isfinite(unit) or not 0 < unit <= 100 or
            min(width, height) <= 0 or max(width, height)*unit > 100_000 or
            rotation % 90):
        raise ValueError("UNSUPPORTED_PDF_DISPLAY_FRAME")
    rotation %= 360

    def display(p):
        x, y = p[0]-left, p[1]-bottom
        if rotation == 0:
            a, b = x, height-y
        elif rotation == 90:
            a, b = y, x
        elif rotation == 180:
            a, b = width-x, y
        else:
            a, b = height-y, width-x
        return (round(a*unit, 6), round(b*unit, 6))

    return display, (height*unit, width*unit) if rotation in (90, 270) else (
        width*unit, height*unit), rotation


def _extract_page(page, number):
    transform, (width, height), rotation = _display_frame(page)
    contents = page.get_contents()
    operations = contents.operations if contents is not None else []
    if len(operations) > MAX_OPERATORS:
        raise ValueError("PDF_OPERATOR_LIMIT")
    matrix, stack = IDENTITY, []
    paths = []
    results, rejected, flags = [], Counter(), Counter()
    active = None

    def start(point):
        nonlocal active
        active = {"points": [point], "curved": False}
        paths.append(active)

    def add(point):
        if active is None:
            raise ValueError("PATH_WITHOUT_MOVETO")
        if len(active["points"]) <= 65:
            active["points"].append(point)
        else:
            active["overflow"] = True

    def close():
        if active is not None and active["points"][-1] != active["points"][0]:
            add(active["points"][0])

    def collect():
        for path in paths:
            points = path["points"]
            if path["curved"]:
                rejected["CURVED_PATH_REVIEW"] += 1
                continue
            if path.get("overflow"):
                rejected["TOO_MANY_VERTICES"] += 1
                continue
            if len(points) < 2 or points[0] != points[-1]:
                rejected["OPEN_PATH_REVIEW"] += 1
                continue
            display_points = [transform(point) for point in points]
            try:
                ring = _ring([list(point) for point in display_points])
            except ValueError as exc:
                rejected[str(exc)] += 1
                continue
            if any(not (0 <= x <= width and 0 <= y <= height) for x, y in ring):
                rejected["OUTSIDE_DISPLAY_FRAME"] += 1
                continue
            xs, ys = zip(*ring)
            bbox = [min(xs), min(ys), max(xs), max(ys)]
            area = abs(sum(a[0]*b[1]-b[0]*a[1] for a, b in zip(ring, ring[1:])))/2
            bbox_fraction = (bbox[2]-bbox[0])*(bbox[3]-bbox[1])/(width*height)
            results.append({
                "page_ring": [list(point) for point in ring],
                "page_unit": "PAGE_POINT",
                "page_area_points_squared": round(area, 6),
                "bbox_points": bbox,
                "possible_frame_or_border": bbox_fraction >= .85,
                "geometry_status": "PAGE_OUTLINE_CANDIDATE",
                "ground_verified": False,
                "requires_visual_review": True,
            })
            if len(results) > MAX_CANDIDATES:
                raise ValueError("PDF_CANDIDATE_LIMIT")

    for operands, op in operations:
        if op == b"q":
            stack.append(matrix)
            if len(stack) > 128:
                raise ValueError("PDF_GRAPHICS_STACK_LIMIT")
        elif op == b"Q":
            if not stack:
                raise ValueError("UNBALANCED_PDF_GRAPHICS_STATE")
            matrix = stack.pop()
        elif op == b"cm":
            matrix = _compose(matrix, _numbers(operands, 6))
        elif op == b"m":
            start(_apply(matrix, _numbers(operands, 2)))
        elif op == b"l":
            add(_apply(matrix, _numbers(operands, 2)))
        elif op == b"h":
            close()
        elif op == b"re":
            x, y, w, h = _numbers(operands, 4)
            corners = [(x,y), (x+w,y), (x+w,y+h), (x,y+h), (x,y)]
            start(_apply(matrix, corners[0]))
            for point in corners[1:]:
                add(_apply(matrix, point))
        elif op in (b"c", b"v", b"y"):
            if active is None:
                raise ValueError("CURVE_WITHOUT_PATH")
            active["curved"] = True
            # The current subpath stays unsupported, even if later closed.
        elif op in PAINT:
            if op in CLOSE_PAINT:
                close()
            collect()
            paths, active = [], None
        elif op == b"n":
            rejected["UNPAINTED_PATHS"] += len(paths)
            paths, active = [], None
        elif op in (b"W", b"W*"):
            flags["CLIPPING_PRESENT"] += 1
        elif op in (b"Do", b"INLINE IMAGE", b"sh"):
            flags["NESTED_OR_RASTER_CONTENT_NOT_INSPECTED"] += 1
    if stack:
        raise ValueError("UNBALANCED_PDF_GRAPHICS_STATE")
    if paths:
        rejected["UNPAINTED_PATHS"] += len(paths)

    return {
        "page": number,
        "page_unit": "PAGE_POINT",
        "display_width_points": width,
        "display_height_points": height,
        "rotation_degrees": rotation,
        "outlines_private": results,
        "rejected_path_counts": dict(sorted(rejected.items())),
        "limitations_detected": dict(sorted(flags.items())),
        "page_status": "DIRECT_VECTOR_CANDIDATES_REVIEW" if results else "RASTER_OR_MANUAL_REVIEW_REQUIRED",
        "official_plan_verified": None,
    }


def extract_pdf(pdf: Path, expected_sha256: str, *, pages=None):
    from pypdf import PdfReader

    if not isinstance(expected_sha256, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha256):
        raise ValueError("EXPECTED_PDF_SHA256_REQUIRED")
    if pdf.is_symlink() or not pdf.is_file():
        raise ValueError("REGULAR_PRIVATE_PDF_REQUIRED")
    with pdf.open("rb") as handle:
        data = handle.read(MAX_PDF_BYTES+1)
    if not data or len(data) > MAX_PDF_BYTES:
        raise ValueError("PDF_BYTE_LIMIT")
    digest = hashlib.sha256(data).hexdigest()
    if digest != expected_sha256.lower():
        raise ValueError("PDF_SHA256_MISMATCH")
    reader = PdfReader(io.BytesIO(data), strict=True)
    if reader.is_encrypted:
        raise ValueError("ENCRYPTED_PDF_NOT_SUPPORTED")
    total = len(reader.pages)
    if pages is None:
        if total > MAX_SELECTED_PAGES:
            raise ValueError("SELECT_PAGES_EXPLICITLY")
        pages = list(range(1, total+1))
    if (not 1 <= len(pages) <= MAX_SELECTED_PAGES or len(set(pages)) != len(pages)
            or any(type(n) is not int or not 1 <= n <= total for n in pages)):
        raise ValueError("INVALID_SELECTED_PAGES")
    inspected = [_extract_page(reader.pages[n-1], n) for n in sorted(pages)]
    return {
        "schema_version": 1,
        "kind": "ATLAS_PRIVATE_PDF_PAGE_OUTLINE_CANDIDATES",
        "document_sha256_private": digest,
        "document_page_count": total,
        "inspected_pages": sorted(pages),
        "uninspected_page_count": total-len(pages),
        "pages_private": inspected,
        "candidate_outline_count": sum(len(page["outlines_private"]) for page in inspected),
        "verified_official_cadastral_plan": None,
        "ground_verified": False,
        "limitations": [
            "Direct painted straight-line subpaths only; nested XObjects, curves, clipping and filled holes require visual review.",
            "A closed outline can be a frame, symbol, building or other non-parcel drawing.",
            "PAGE_POINT is top-left display page space, not geographic coordinates.",
            "Use the existing raster/crop extractor for scans; no auto-selection or publication.",
            "Byte/operator/page limits are not a sandbox: process only approved copies under OS memory/time isolation.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--page", type=int, action="append")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = extract_pdf(args.pdf, args.expected_sha256, pages=args.page)
    write_private_json(args.output, result)
    print(json.dumps({"report_written": True,
                      "inspected_pages": len(result["inspected_pages"]),
                      "candidate_outlines": result["candidate_outline_count"],
                      "official_plan_verified": "UNKNOWN", "ground_verified": False}))


if __name__ == "__main__":
    main()
