"""Private benchmark of actual source references against existing Atlas search and PDF evidence.

No writes to Atlas, no OCR, no new downloads. Read atomic completed windows only.
"""
from __future__ import annotations
import argparse,csv,json,re,shutil,sqlite3,subprocess,sys,tempfile,unicodedata
from contextlib import closing
from scripts.atlas_archive_review_batch import _digest
from pathlib import Path
from collections import Counter,defaultdict
from scripts.atlas_cadastre_evidence_audit import DIGITS,write_private_json
from scripts.atlas_archive_full_review import first_chain,load_plan,private_dir,observe_window
from scripts.atlas_spatial_context import private_json
from scripts.atlas_property_index import _sha

def exact_ref(raw):
    if not isinstance(raw,str):return None
    v=unicodedata.normalize("NFKC",raw).translate(DIGITS).strip()
    m=re.fullmatch(r"([0-9]{3,12})(?:\s*[/\\]\s*([A-Za-z0-9\u0600-\u06ff-]{1,32}))?",v)
    return (m[1],m[2].casefold() if m[2] else None) if m else None

def compare_ref(source,number,suffix):
    candidate=exact_ref(number+("/"+suffix if suffix else "")) if isinstance(number,str) else None
    if source is None or candidate is None or source[0]!=candidate[0]:return None
    if source[1]==candidate[1]:
        return "EXACT_TOKEN_SCOPE_UNVERIFIED" if source[1] else "BARE_TOKEN_SCOPE_UNVERIFIED"
    return "NUMBER_ONLY_SUFFIX_UNRESOLVED" if source[1] is None or candidate[1] is None else None

def add_matches(hits,source_refs,sha,page,origin,title_rows,parcel_present,cadastral):
    for n,s in title_rows:
        for source in source_refs.get(n,()):
            status=compare_ref(source,n,s)
            if status:
                hits[source].add((sha,page,origin,status,bool(parcel_present),bool(cadastral)))

def result_reference(result):
    # Actual Atlas search DTO. Treat its normalized reference as a retrieval
    # token only; it may have dropped jurisdiction or a compound identifier.
    if not isinstance(result,dict):return None
    value=result.get("reference")
    if type(value) is int and value>=0:value=str(value)
    return exact_ref(value)

def stored_numeric_hits(db,expected,docs,refs):
    # Search existing text for exact standalone digit sequences, including labels
    # in tables not recognized by the title parser. Weak evidence only.
    if db.is_symlink() or not db.is_file() or any(Path(str(db)+s).exists() for s in ("-wal","-shm")):
        raise ValueError("STANDALONE_SNAPSHOT_REQUIRED")
    if _digest(db)!=expected:raise ValueError("SNAPSHOT_BINDING_MISMATCH")
    result=defaultdict(set);counts=Counter()
    with closing(sqlite3.connect(db.resolve().as_uri()+"?mode=ro&immutable=1",uri=True)) as conn:
        conn.execute("PRAGMA query_only=ON")
        for sha,page,txt in conn.execute("SELECT sha256,page,text FROM pages"):
            if sha not in docs or not isinstance(txt,str) or not txt.strip():continue
            counts["indexed_text_pages_checked"]+=1
            if len(txt)>2*1024**2:
                counts["oversize_indexed_text_pages_skipped"]+=1;continue
            text=unicodedata.normalize("NFKC",txt).translate(DIGITS)
            for token in set(re.findall(r"(?<!\d)\d{3,12}(?!\d)",text))&refs.keys():
                result[token].add((sha,page))
    if _digest(db)!=expected:raise ValueError("SNAPSHOT_CHANGED")
    return result,counts

def identity(row,result):
    # Native ID alone can recur across layers. Only compare explicit same-source fields.
    if not isinstance(result,dict):return "RESULT_SCHEMA_UNRESOLVED"
    if not all(k in result for k in ("native_id","snapshot")):
        if all(k in result for k in ("feature_id","layer","reference","has_geometry","areas")):
            return "GIS_FEATURE_RETURNED_SOURCE_IDENTITY_UNPROVEN"
        return "RESULT_SCHEMA_UNRESOLVED"
    if str(result["native_id"])!=row["native_id"] or str(result["snapshot"])!=row["snapshot"]:
        return "EXPLICIT_SOURCE_ID_MISMATCH"
    if "record_set" not in result:return "NATIVE_ID_SNAPSHOT_MATCH_GROUP_UNRESOLVED"
    return "SOURCE_IDENTITY_MATCH" if result["record_set"]==row["record_set"] else "EXPLICIT_SOURCE_ID_MISMATCH"

def benchmark(atlas,run,native,output):
    atlas=Path(atlas).resolve(strict=True);run=private_dir(run);native=private_dir(native)
    output=Path(output).absolute()
    private_dir(output.parent)
    if output.exists() or output.is_symlink() or output.resolve().is_relative_to(atlas) or output.resolve().is_relative_to(run) or output.resolve().is_relative_to(native):
        raise ValueError("SEPARATE_NEW_PRIVATE_OUTPUT_REQUIRED")
    data=atlas/"data"
    with (data/"derived/names-sweep-20261115-r3/lotissement_title_same_record.csv").open(encoding="utf-8-sig") as f:
        rows=list(csv.DictReader(f))
    if not rows or len(rows)>1000:raise ValueError("BOUNDED_SOURCE_SAMPLE_REQUIRED")
    refs=defaultdict(set)
    counts=Counter()
    # Reuse existing local search implementation with a private temporary copy.
    manifest=json.loads((data/"map-index/manifest.json").read_text())
    db=data/"map-index"/Path(manifest["file"]).name
    if db.is_symlink() or not db.is_file() or any(Path(str(db)+s).exists() for s in ("-wal","-shm")):
        raise ValueError("STANDALONE_MAP_INDEX_REQUIRED")
    sys.path.insert(0,str(atlas))
    from atlas import title_lookup
    details=[];shapes=Counter(); retrieved=[]
    with tempfile.TemporaryDirectory(prefix="atlas-source-test-",dir=output.parent) as temp:
        dst=Path(temp)/db.name;before=db.stat()
        cp=subprocess.run(["/bin/cp","-c",str(db),str(dst)],capture_output=True)
        if cp.returncode:shutil.copyfile(db,dst)
        after=db.stat()
        if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns):raise ValueError("MAP_INDEX_CHANGED")
        # Use the actual existing search engine first, before collecting PDF evidence.
        for row in rows:
            counts["source_rows_tested"]+=1
            raw=exact_ref(row["raw_reference"])
            if raw is None:counts["source_reference_compound_or_unparsed"]+=1
            tokens=set()
            if raw:tokens.add(raw)
            try:
                normalized=title_lookup.normalize(row["raw_reference"])
                token=result_reference({"reference":normalized})
                if token:tokens.add(token)
            except (ValueError,TypeError):
                counts["existing_normalization_rejected"]+=1
            records=[]
            try:
                result=title_lookup.search(dst,row["raw_reference"])
                result=result.get("results",[]) if isinstance(result,dict) else result
                if not isinstance(result,list):raise ValueError("UNKNOWN_RESULT_SHAPE")
                counts["search_completed"]+=1
                counts["search_with_results"]+=bool(result)
                identities=[]
                for record in result:
                    if isinstance(record,dict):
                        shapes.update(record.keys())
                        token=result_reference(record)
                        if token:tokens.add(token)
                        records.append({k:record[k] for k in ("reference","feature_id","layer","has_geometry","areas") if k in record})
                    identities.append(identity(row,record))
                state="SOURCE_IDENTITY_MATCH" if "SOURCE_IDENTITY_MATCH" in identities else (
                    "NO_RESULT" if not result else "|".join(sorted(set(identities))))
            except Exception as exc:
                state="SEARCH_ERROR_"+type(exc).__name__
            counts[state]+=1
            counts["source_rows_with_query_tokens"]+=bool(tokens)
            counts["source_rows_recovered_by_existing_search"]+=raw is None and bool(tokens)
            counts["source_rows_with_returned_geometry"]+=any(r.get("has_geometry") is True for r in records)
            for token in tokens:refs[token[0]].add(token)
            retrieved.append((row,raw,tokens,state,records))
    dirs,audit,docs,entries,last=first_chain(run)
    roots=[(data).resolve()]
    plan,_=load_plan(native,roots,last,docs)
    cfg,_=private_json(native/"full-review-config.json")
    if cfg.get("raster_mode")!="none" or cfg.get("first_pass_manifest_sha256_private")!=last[1]:
        raise ValueError("NATIVE_CONFIG_BINDING_REQUIRED")
    hits=defaultdict(set)
    for sha,doc in docs.items():
        e=entries.get(sha,{})
        for origin,obs in (("CATALOGUE_INDEX",doc.get("page_reference_evidence_private",[])),
                           ("V1_OCR",e.get("page_reference_evidence_private",[]) if e.get("ocr_status")=="COMPLETED" else [])):
            for o in obs:
                add_matches(hits,refs,sha,o.get("page"),origin,[(n,None) for n in o.get("title_reference_candidates",[])],
                            o.get("parcel_reference_candidates"),o.get("cadastral_term_present"))
    documents=native/"full-documents"
    # Freeze filenames once, so an active run can continue without inconsistent counts.
    paths=[]
    for folder in list(documents.iterdir()):
        if folder.is_symlink() or not folder.is_dir():continue
        sha=_sha(folder.name)
        if sha not in docs:raise ValueError("WINDOW_UNKNOWN_DOCUMENT")
        paths.extend((sha,p) for p in sorted(folder.glob("pages-*.json")))
    counts["native_window_files_at_start"]=len(paths)
    covered=set()
    for sha,p in paths:
        w,_=private_json(p)
        start=int(p.stem.split("-")[1])
        limit=min(cfg["page_window"],cfg["max_pages_per_document"]-start+1)
        observe_window(w,sha,start,limit,None,False,False)
        covered.add(sha)
        for page in w["pages_private"]:
            counts["native_pages"]+=1
            for o in page["observations"]:
                e=o.get("evidence") or {}
                if o["status"]!="COMPLETED":continue
                add_matches(hits,refs,sha,page["page"],"DEEP_NATIVE_LAYOUT",
                           [(r["number"],r.get("suffix")) for r in e["title_candidates"]],
                           e["parcel_candidates"],e["cadastral_term_present"])
    counts["native_documents_with_windows"]=len(covered)
    numeric,numeric_counts=stored_numeric_hits(run/"catalogue.sqlite",last[0]["snapshot_sha256_private"],docs,refs)
    counts.update(numeric_counts)
    for row,raw,tokens,state,records in retrieved:
        found=set().union(*(hits.get(token,set()) for token in tokens))
        links=sorted(found,key=lambda x:(x[0],x[1] or 0,x[2],x[3]))
        counts["source_rows_with_document_token_candidates"]+=bool(links)
        counts["source_rows_with_same_observation_parcel_hint"]+=any(x[4] for x in links)
        counts["source_rows_with_qualified_token_candidate"]+=any(x[3]=="EXACT_TOKEN_SCOPE_UNVERIFIED" for x in links)
        weak=set().union(*(numeric.get(n,set()) for n,s in tokens))
        counts["source_rows_with_untyped_numeric_text_occurrences"]+=bool(weak)
        details.append({"untyped_numeric_occurrences_private":[{"sha256":h,"page":p,
                        "status":"NUMBER_OCCURRENCE_NOT_TITLE_EVIDENCE"} for h,p in sorted(weak,key=lambda x:(x[0],x[1] or 0))],
                        "source_record_private":{"record_set":row["record_set"],"snapshot":row["snapshot"],"native_id":row["native_id"],
                        "reference_field":row["reference_field"],"reference":row["raw_reference"]},
                        "identity_status":state,"retrieved_gis_records_private":records,
                        "query_tokens_private":[{"number":n,"suffix":s,"origin":"RAW_EXACT" if (n,s)==raw else "EXISTING_ENGINE_TOKEN"}
                                                for n,s in sorted(tokens,key=lambda x:(x[0],x[1] or ""))],
                        "document_token_candidates_private":[
                         {"sha256":x[0],"page":x[1],"origin":x[2],"status":x[3],"parcel_hint":x[4],"cadastral_hint":x[5]} for x in links],
                        "official_plan_verified":None,"ground_verified":False})
    report={"kind":"PRIVATE_ATLAS_EXISTING_SOURCE_BENCHMARK","counts":dict(counts),
            "limitations":["Search DTO references are retrieval tokens, not proof of original source identity or registry scope.","Stored indexed text also searched for standalone numeric occurrences, which are not title evidence."],
            "result_field_names":dict(shapes),"native_sweep_is_partial":True,
            "official_plans_verified":"UNKNOWN","public_export_permitted":False}
    write_private_json(output,{**report,"rows_private":details})
    return report

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--atlas",required=True,type=Path)
    p.add_argument("--first-pass-run",required=True,type=Path)
    p.add_argument("--native-run",required=True,type=Path)
    p.add_argument("--output",required=True,type=Path)
    a=p.parse_args()
    print(json.dumps(benchmark(a.atlas,a.first_pass_run,a.native_run,a.output),ensure_ascii=False,indent=2))
if __name__=="__main__":main()
