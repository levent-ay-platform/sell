#!/usr/bin/env python3
"""Private source-schema inventory and explicit, snapshot-bound linkage adapter.

No live database access, inferred registry scope, or publication. Only stable
operator-controlled SQLite copies. Original rows remain in the source snapshot;
derived row accounting retains even incomplete and conflicting identifiers.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from contextlib import contextmanager, closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3
from scripts.atlas_cadastre_evidence_audit import DIGITS, write_private_json
from scripts.atlas_public_projection import _safe_file

FIELDS = ("namespace", "service_id", "layer_id", "object_id", "version",
          "registry_scope", "title_reference", "parcel_reference")
KEY = FIELDS[:5]
MAX_ROWS = 250_000
MAX_JSON = 2_000_000


def digest(path):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def ident(name):
    if not isinstance(name, str) or not name or "\x00" in name:
        raise ValueError("INVALID_SQL_IDENTIFIER")
    return '"' + name.replace('"', '""') + '"'


@contextmanager
def snapshot(path, expected, kind):
    path = Path(path)
    if kind not in ("mainfile_diagnostic", "transactional_backup"):
        raise ValueError("EXPLICIT_SNAPSHOT_KIND_REQUIRED")
    if path.is_symlink() or not path.is_file() or not re.fullmatch("[0-9a-f]{64}", expected):
        raise ValueError("PRIVATE_SNAPSHOT_AND_SHA_REQUIRED")
    if any(Path(str(path)+suffix).exists() or Path(str(path)+suffix).is_symlink()
           for suffix in ("-wal", "-shm")):
        raise ValueError("STANDALONE_SNAPSHOT_REQUIRED")
    if digest(path) != expected:
        raise ValueError("SNAPSHOT_SHA_MISMATCH")
    with closing(sqlite3.connect(path.resolve().as_uri()+"?mode=ro&immutable=1", uri=True)) as db:
        db.execute("PRAGMA query_only=ON")
        if db.execute("PRAGMA quick_check(1)").fetchone()[0] != "ok":
            raise ValueError("SNAPSHOT_INTEGRITY_FAILED")
        yield db
    if digest(path) != expected:
        raise ValueError("SNAPSHOT_CHANGED")


def tables(db):
    return [r[0] for r in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]


def columns(db, table):
    return [{"name": r[1], "declared_type": r[2], "primary_key_order": r[5]}
            for r in db.execute("PRAGMA table_info("+ident(table)+")")]


def inspect_schema(path, expected, kind, sample_rows=30):
    if not 0 <= sample_rows <= 100:
        raise ValueError("BOUNDED_SCHEMA_SAMPLE_REQUIRED")
    result = []
    with snapshot(path, expected, kind) as db:
        for table in tables(db):
            cols = columns(db, table)
            # JSON key hints only; no sample values or SQL from the source.
            hints = defaultdict(set)
            if sample_rows and cols:
                sql = "SELECT * FROM "+ident(table)+" LIMIT ?"
                for row in db.execute(sql, (sample_rows,)):
                    for col, val in zip(cols, row):
                        if not isinstance(val,str) or len(val)>MAX_JSON or not val.lstrip().startswith("{"):
                            continue
                        try:
                            obj = json.loads(val)
                        except ValueError:
                            continue
                        if isinstance(obj,dict):
                            hints[col["name"]].update(k for k in obj if isinstance(k,str) and len(k)<=128)
                            for k,v in obj.items():
                                if isinstance(v,dict) and len(k)<=128:
                                    hints[col["name"]].update(k+"."+x for x in v if isinstance(x,str) and len(x)<=128)
            result.append({"table": table, "columns": cols,
                           "row_count": db.execute("SELECT COUNT(*) FROM "+ident(table)).fetchone()[0],
                           "sampled_json_key_hints_private": {k:sorted(v)[:500] for k,v in hints.items()}})
    return {"kind":"PRIVATE_ATLAS_SOURCE_SCHEMA", "schema_version":1,
            "snapshot_kind":kind,"snapshot_sha256_private":expected,
            "tables_private":result,"sample_limit_per_table":sample_rows,
            "public_export_permitted":False}


def read_field(row, rule):
    if rule is None:
        return None
    if not isinstance(rule,dict):
        raise ValueError("EXPLICIT_FIELD_RULE_REQUIRED")
    if set(rule)=={"literal"}:
        return rule["literal"]
    if set(rule) not in ({"column"},{"column","json_path"}):
        raise ValueError("INVALID_FIELD_RULE")
    value = row[rule["column"]]
    if "json_path" not in rule:
        return value
    path = rule["json_path"]
    if not isinstance(path,list) or not 1 <= len(path)<=6 or any(not isinstance(k,str) or not k for k in path):
        raise ValueError("INVALID_JSON_PATH")
    if not isinstance(value,str) or len(value)>MAX_JSON:
        return None
    try:
        value=json.loads(value)
    except ValueError:
        return None
    for key in path:
        if not isinstance(value,dict):
            return None
        value=value.get(key)
    return value


def scalar(value):
    if type(value) is int:
        return str(value)
    if isinstance(value,str) and 0<len(value)<=256 and not any(ord(c)<32 or ord(c)==127 for c in value):
        return value
    return None


def reference(value, min_digits):
    # Numeric SQLite values have already lost possible leading zeros. Preserve
    # their observed representation privately but do not promote to a link key.
    if not isinstance(value,str):
        return None
    value=value.strip().translate(DIGITS)
    return value if re.fullmatch(r"[0-9]{%d,12}" % min_digits, value) else None


def export_registry(path, config, *, include_geometries=False):
    if not isinstance(config,dict) or config.get("kind")!="PRIVATE_REVIEWED_SOURCE_MAPPING":
        raise ValueError("EXPLICIT_REVIEWED_MAPPING_REQUIRED")
    expected=config["snapshot_sha256_private"]
    kind=config["snapshot_kind"]
    mappings=config.get("mappings")
    if not isinstance(mappings,list) or not 1<=len(mappings)<=100:
        raise ValueError("BOUNDED_SOURCE_MAPPINGS_REQUIRED")
    records=[]
    geometries=[]
    seen_tables=set()
    with snapshot(path,expected,kind) as db:
        for mapping in mappings:
            table=mapping["table"]
            if table not in tables(db) or table in seen_tables:
                raise ValueError("UNKNOWN_OR_DUPLICATE_MAPPED_TABLE")
            seen_tables.add(table)
            rules=mapping["fields"]
            if not isinstance(rules,dict) or set(rules)-set(FIELDS):
                raise ValueError("UNKNOWN_MAPPING_FIELD")
            if any(rules.get(f) is None for f in KEY):
                raise ValueError("SOURCE_KEY_MAPPING_REQUIRED")
            scope_map=mapping.get("reviewed_registry_scope_values",{})
            if not isinstance(scope_map,dict) or any(
                    not isinstance(k,str) or not isinstance(v,str) or not v or len(v)>100
                    for k,v in scope_map.items()):
                raise ValueError("INVALID_REVIEWED_SCOPE_MAP")
            for f in ("title_reference","parcel_reference","object_id","registry_scope"):
                if isinstance(rules.get(f),dict) and "literal" in rules[f]:
                    raise ValueError("ROW_SPECIFIC_VALUE_CANNOT_BE_LITERAL")
            names=[c["name"] for c in columns(db,table)]
            geometry_mapping=mapping.get("geometry") if include_geometries else None
            geometry_rule=None
            if geometry_mapping is not None:
                if (not isinstance(geometry_mapping,dict) or geometry_mapping.get("crs")!="EPSG:4326"
                        or geometry_mapping.get("format")!="GeoJSON"
                        or geometry_mapping.get("kind") not in ("CADASTRE_SOURCE","PLANNING_SOURCE","OTHER_SOURCE")):
                    raise ValueError("EXPLICIT_WGS84_GEOJSON_MAPPING_REQUIRED")
                geometry_rule=geometry_mapping.get("rule")
                if not isinstance(geometry_rule,dict) or "column" not in geometry_rule or "literal" in geometry_rule:
                    raise ValueError("ROW_GEOMETRY_FIELD_REQUIRED")
            selected_rules=list(rules.values()) + ([geometry_rule] if geometry_rule is not None else [])
            for rule in selected_rules:
                if rule is not None and (not isinstance(rule,dict) or
                        ("column" in rule and rule["column"] not in names)):
                    raise ValueError("MAPPING_COLUMN_NOT_FOUND")
            count=db.execute("SELECT COUNT(*) FROM "+ident(table)).fetchone()[0]
            if len(records)+count>MAX_ROWS:
                raise ValueError("MAX_SOURCE_ROWS_EXCEEDED")
            selected=sorted({rule["column"] for rule in selected_rules
                             if isinstance(rule,dict) and "column" in rule})
            sql="SELECT "+",".join(map(ident,selected))+" FROM "+ident(table)
            actual=0
            for values in db.execute(sql):
                actual+=1
                row=dict(zip(selected,values))
                raw={f:read_field(row,rules.get(f)) for f in FIELDS}
                record={f:scalar(raw[f]) for f in KEY}
                record["registry_scope"]=scope_map.get(raw["registry_scope"]) if isinstance(raw["registry_scope"],str) else None
                record["title_reference"]=reference(raw["title_reference"],3)
                record["parcel_reference"]=reference(raw["parcel_reference"],1)
                reasons=[]
                if any(record[f] is None for f in KEY):
                    reasons.append("INCOMPLETE_SCOPED_IDENTITY")
                if record["registry_scope"] is None:
                    reasons.append("REGISTRY_SCOPE_UNREVIEWED")
                if record["title_reference"] is None or record["parcel_reference"] is None:
                    reasons.append("REFERENCE_MISSING_OR_UNSAFE_REPRESENTATION")
                if include_geometries:
                    value=read_field(row,geometry_rule)
                    if isinstance(value,str) and len(value)<=MAX_JSON:
                        try:
                            value=json.loads(value)
                        except ValueError:
                            value=None
                    geometry=None
                    state="GEOMETRY_NOT_MAPPED" if geometry_rule is None else "MISSING_OR_UNSUPPORTED_GEOMETRY"
                    if isinstance(value,dict) and value.get("type") in ("Polygon","MultiPolygon") and isinstance(value.get("coordinates"),list):
                        geometry={"type":value["type"],"coordinates":value["coordinates"]}
                        state="WGS84_POLYGON_DECLARED_NOT_VALIDATED"
                    geometries.append({"table_private":table,"scan_ordinal_private":actual,
                        "source_scoped_key":[record[f] for f in KEY],
                        "geometry_kind":geometry_mapping["kind"] if geometry_mapping else "UNKNOWN",
                        "geometry_input_state":state,"geometry":geometry})
                records.append({"table_private":table,"scan_ordinal_private":actual,
                                "observed_fields_private":{f:scalar(raw[f]) for f in FIELDS},
                                "source_record_private":record,"reasons":reasons})
            if actual!=count:
                raise ValueError("SOURCE_ROW_COUNT_MISMATCH")
    grouped=defaultdict(list)
    for entry in records:
        row=entry["source_record_private"]
        if all(row[f] is not None for f in KEY):
            grouped[tuple(row[f] for f in KEY)].append(entry)
    candidates=[]
    for entry in records:
        row=entry["source_record_private"]
        if all(row[f] is not None for f in KEY) and len(grouped[tuple(row[f] for f in KEY)])>1:
            entry["reasons"].append("DUPLICATE_SCOPED_IDENTITY")
        if not entry["reasons"]:
            candidates.append(row)
    report={"kind":"PRIVATE_ATLAS_SOURCE_REGISTRY","schema_version":1,
            "snapshot_kind":kind,"snapshot_sha256_private":expected,
            "counts":{"mapped_rows":len(records),"candidate_source_rows":len(candidates),
                      "unresolved_rows":len(records)-len(candidates),
                      "unresolved_reasons":dict(Counter(r for e in records for r in e["reasons"]))},
            "rows_private":records,"candidate_source_records_private":candidates,
            "official_plan_verified":None,"public_export_permitted":False}
    if include_geometries:
        if len(geometries)!=len(records):
            raise ValueError("GEOMETRY_ROW_ACCOUNTING_MISMATCH")
        report["geometry_projection_private"]={"kind":"PRIVATE_ATLAS_SOURCE_GEOMETRIES",
            "crs":"EPSG:4326","snapshot_sha256_private":expected,"public_export_permitted":False,
            "records_private":geometries}
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest="command",required=True)
    i=sub.add_parser("inspect")
    i.add_argument("--snapshot-sha256",required=True)
    i.add_argument("--snapshot-kind",required=True,choices=["mainfile_diagnostic","transactional_backup"])
    i.add_argument("--sample-rows",type=int,default=30)
    e=sub.add_parser("export")
    e.add_argument("--mapping",type=Path,required=True)
    e.add_argument("--candidate-sources-output",type=Path,required=True)
    e.add_argument("--geometries-output",type=Path)
    for cmd in (i,e):
        cmd.add_argument("--db",type=Path,required=True)
        cmd.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    if a.command=="inspect":
        report=inspect_schema(a.db,a.snapshot_sha256,a.snapshot_kind,a.sample_rows)
        write_private_json(a.output,report)
        counts={"tables":len(report["tables_private"])}
    else:
        outputs=[a.output,a.candidate_sources_output]+([a.geometries_output] if a.geometries_output else [])
        if len({p.resolve() for p in outputs})!=len(outputs) or any(p.exists() or p.is_symlink() for p in outputs):
            raise ValueError("NEW_DISTINCT_OUTPUTS_REQUIRED")
        report=export_registry(a.db,_safe_file(a.mapping),include_geometries=a.geometries_output is not None)
        geometry_projection=report.pop("geometry_projection_private",None)
        write_private_json(a.output,report)
        write_private_json(a.candidate_sources_output,report["candidate_source_records_private"])
        if a.geometries_output is not None:
            write_private_json(a.geometries_output,geometry_projection)
        counts=report["counts"]
    print(json.dumps({"private_report_written":True,"counts":counts,"legal_links_verified":False}))


if __name__=="__main__":
    main()
