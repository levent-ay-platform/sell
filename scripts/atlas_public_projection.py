#!/usr/bin/env python3
"""Deny-by-default Atlas -> Amlak public boundary projection.

Reads privately reviewed plans, independently reviewed geometry and a complete
current revocation snapshot. Emits only minimal safe GeoJSON. No source URLs,
land title/parcel identifiers, CIN, owner name, OCR, or provenance are emitted.
This is a build/export gate, not an online publication endpoint.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import math
import os
from pathlib import Path

from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_geometry_evidence import _area_m2, _ring


def _is_true(v):
    return v is True


def _positive_number(v, low, high):
    return type(v) in (int, float) and math.isfinite(v) and low <= v <= high


def _safe_file(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 16_000_000:
        raise ValueError("INVALID_PRIVATE_INPUT_FILE")
    if path.stat().st_mode & 0o077:
        raise ValueError("PRIVATE_INPUT_PERMISSIONS_REQUIRED")
    return json.loads(path.read_text(encoding="utf-8"))


def _accepted_plan(row):
    if not isinstance(row, dict):
        return False
    if (row.get("classification") != "OFFICIAL_CADASTRAL" or
            not _is_true(row.get("authority_verified")) or
            row.get("title_parcel_link") != "VERIFIED" or
            row.get("geometry_status") != "GROUND_VERIFIED"):
        return False
    publication = row.get("publication")
    if not isinstance(publication, dict) or any(
        not _is_true(publication.get(k))
        for k in ("manager_approved","geometry_approved","rights_cleared",
                  "owner_consented","listing_active")
    ):
        return False
    qa = row.get("qa")
    if (not isinstance(qa, dict) or qa.get("target_crs") != "EPSG:4326" or
            not isinstance(qa.get("fit_points"),int) or
            not isinstance(qa.get("independent_holdout"),int) or
            qa["fit_points"] < 3 or qa["independent_holdout"] < 1 or
            not _positive_number(qa.get("holdout_max_error_m"), 0, 3)):
        return False
    return True


def _validated_geometry(value):
    if (not isinstance(value, dict) or value.get("type") != "Polygon" or
            not isinstance(value.get("coordinates"), list) or
            len(value["coordinates"]) != 1):
        raise ValueError("ONLY_SINGLE_RING_POLYGON_ALLOWED")
    ring = _ring(value["coordinates"][0], geographic=True)
    area = _area_m2(ring)
    if not _positive_number(area, 0.5, 1_000_000_000):
        raise ValueError("INVALID_GROUND_AREA")
    return {"type":"Polygon","coordinates":[[list(p) for p in ring]]}


def _manifest(manifest):
    if not isinstance(manifest, dict) or manifest.get("complete") is not True:
        raise ValueError("COMPLETE_REVOCATION_MANIFEST_REQUIRED")
    if (type(manifest.get("revision")) is not int or manifest["revision"] < 1 or
            not isinstance(manifest.get("revoked_record_ids"),list) or
            len(manifest["revoked_record_ids"]) > 100_000 or
            any(not isinstance(x,str) or not x or len(x)>256
                for x in manifest["revoked_record_ids"])):
        raise ValueError("INVALID_REVOCATION_MANIFEST")
    return set(manifest["revoked_record_ids"])


def make_public_projection(records, revocation_manifest, *, hmac_key):
    if not isinstance(records,list) or len(records) > 50_000:
        raise ValueError("INVALID_REVIEW_SET")
    if not isinstance(hmac_key,bytes) or len(hmac_key) < 32:
        raise ValueError("STRONG_SECRET_REQUIRED")
    revoked=_manifest(revocation_manifest)
    seen=set()
    features=[]
    for row in records:
        if not isinstance(row,dict):
            raise ValueError("INVALID_REVIEW_ROW")
        internal=row.get("record_id")
        digest=row.get("document_sha256")
        if (not isinstance(internal,str) or not 1<=len(internal)<=256 or
                internal in seen):
            raise ValueError("DUPLICATE_OR_INVALID_PRIVATE_RECORD")
        seen.add(internal)
        if internal in revoked or not _accepted_plan(row):
            continue
        if (not isinstance(digest,str) or len(digest)!=64 or
                any(c not in "0123456789abcdef" for c in digest)):
            raise ValueError("VERIFIED_DOCUMENT_SHA256_REQUIRED")
        geometry=_validated_geometry(row.get("geometry4326"))
        public_id="ap_"+hmac.new(
            hmac_key, (internal+"\x00"+digest).encode("utf-8"),
            hashlib.sha256).hexdigest()[:32]
        features.append({
            "type":"Feature",
            "id":public_id,
            "geometry":geometry,
            "properties":{
                "publicId":public_id,
                "atlasLayer":"ATLAS_OFFICIAL_REVIEWED",
                "documentStatus":"OFFICIAL_VERIFIED",
                "geometryStatus":"GROUND_VERIFIED",
            },
        })
    # Keep output order deterministic. HMAC public IDs cannot reveal title numbers.
    features.sort(key=lambda row:row["id"])
    return {
        "type":"FeatureCollection",
        "layerId":"ATLAS_OFFICIAL_REVIEWED",
        "features":features,
    }


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reviews",type=Path,required=True)
    parser.add_argument("--revocations",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    secret=os.environ.get("ATLAS_PUBLIC_HMAC_KEY","").encode("utf-8")
    reviews=_safe_file(args.reviews)
    revoked=_safe_file(args.revocations)
    projection=make_public_projection(reviews,revoked,hmac_key=secret)
    write_private_json(args.output,projection)
    print(json.dumps({"projection_ready_for_rights_review":True,
                      "approved_feature_count":len(projection["features"]),
                      "deployed":False}))


if __name__=="__main__":
    main()
