#!/usr/bin/env python3
"""Geometry-first, source-prioritized visual triage; no OCR or legal promotion."""
from __future__ import annotations
import argparse,html,json,math,os,shutil,tempfile
from collections import Counter,defaultdict
from pathlib import Path
from PIL import Image,ImageOps,ImageDraw
from scripts.atlas_archive_full_review import first_chain,load_plan,private_dir
from scripts.atlas_focused_page_review import stage
from scripts.atlas_archive_deep_review import _command
from scripts.atlas_archive_review_batch import _digest
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_spatial_context import private_json
from scripts.atlas_property_index import _sha

def shape_metrics(page):
    w,h=page.get("display_width_points"),page.get("display_height_points")
    if not isinstance(w,(int,float)) or not isinstance(h,(int,float)) or not 0<w<=20000 or not 0<h<=20000:
        return None
    count=nonrect=large=0
    areas=set()
    for o in page.get("outlines_private",[]):
        if o.get("ground_verified") is not False or o.get("geometry_status")!="PAGE_OUTLINE_CANDIDATE" or o.get("possible_frame_or_border") is True:continue
        ring=o.get("page_ring");a=o.get("page_area_points_squared")
        if not isinstance(ring,list) or len(ring)<4 or not isinstance(a,(float,int)) or not math.isfinite(a):continue
        frac=a/(w*h)
        if not .00005<=frac<=.8:continue
        try:
            xs,ys=zip(*ring)
            box=(max(xs)-min(xs))*(max(ys)-min(ys))
            if box<=0:continue
            fill=a/box
        except (TypeError,ValueError):continue
        count+=1
        if frac>=.001:large+=1
        if len(ring)>=6 and fill<.96:nonrect+=1
        areas.add(round(frac,5))
    if not count:return None
    # A heuristic for visually varied polygon networks, not a cadastral classifier.
    score=min(nonrect,25)*4+min(large,20)+min(len(areas),20)-min(max(0,count-nonrect-large),40)
    return {"outline_count":count,"nonrectangular_count":nonrect,"substantial_count":large,
            "distinct_area_bins":len(areas),"geometry_priority":score}

def rank(entries,metadata,reviewed):
    rows=[]
    for sha,meta in metadata.items():
        origin=meta["origins"]
        e=entries.get(sha,{})
        v=e.get("vector_evidence_private") or {}
        pages=[]
        if (e.get("vector_status")=="COMPLETED" and v.get("document_sha256_private")==sha and v.get("ground_verified") is False
            and v.get("kind")=="ATLAS_PRIVATE_PDF_PAGE_OUTLINE_CANDIDATES"):
            for pg in v.get("pages_private",[]):
                p=pg.get("page")
                if type(p) is not int or p<1:continue
                m=shape_metrics(pg)
                if m and (sha,p) not in reviewed:pages.append({"sha":sha,"page":p,"origins":origin,**m})
        pages.sort(key=lambda x:(-x["geometry_priority"],-x["nonrectangular_count"],x["page"]))
        if "OTC" in origin:
            # Review every OTC document's cover unless already seen; additionally
            # preserve the best unseen vector page, which may be an annex.
            p=pages[0] if pages else None
            rows.append({"sha":sha,"page":1,"origins":origin,"kind":"OTC_COVER",
                         "already_reviewed":(sha,1) in reviewed,"cadastre_filename_hint":bool(meta.get("cadastre_filename_hint"))})
            if p and p["page"]!=1:rows.append({**p,"kind":"OTC_VECTOR_PAGE","already_reviewed":False})
        elif pages:
            rows.append({**pages[0],"kind":"GEOMETRY_PAGE","already_reviewed":False})
    return rows

def choose(rows,geometry_cap,otc_vector_cap):
    covers=[r for r in rows if r["kind"]=="OTC_COVER" and not r["already_reviewed"]]
    covers.sort(key=lambda r:(not r["cadastre_filename_hint"],r["sha"]))
    def ordered(kind):
        return sorted((r for r in rows if r["kind"]==kind),key=lambda r:(-r["geometry_priority"],-r["nonrectangular_count"],r["sha"]))
    return covers+ordered("OTC_VECTOR_PAGE")[:otc_vector_cap]+ordered("GEOMETRY_PAGE")[:geometry_cap]

def sheets(out,selected):
    # Stable numbered thumbnails; preserve SHA/page mapping in the private JSON.
    for group in ("OTC_COVER","OTC_VECTOR_PAGE","GEOMETRY_PAGE"):
        rows=[r for r in selected if r["kind"]==group and r.get("image")]
        for i in range(0,len(rows),9):
            sheet=Image.new("RGB",(2400,3300),"white");d=ImageDraw.Draw(sheet)
            for n,r in enumerate(rows[i:i+9]):
                with Image.open(out/r["image"]) as im:
                    im=ImageOps.contain(im.convert("RGB"),(780,1030))
                x=(n%3)*800;y=(n//3)*1100
                d.text((x+12,y+8),f'#{r["ordinal"]} | {group} | p{r["page"]}',fill="black")
                sheet.paste(im,(x+(800-im.width)//2,y+35))
            file=out/f"{group.lower()}-{i//9+1:02d}.jpg"
            sheet.save(file,quality=90);file.chmod(0o600)

def run(atlas,first,native,focused,output,geometry_cap=36,otc_vector_cap=20):
    if not 1<=geometry_cap<=100 or not 0<=otc_vector_cap<=100:raise ValueError("BOUNDED_REVIEW_REQUIRED")
    first=private_dir(first);native=private_dir(native);focused=private_dir(focused)
    root=(Path(atlas)/"data").resolve(strict=True);output=Path(output).absolute();private_dir(output.parent)
    if output.exists() or output.is_symlink() or any(output.resolve().is_relative_to(p) for p in (root,first,native,focused)):
        raise ValueError("NEW_SEPARATE_PRIVATE_OUTPUT_REQUIRED")
    summaries=sorted(native.glob("summary-*.json"))
    if not summaries:raise ValueError("TERMINAL_SUMMARY_REQUIRED")
    summary,sh=private_json(summaries[-1])
    if summary.get("kind")!="PRIVATE_ATLAS_FULL_REVIEW_SUMMARY" or summary.get("available_queue_attempted") is not True:
        raise ValueError("TERMINAL_SWEEP_REQUIRED")
    prior,fh=private_json(focused/"review.json")
    if prior.get("kind")!="PRIVATE_ATLAS_FOCUSED_PAGE_REVIEW" or prior.get("input_sha256_private",{}).get("summary")!=sh:
        raise ValueError("FOCUSED_SUMMARY_BINDING_MISMATCH")
    dirs,_,docs,entries,last=first_chain(first)
    plan,ph=load_plan(native,[root],last,docs)
    if prior["input_sha256_private"].get("plan")!=ph:raise ValueError("FOCUSED_PLAN_BINDING_MISMATCH")
    backlog,bh=private_json(dirs[0]/"backlog.json")
    meta={_sha(r["document_sha256_private"]):r for r in backlog["backlog_private"]+backlog["text_ready_private"]}
    if set(meta)!=set(docs):raise ValueError("BACKLOG_BINDING_MISMATCH")
    reviewed={(r["sha"],r["page"]) for r in prior["pages_private"] if r.get("state")=="RENDERED_PRIVATE"}
    rows=rank(entries,meta,reviewed)
    selected=choose(rows,geometry_cap,otc_vector_cap)
    if len(selected)>400:raise ValueError("RENDER_BUDGET_EXCEEDED")
    output.mkdir(mode=0o700)
    for i,r in enumerate(selected,1):r["ordinal"]=i
    bydoc=defaultdict(list)
    for r in selected:bydoc[r["sha"]].append(r)
    plan_rows={r["document_sha256_private"]:r for r in plan["rows_private"]}
    for i,(sha,items) in enumerate(bydoc.items(),1):
        with tempfile.TemporaryDirectory(prefix="geometry-review-",dir=output) as d:
            tmp=Path(d);pdf=tmp/"verified.pdf"
            ok=stage(plan_rows[sha],root,pdf,sha)
            for r in items:
                if not ok:r["state"]="SOURCE_BYTES_NOT_REVERIFIED";continue
                prefix=tmp/"preview";image=prefix.with_suffix(".png")
                _,state=_command(["pdftoppm","-f",str(r["page"]),"-l",str(r["page"]),"-scale-to","1600","-singlefile","-png",str(pdf),str(prefix)],tmp,timeout=90)
                if state=="COMPLETED" and image.is_file() and 0<image.stat().st_size<=24*1024**2:
                    dest=output/f'{r["ordinal"]:04d}.png';shutil.copyfile(image,dest);dest.chmod(0o600)
                    r.update(state="RENDERED_PRIVATE",image=dest.name,render_sha256=_digest(dest))
                else:r["state"]="RENDER_FAILED_"+state
                image.unlink(missing_ok=True)
        if i%20==0:print(json.dumps({"documents_rendered":i,"selected_documents":len(bydoc)}),flush=True)
    selected_keys={(r["sha"],r["page"],r["kind"]) for r in selected}
    omitted=[r for r in rows if (r["sha"],r["page"],r["kind"]) not in selected_keys]
    counts={"otc_documents":sum("OTC" in r["origins"] for r in meta.values()),
            "previously_rendered_otc_covers":sum(r["kind"]=="OTC_COVER" and r["already_reviewed"] for r in rows),
            "geometry_candidate_documents":sum(r["kind"]=="GEOMETRY_PAGE" for r in rows),
            "selected_by_kind":dict(Counter(r["kind"] for r in selected)),
            "render_states":dict(Counter(r["state"] for r in selected)),
            "remaining_by_kind":dict(Counter(r["kind"] for r in omitted if not r["already_reviewed"]))}
    report={"kind":"PRIVATE_ATLAS_GEOMETRY_TRIAGE","counts":counts,"pages_private":selected,"remaining_private":omitted,
            "input_sha256_private":{"summary":sh,"plan":ph,"focused":fh,"backlog":bh},
            "limitations":["Shape ranking is a heuristic, not official plan classification.","OTC covers do not establish contents of uninspected annexes.","Prior rendered pages are excluded, not assumed legally classified."],
            "official_plan_verified":None,"public_export_permitted":False}
    write_private_json(output/"review.json",report)
    sheets(output,selected)
    parts=['<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src &#39;none&#39;; img-src &#39;self&#39; file:; style-src &#39;unsafe-inline&#39;"><title>Atlas geometry review</title><style>body{font:16px system-ui;margin:24px;background:#f7f7f7}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px}article{background:white;padding:12px;border:1px solid #ddd}img{width:100%;height:260px;object-fit:contain}</style><h1>أطلس — فرز المخططات هندسياً</h1><p>مراجعة خاصة. لا تثبت الهوية العقارية أو حدودها.</p>']
    for group in ("OTC_COVER","OTC_VECTOR_PAGE","GEOMETRY_PAGE"):
        parts.append("<h2>"+group+"</h2><div class='grid'>")
        for r in selected:
            if r["kind"]!=group:continue
            parts.append(f'<article><b>#{r["ordinal"]} · Page {r["page"]}</b><p>{html.escape(r["state"])}</p>')
            if r.get("image"):parts.append(f'<a href="{r["image"]}"><img loading="lazy" src="{r["image"]}"></a>')
            parts.append("</article>")
        parts.append("</div>")
    page=output/"review.html";page.write_text("".join(parts)+"</html>",encoding="utf-8");page.chmod(0o600)
    return counts

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("atlas","first","native","focused","output"):p.add_argument("--"+n,type=Path,required=True)
    p.add_argument("--geometry-cap",type=int,default=36);p.add_argument("--otc-vector-cap",type=int,default=20)
    a=p.parse_args();old=os.umask(0o077)
    try:print(json.dumps(run(a.atlas,a.first,a.native,a.focused,a.output,a.geometry_cap,a.otc_vector_cap)))
    finally:os.umask(old)
if __name__=="__main__":main()
