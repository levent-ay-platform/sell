#!/usr/bin/env python3
"""Prepare the pinned public ODbL sector asset; never fetch private Atlas data.
No network call at app runtime. Fail closed on digest/schema mismatch."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "apps/android/app/src/main/assets/atlas-admin"
REV = "8ac5c1d85ba0661f3352c153fe8542c0aaafe2ef"
BLOB = "3c867f1708cb3c1f752ee32c6288dfe13b591fb5"
SIZE = 2845889
URL = f"https://raw.githubusercontent.com/Alaa-Taieb/map-tunisia/{REV}/dist/geojson/tunisia-sectors-medium.geojson"

def validate(raw):
    if len(raw) != SIZE or hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() != BLOB:
        raise ValueError("sector asset does not match pinned Git blob")
    features = json.loads(raw)["features"]
    delegates = {f["properties"]["id"]: f["properties"]["governorate"]
                 for f in json.loads((ASSETS / "delegations.geojson").read_bytes())["features"]}
    if len(features) not in (2082, 2083):
        raise ValueError("unexpected reference count")
    ids = set()
    for f in features:
        p = f["properties"]
        ident = p["id"]
        if ident in ids or not ident.startswith("sector-") or p["delegation"] not in delegates:
            raise ValueError("duplicate sector or invalid parent")
        if p.get("governorate") != delegates[p["delegation"]]:
            raise ValueError("inconsistent governorate")
        if not p.get("name_ar") or not p.get("name_fr"):
            raise ValueError("missing bilingual names")
        geom = f["geometry"]
        if geom["type"] not in ("Polygon", "MultiPolygon") or not geom["coordinates"]:
            raise ValueError("missing polygon")
        ids.add(ident)
    return len(features)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, help="Use already downloaded public GeoJSON")
    args = parser.parse_args()
    target = ASSETS / "sectors.geojson"
    if args.source:
        raw = args.source.read_bytes()
    elif target.exists():
        raw = target.read_bytes()
    else:
        with urllib.request.urlopen(URL, timeout=30) as stream:
            raw = stream.read(SIZE + 1)
    count = validate(raw)
    tmp = target.with_suffix(".tmp")
    tmp.write_bytes(raw)
    tmp.replace(target)
    print(json.dumps({"reference_sectors": count, "ins_atlas_target": 2082,
                      "sha256": hashlib.sha256(raw).hexdigest(),
                      "source_git_blob": BLOB, "status": "PINNED_REFERENCE_ONLY"}))

if __name__ == "__main__":
    main()
