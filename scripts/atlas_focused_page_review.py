#!/usr/bin/env python3
"""Private, bounded actual-page review of the finished native archive sweep."""
from __future__ import annotations
import argparse,html,json,os,shutil,tempfile
from collections import Counter,defaultdict
from pathlib import Path
from scripts.atlas_archive_full_review import first_chain,load_plan,private_dir
from scripts.atlas_archive_review_batch import _safe_relative,_digest
from scripts.atlas_archive_deep_review import _command,MAX_BYTES
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_spatial_context import private_json
from scripts.atlas_reviewer_workbench import build_queue
from scripts.atlas_property_index import _sha

def select(queue,benchmark,cap=80):
    if not 1<=cap<=120:raise ValueError("PAGE_BUDGET_REQUIRED")
    docs={r["sha"]:r for r in queue["rows"]}
    candidates=defaultdict(lambda:defaultdict(set))
    for sha,r in docs.items():
        for o in r["observations"]:
            if o["titles"]:candidates[sha][o["page"] or 1].add("TITLE_HINT")
            if o["cadastral_hint"]:candidates[sha][o["page"] or 1].add("CADASTRAL_HINT")
    if benchmark:
        if benchmark.get("kind")!="PRIVATE_ATLAS_EXISTING_SOURCE_BENCHMARK" or benchmark.get("public_export_permitted") is not False:
            raise ValueError("PRIVATE_BENCHMARK_REQUIRED")
        for r in benchmark["rows_private"]:
            for o in r["untyped_numeric_occurrences_private"]:
                sha=_sha(o["sha256"]);page=o.get("page")
                if sha not in docs:raise ValueError("BENCHMARK_DOCUMENT_NOT_IN_INDEX")
                if type(page) is int and page>=1:candidates[sha][page].add("UNTYPED_NUMBER_ONLY")
    def score(reasons):
        return 100*("CADASTRAL_HINT" in reasons)+80*("TITLE_HINT" in reasons)+10*("UNTYPED_NUMBER_ONLY" in reasons)
    order=sorted(candidates,key=lambda sha:(-max(map(score,candidates[sha].values())),sha))
    # At least one evidence page per priority document, then first page/context
    # and extra evidence; preserve omitted candidates explicitly.
    selected=[];seen=set()
    def put(sha,page,reasons):
        if len(selected)<cap and (sha,page) not in seen:
            selected.append({"sha":sha,"page":page,"reasons":sorted(reasons)})
            seen.add((sha,page))
    for sha in order:
        p=min(candidates[sha],key=lambda x:(-score(candidates[sha][x]),x))
        put(sha,p,candidates[sha][p])
    for sha in order:
        if any("TITLE_HINT" in x or "CADASTRAL_HINT" in x for x in candidates[sha].values()):
            put(sha,1,candidates[sha].get(1,{"DOCUMENT_CONTEXT"}))
    for sha in order:
        for p,reasons in sorted(candidates[sha].items(),key=lambda x:(-score(x[1]),x[0]))[:3]:
            put(sha,p,reasons)
    omitted=[{"sha":sha,"page":p,"reasons":sorted(r)} for sha in order for p,r in sorted(candidates[sha].items()) if (sha,p) not in seen]
    return selected,omitted

def stage(row,root,dst,sha):
    for loc in row["locations_private"]:
        try:
            src=_safe_relative(root,loc["relative_path_private"])
            if src.stat().st_size>MAX_BYTES:continue
            with src.open("rb") as a,dst.open("wb") as b:
                size=0
                while chunk:=a.read(1024**2):
                    size+=len(chunk)
                    if size>MAX_BYTES:raise ValueError("SIZE_LIMIT")
                    b.write(chunk)
            with dst.open("rb") as a:header=a.read(1024)
            if b"%PDF-" in header and _digest(dst)==sha:return True
        except (OSError,ValueError):pass
        dst.unlink(missing_ok=True)
    return False

def run(atlas,first,native,benchmark,output,cap):
    first=private_dir(first);native=private_dir(native)
    output=Path(output).absolute();private_dir(output.parent)
    root=(Path(atlas)/"data").resolve(strict=True)
    if output.exists() or output.is_symlink() or any(output.resolve().is_relative_to(x) for x in (root,first,native)):
        raise ValueError("NEW_SEPARATE_PRIVATE_OUTPUT_REQUIRED")
    summaries=sorted(native.glob("summary-*.json"))
    if not summaries:raise ValueError("TERMINAL_SUMMARY_REQUIRED")
    summary,sh=private_json(summaries[-1])
    if summary.get("kind")!="PRIVATE_ATLAS_FULL_REVIEW_SUMMARY" or summary.get("available_queue_attempted") is not True or summary["counts"]["pending_available_documents"]!=0:
        raise ValueError("TERMINAL_SWEEP_REQUIRED")
    name=summary["combined_index_name_private"]
    if Path(name).name!=name or not name.startswith("property-index-combined-"):raise ValueError("INDEX_NAME_INVALID")
    index,ih=private_json(native/name)
    _,_,docs,_,last=first_chain(first)
    plan,ph=load_plan(native,[root],last,docs)
    if index.get("second_pass_plan_sha256_private")!=ph or index.get("last_batch_manifest_sha256_private")!=last[1]:
        raise ValueError("INDEX_CHAIN_BINDING_MISMATCH")
    queue=build_queue(index)
    b,bh=private_json(benchmark) if benchmark else (None,None)
    chosen,omitted=select(queue,b,cap)
    lookup={r["document_sha256_private"]:r for r in plan["rows_private"]}
    output.mkdir(mode=0o700)
    groups=defaultdict(list)
    for x in chosen:groups[x["sha"]].append(x)
    for sha,items in groups.items():
        with tempfile.TemporaryDirectory(prefix="page-review-",dir=output) as d:
            temp=Path(d);pdf=temp/"verified.pdf"
            ok=stage(lookup[sha],root,pdf,sha)
            for x in items:
                if not ok:x["state"]="SOURCE_BYTES_NOT_REVERIFIED";continue
                page=x["page"];name=f'{sha}-{page}'
                prefix=temp/"page"
                image=prefix.with_suffix(".png")
                _,state=_command(["pdftoppm","-f",str(page),"-l",str(page),"-scale-to","2400","-singlefile","-png",str(pdf),str(prefix)],temp,timeout=90)
                if state=="COMPLETED" and image.is_file() and 0<image.stat().st_size<=32*1024**2:
                    dest=output/(name+".png")
                    shutil.copyfile(image,dest);dest.chmod(0o600)
                    x.update(state="RENDERED_PRIVATE",image=dest.name,render_sha256=_digest(dest))
                else:x["state"]="RENDER_FAILED_"+state
                image.unlink(missing_ok=True)
    report={"kind":"PRIVATE_ATLAS_FOCUSED_PAGE_REVIEW","counts":{"selected_documents":len(groups),"selected_pages":len(chosen),
            "render_states":dict(Counter(x["state"] for x in chosen)),"omitted_evidence_pages":len(omitted)},
            "input_sha256_private":{"summary":sh,"index":ih,"plan":ph,"benchmark":bh},
            "pages_private":chosen,"omitted_private":omitted,"official_plan_verified":None,"public_export_permitted":False}
    write_private_json(output/"review.json",report)
    parts=['<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src \'self\' file:; style-src \'unsafe-inline\'"><title>Atlas — مراجعة المخططات</title><style>body{font:17px system-ui;margin:24px;max-width:1100px;color:#20252b;background:#fafafa}article{background:white;border:1px solid #ddd;padding:16px;margin:16px 0}img{max-width:100%;height:auto}code{overflow-wrap:anywhere}small{color:#555}</style><h1>أطلس — مراجعة صفحات الأدلة</h1><p>مرشحات خاصة، لا إثبات ملكية أو تحديد رسمي. لا تنشر هذه الصفحة.</p>']
    for x in chosen:
        parts.append("<article><h2>صفحة "+str(x["page"])+"</h2><small>"+html.escape(" · ".join(x["reasons"]))+"</small><p><code>"+x["sha"]+"</code></p>")
        if x.get("image"):parts.append('<a href="'+x["image"]+'"><img loading="lazy" src="'+x["image"]+'" alt="صفحة أصلية"></a>')
        else:parts.append("<p>"+html.escape(x["state"])+"</p>")
        parts.append("</article>")
    dest=output/"review.html";dest.write_text("".join(parts)+"</html>",encoding="utf-8");dest.chmod(0o600)
    return report["counts"]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("atlas","first","native","output"):p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--benchmark",type=Path);p.add_argument("--cap",type=int,default=80)
    a=p.parse_args();old=os.umask(0o077)
    try:print(json.dumps(run(a.atlas,a.first,a.native,a.benchmark,a.output,a.cap)))
    finally:os.umask(old)
if __name__=="__main__":main()
