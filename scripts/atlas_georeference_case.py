#!/usr/bin/env python3
"""Private document-bound georeferencing review case, never a legal approval.

Explicit local PDF and hash from an existing private index; stage verified
bytes, extract selected page outlines, optionally render its actual cropbox.
Controls are separately supplied by a reviewer, not fabricated from a title.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from scripts.atlas_archive_review_batch import _stage_verified
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_geometry_evidence import assess_georeferencing, _ring, _point
from scripts.atlas_pdf_vector_candidates import extract_pdf
from scripts.atlas_property_index import _sha
from scripts.atlas_reviewer_workbench import clean_observation
from scripts.atlas_spatial_context import private_json

MAX_RENDER=16*1024**2

def digest(obj):
    return hashlib.sha256(json.dumps(obj,ensure_ascii=False,sort_keys=True,
            separators=(",",":"),allow_nan=False).encode()).hexdigest()

def new_output(path):
    path=Path(path)
    if path.exists() or path.is_symlink() or any(p.is_symlink() for p in path.parents):
        raise ValueError("NEW_PRIVATE_CASE_DIRECTORY_REQUIRED")
    if not path.parent.is_dir() or path.parent.stat().st_mode&0o077:
        raise ValueError("PRIVATE_CASE_PARENT_REQUIRED")
    return path

def write_bytes(path,data):
    fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY|getattr(os,"O_NOFOLLOW",0),0o600)
    with os.fdopen(fd,"wb") as f:
        f.write(data);f.flush();os.fsync(f.fileno())

def prepare(index_path,pdf,sha,page,output,*,render=False):
    sha=_sha(sha)
    index,ih=private_json(index_path)
    if (index.get("kind")!="PRIVATE_ATLAS_PROPERTY_INDEX" or index.get("public_export_permitted") is not False
            or index.get("official_plan_count") is not None):
        raise ValueError("PRIVATE_CANDIDATE_INDEX_REQUIRED")
    rows=[r for r in index["documents_private"] if r["document_sha256"]==sha]
    if len(rows)!=1 or rows[0].get("official_plan_verified") is not None or rows[0].get("ground_verified") is not False:
        raise ValueError("UNIQUE_UNVERIFIED_DOCUMENT_REQUIRED")
    if type(page) is not int or not 1<=page<=100000:
        raise ValueError("EXPLICIT_PAGE_REQUIRED")
    pdf=Path(pdf)
    if pdf.is_symlink() or not pdf.is_file() or any(p.is_symlink() for p in pdf.parents):
        raise ValueError("REGULAR_APPROVED_LOCAL_PDF_REQUIRED")
    output=new_output(output)
    png=None
    frame=None
    with tempfile.TemporaryDirectory(prefix=".atlas-review-",dir=output.parent) as t:
        work=Path(t)
        staged=work/"document.pdf"
        size=_stage_verified(pdf,staged,sha)
        vector=extract_pdf(staged,sha,pages=[page])
        if vector["document_sha256_private"]!=sha or vector["ground_verified"] is not False:
            raise ValueError("VECTOR_BINDING_MISMATCH")
        p=vector["pages_private"][0]
        w,h=p["display_width_points"],p["display_height_points"]
        if p["page"]!=page or p["page_unit"]!="PAGE_POINT" or not 0<w<=100000 or not 0<h<=100000:
            raise ValueError("PAGE_FRAME_MISMATCH")
        outlines=[]
        for ordinal,o in enumerate(p["outlines_private"],1):
            if o["geometry_status"]!="PAGE_OUTLINE_CANDIDATE" or o["ground_verified"] is not False:
                raise ValueError("CANDIDATE_OUTLINE_REQUIRED")
            r=[list(x) for x in _ring(o["page_ring"])]
            if any(not(0<=x<=w and 0<=y<=h) for x,y in r):
                raise ValueError("OUTLINE_FRAME_MISMATCH")
            outlines.append({"ordinal":ordinal,"page_ring":r,"outline_sha256":digest(r),
                "possible_frame_or_border":o["possible_frame_or_border"],
                "page_area_points_squared":o["page_area_points_squared"]})
        if render:
            if not shutil.which("pdftoppm"):
                raise ValueError("POPPLER_REQUIRED")
            try:
                subprocess.run(["pdftoppm","-f",str(page),"-l",str(page),"-cropbox","-scale-to","2200",
                    "-singlefile","-png",str(staged),str(work/"page")],cwd=work,
                    stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,timeout=60)
            except (OSError,subprocess.SubprocessError) as exc:
                raise ValueError("PAGE_RENDER_FAILED") from exc
            image=work/"page.png"
            if not image.is_file() or image.stat().st_size>MAX_RENDER:
                raise ValueError("PAGE_RENDER_SIZE_LIMIT")
            from PIL import Image
            with Image.open(image) as im:
                iw,ih_pixels=im.size
                if not 1<=iw<=2300 or not 1<=ih_pixels<=2300 or abs((iw/ih_pixels)/(w/h)-1)>.02:
                    raise ValueError("PAGE_RENDER_FRAME_MISMATCH")
                im.verify()
            png=image.read_bytes()
            frame={"width_pixels":iw,"height_pixels":ih_pixels,"sha256":hashlib.sha256(png).hexdigest()}
    observations=[clean_observation(o) for o in rows[0]["observations_private"] if o.get("page")==page]
    case={"kind":"PRIVATE_ATLAS_GEOREFERENCE_CASE","schema_version":1,
        "document_sha256_private":sha,"index_sha256_private":ih,"document_bytes":size,
        "document_page_count":vector["document_page_count"],"page":page,"page_unit":"PAGE_POINT",
        "display_width_points":w,"display_height_points":h,"rotation_degrees":p["rotation_degrees"],
        "outlines_private":outlines,"page_observations_private":observations,
        "page_render_private":frame,"limitations_detected":p["limitations_detected"],
        "official_plan_verified":None,"ground_verified":False,"public_export_permitted":False}
    output.mkdir(mode=0o700)
    write_private_json(output/"case.json",case)
    if png is not None:
        write_bytes(output/"page.png",png)
        # Static, local-only proof sheet. No source path, URL, external request
        # or executable interpolation. All SVG numbers come from validated data.
        image64=base64.b64encode(png).decode("ascii")
        paths=[]
        for outline in outlines:
            points=" ".join(f"{x:.6f},{y:.6f}" for x,y in outline["page_ring"])
            x,y=outline["page_ring"][0]
            paths.append(f'<polygon points="{points}" fill="none" stroke="#b31942" stroke-width="1.5" vector-effect="non-scaling-stroke"/><text x="{x:.6f}" y="{y:.6f}" font-size="{max(w,h)*.018:.6f}" fill="#b31942">{outline["ordinal"]}</text>')
        html=('<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8">'
              '<meta name="viewport" content="width=device-width, initial-scale=1">'
              '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src data:; style-src \'unsafe-inline\'">'
              '<title>مراجعة مخطط عقاري خاصة</title><style>body{font:16px system-ui;background:#f5f6f7;margin:20px;color:#202a30}svg{width:100%;max-height:80vh;background:white;border:1px solid #bbb}</style>'
              '<h1>مراجعة صفحة المخطط الأصلية</h1><p>نسخة خاصة. الأشكال المرقمة مرشحة وليست حدوداً قانونية. لا تنشر هذه الصفحة.</p>'
              f'<svg viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg"><image width="{w}" height="{h}" preserveAspectRatio="none" href="data:image/png;base64,{image64}"/>'
              +"".join(paths)+"</svg></html>")
        write_bytes(output/"page-review.html",html.encode("utf-8"))
    return case

def assess(case_path,controls_path,output):
    case,ch=private_json(case_path)
    controls,ph=private_json(controls_path)
    if (case.get("kind")!="PRIVATE_ATLAS_GEOREFERENCE_CASE" or case.get("schema_version")!=1
        or case.get("public_export_permitted") is not False or case.get("ground_verified") is not False
        or case.get("official_plan_verified") is not None or case.get("page_unit")!="PAGE_POINT"):
        raise ValueError("PRIVATE_UNVERIFIED_CASE_REQUIRED")
    if (controls.get("kind")!="PRIVATE_ATLAS_CONTROL_OBSERVATIONS" or controls.get("case_sha256")!=ch
        or controls.get("document_sha256")!=case.get("document_sha256_private")
        or controls.get("page")!=case.get("page")):
        raise ValueError("CONTROL_CASE_BINDING_MISMATCH")
    ordinal=controls.get("outline_ordinal")
    selected=[o for o in case["outlines_private"] if type(ordinal) is int and o["ordinal"]==ordinal]
    if len(selected)!=1:
        raise ValueError("EXPLICIT_UNIQUE_OUTLINE_REQUIRED")
    selected=selected[0]
    r=[list(p) for p in _ring(selected["page_ring"])]
    if selected["outline_sha256"]!=digest(r) or controls.get("outline_sha256")!=selected["outline_sha256"]:
        raise ValueError("OUTLINE_BINDING_MISMATCH")
    if selected.get("possible_frame_or_border") and controls.get("border_reviewed_as_candidate") is not True:
        raise ValueError("POSSIBLE_FRAME_REVIEW_REQUIRED")
    w,h=case["display_width_points"],case["display_height_points"]
    if not 0<w<=100000 or not 0<h<=100000 or any(not(0<=x<=w and 0<=y<=h) for x,y in r):
        raise ValueError("INVALID_CASE_FRAME")
    pts=controls.get("control_points")
    if not isinstance(pts,list) or not 4<=len(pts)<=32:
        raise ValueError("BOUNDED_CONTROL_POINTS_REQUIRED")
    ids=set()
    for p in pts:
        ident=p.get("control_id")
        if not isinstance(ident,str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}",ident) or ident in ids:
            raise ValueError("UNIQUE_CONTROL_ID_REQUIRED")
        ids.add(ident)
        _sha(p.get("source_evidence_sha256"))
        x,y=_point(p.get("page"))
        if not 0<=x<=w or not 0<=y<=h:
            raise ValueError("CONTROL_OUTSIDE_PAGE")
    # Source digests are operator-supplied references, not authentication.
    # Check coordinates, independent holdout and residuals; never certify authority.
    spec={"page_ring":r,"page_unit":"PAGE_POINT","target_crs":"EPSG:4326",
          "control_points":pts,"maximum_holdout_error_m":controls.get("maximum_holdout_error_m"),
          "printed_area_m2":controls.get("printed_area_m2")}
    if spec["printed_area_m2"] is not None:
        _sha(controls.get("printed_area_evidence_sha256"))
    qa=assess_georeferencing(spec)
    result={"kind":"PRIVATE_ATLAS_DOCUMENT_GEOREFERENCE_QA","schema_version":1,
            "case_sha256_private":ch,"controls_sha256_private":ph,
            "document_sha256_private":case["document_sha256_private"],"page":case["page"],
            "outline_ordinal":ordinal,"outline_sha256_private":selected["outline_sha256"],
            "qa":qa,"authority_verified":False,"title_parcel_link":"UNVERIFIED",
            "control_source_authenticity_verified":False,"official_plan_verified":None,
            "ground_verified":False,"public_export_permitted":False}
    output=Path(output)
    if not output.parent.is_dir() or output.parent.stat().st_mode&0o077 or any(p.is_symlink() for p in output.parents):
        raise ValueError("PRIVATE_QA_OUTPUT_REQUIRED")
    write_private_json(output,result)
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest="command",required=True)
    a=sub.add_parser("prepare")
    a.add_argument("--index",type=Path,required=True)
    a.add_argument("--pdf",type=Path,required=True)
    a.add_argument("--sha256",required=True)
    a.add_argument("--page",type=int,required=True)
    a.add_argument("--output-directory",type=Path,required=True)
    a.add_argument("--render-private-page",action="store_true")
    b=sub.add_parser("assess")
    b.add_argument("--case",type=Path,required=True)
    b.add_argument("--controls",type=Path,required=True)
    b.add_argument("--output",type=Path,required=True)
    args=p.parse_args()
    if args.command=="prepare":
        r=prepare(args.index,args.pdf,args.sha256,args.page,args.output_directory,render=args.render_private_page)
        print(json.dumps({"private_case_written":True,"outline_count":len(r["outlines_private"]),
            "page_rendered":r["page_render_private"] is not None,"official_plan_verified":"UNKNOWN"}))
    else:
        r=assess(args.case,args.controls,args.output)
        qa=r["qa"]
        print(json.dumps({"private_qa_written":True,"geometry_status":qa["geometry_status"],
            "fit_rms_m":qa["fit_rms_m"],"holdout_max_error_m":qa["holdout_max_error_m"],
            "ground_verified":False,"official_plan_verified":"UNKNOWN"}))
if __name__=="__main__":main()
