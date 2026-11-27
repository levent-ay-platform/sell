#!/usr/bin/env python3
"""PRIVATE Atlas -> Amlak importer staging for existing ReviewedAtlasParcel contract.

The output is confidential: title references, provenance and source hashes must
never be bundled in Android, public Atlas, or public web. This script does NOT
write to Amlak's database or make network requests. Database import is a
separate authenticated reviewer action against the existing V2 adapter.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_public_projection import (
    _accepted_plan, _manifest, _safe_file, _validated_geometry,
)

SHA256 = re.compile(r"^[0-9a-f]{64}$")
NAMESPACE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def _text(row, name, maximum=200, required=True):
    value=row.get(name)
    if value is None and not required:
        return None
    if (not isinstance(value,str) or not value.strip() or len(value)>maximum or
            any(ord(char)<32 or ord(char)==127 for char in value)):
        raise ValueError("INVALID_PRIVATE_IMPORT_FIELD_"+name.upper())
    return value


def build_private_import_batch(reviews, revocations):
    """Map approved source evidence to the existing TS ReviewedAtlasParcel DTO."""
    revoked=_manifest(revocations)
    if not isinstance(reviews,list) or len(reviews)>50_000:
        raise ValueError("INVALID_REVIEW_SET")
    seen_record=set()
    seen_source_version=set()
    result=[]
    for row in reviews:
        if not isinstance(row,dict):
            raise ValueError("INVALID_REVIEW_RECORD")
        private_id=_text(row,"record_id",256)
        if private_id in seen_record:
            raise ValueError("DUPLICATE_PRIVATE_RECORD")
        seen_record.add(private_id)
        if private_id in revoked or not _accepted_plan(row):
            continue
        digest=_text(row,"document_sha256",64)
        if SHA256.fullmatch(digest) is None:
            raise ValueError("INVALID_DOCUMENT_HASH")
        source=row.get("source")
        if not isinstance(source,dict):
            raise ValueError("PRIVATE_SOURCE_EVIDENCE_REQUIRED")
        namespace=_text(source,"namespace",64)
        if not NAMESPACE.fullmatch(namespace):
            raise ValueError("INVALID_SOURCE_NAMESPACE")
        source_id=_text(source,"source_id")
        layer_id=_text(source,"layer_id")
        external_id=_text(source,"external_id")
        version=_text(source,"version")
        scoped=(namespace,source_id,layer_id,external_id,version)
        if scoped in seen_source_version:
            raise ValueError("DUPLICATE_SOURCE_VERSION")
        seen_source_version.add(scoped)
        scope=_text(row,"registry_scope",100)
        title=_text(row,"title_reference",100)
        plot=_text(row,"plot_reference",100)
        locality=_text(row,"locality_key",100)
        geometry=_validated_geometry(row.get("geometry4326"))
        result.append({
            "namespace":namespace,
            "sourceId":source_id,
            "layerId":layer_id,
            "externalId":external_id,
            "version":version,
            "sha256":digest,
            "registryScope":scope,
            "titleReference":title,
            "plotReference":plot,
            "localityKey":locality,
            "classification":"OFFICIAL_CADASTRAL",
            "allowBoundaryRedistribution":True,
            "geometry4326":geometry,
        })
    return {
        "schemaVersion":1,
        "distribution":"PRIVATE_DERIVED_AUTHORIZED_IMPORT_ONLY",
        "apiContract":"ReviewedAtlasParcel",
        "records":result,
    }


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reviews",type=Path,required=True)
    parser.add_argument("--revocations",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    batch=build_private_import_batch(_safe_file(args.reviews),_safe_file(args.revocations))
    write_private_json(args.output,batch)
    print(json.dumps({"private_import_staged":True,
                      "reviewed_record_count":len(batch["records"]),
                      "database_updated":False}))


if __name__=="__main__":
    main()
