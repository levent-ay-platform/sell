#!/usr/bin/env python3
"""Project the pinned Atlas AFI snapshot into public Android map assets."""

import argparse
import hashlib
import json
import math
from pathlib import Path
from urllib.parse import urlsplit


def agency_url(value, *, pdf=False):
    if not isinstance(value, str):
        return None
    url = urlsplit(value)
    if url.scheme != "https" or url.netloc not in {"afi.nat.tn", "www.afi.nat.tn"}:
        return None
    if pdf and (not url.path.startswith("/wp-content/uploads/") or not url.path.lower().endswith(".pdf")):
        return None
    return value


def point(value):
    return value if (
        isinstance(value, list) and len(value) == 2
        and all(isinstance(n, (int, float)) and math.isfinite(n) for n in value)
        and 7 <= value[0] <= 13 and 29 <= value[1] <= 39
    ) else None


def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")


def export(snapshot, output):
    pin = json.loads((snapshot / "manifest.json").read_text())
    filename = pin["file"]
    if Path(filename).name != filename:
        raise ValueError("Invalid Atlas snapshot filename")
    raw = (snapshot / filename).read_bytes()
    if hashlib.sha256(raw).hexdigest() != pin["sha256"]:
        raise ValueError("Atlas AFI snapshot hash mismatch")
    source = json.loads(raw)
    output.mkdir(parents=True, exist_ok=True)
    overview = []
    for summary in source["overview"]["zones"]:
        zone_id = summary["id"]
        if not isinstance(zone_id, str) or not zone_id.isascii() or not zone_id.isdecimal():
            raise ValueError("Invalid agency zone identifier")
        detail = source["details"][zone_id]
        lots = []
        for lot in detail["lots"]:
            geometry = lot.get("geometry")
            anchor = point(lot.get("anchor"))
            if geometry and (not anchor or geometry.get("type") not in {"Polygon", "MultiPolygon"}):
                raise ValueError("Invalid geometry in checked Atlas projection")
            if not geometry and not lot["listed"]:
                continue
            if not lot["id"].startswith(zone_id + ":"):
                raise ValueError("Invalid lot scope")
            lots.append({
                "id": lot["id"], "number": lot["number"], "geometry": geometry, "anchor": anchor,
                "area_m2": lot["area_m2"], "estimated_area_m2": lot["computed_area_m2"],
                "price_dtn_m2": lot["price_dtn_m2"], "price_basis": lot["price_basis"],
                "listed": lot["listed"], "offer_status": lot["offer_status"],
            })
        documents = [
            {"title": row["title"], "page": page, "pdf": agency_url(row["pdf_url"], pdf=True)}
            for row in detail["documents"] if (page := agency_url(row["page_url"]))
        ]
        shapes = sum(lot["geometry"] is not None for lot in lots)
        if shapes != summary["layout_count"]:
            raise ValueError(f"Shape count changed for zone {zone_id}")
        overview.append({"id": zone_id, "name_ar": summary["name_ar"], "name_fr": summary["name_fr"],
                         "governorate": summary["governorate_name"], "center": point(summary["center"]),
                         "shapes": shapes})
        dump(output / f"{zone_id}.json", {
            "layout_date": (detail["layout_captured_at"] or "")[:10],
            "offer_date": (detail["availability_captured_at"] or "")[:10],
            "documents": documents, "lots": lots,
        })
    dump(output / "overview.json", {
        "captured_at": source["overview"]["captured_at"][:10], "atlas_sha256": pin["sha256"], "zones": overview,
    })
    print(f"{len(overview)} zones, {sum(zone['shapes'] for zone in overview)} traced lots")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    export(args.snapshot, args.output)
