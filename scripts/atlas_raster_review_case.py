#!/usr/bin/env python3
"""Local scan-plan case and manually traced pixel outlines, not legal geometry."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import tempfile
from PIL import Image
from scripts.atlas_archive_review_batch import _stage_verified
from scripts.atlas_archive_deep_review import _command
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_georeference_case import digest,new_output,write_bytes,MAX_RENDER
from scripts.atlas_geometry_evidence import _ring
from scripts.atlas_property_index import _sha
from scripts.atlas_reviewer_workbench import clean_observation
from scripts.atlas_spatial_context import private_json
from services.verification.ocr.parcel_scan import _extract_display_scan

def candidate(ring,w,h,method,ordinal=1):
    r=[list(p) for p in _ring(ring)]
    if any(not(0<=x<=w and 0<=y<=h) for x,y in r):
        raise ValueError("OUTLINE_OUTSIDE_RASTER_FRAME")
    xs,ys=zip(*r)
    border=(max(xs)-min(xs))*(max(ys)-min(ys))/(w*h)>=.85
    return {"ordinal":ordinal,"page_ring":r,"outline_sha256":digest(r),
            "possible_frame_or_border":border,"method":method}

def write_trace_workbench(output,case,png):
    case_path=output/"case.json"
    payload={"case_sha256":hashlib.sha256(case_path.read_bytes()).hexdigest(),
             "document_sha256":case["document_sha256_private"],"page":case["page"],
             "render_sha256":case["page_render_private"]["sha256"],
             "width":case["display_width_pixels"],"height":case["display_height_pixels"],
             "outlines":case["outlines_private"],"png":base64.b64encode(png).decode("ascii")}
    data=json.dumps(payload,ensure_ascii=False,separators=(",",":"),allow_nan=False)
    data=data.replace("&","\\u0026").replace("<","\\u003c").replace(">","\\u003e")
    template=Path(__file__).with_name("atlas_raster_trace.html").read_text(encoding="utf-8")
    if template.count("__ATLAS_TRACE_PAYLOAD__")!=1:
        raise ValueError("TRACE_TEMPLATE_INVALID")
    write_bytes(output/"trace.html",template.replace("__ATLAS_TRACE_PAYLOAD__",data).encode("utf-8"))

def prepare_scan(index_path,pdf,sha,page,output,*,crop=None):
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
    with tempfile.TemporaryDirectory(prefix=".atlas-raster-",dir=output.parent) as t:
        work=Path(t).resolve()
        staged=work/"document.pdf"
        size=_stage_verified(pdf,staged,sha)
        info,status=_command(["pdfinfo",str(staged)],work)
        m=re.search(r"^Pages:\s+([0-9]+)\s*$",info or "",re.M)
        if status!="COMPLETED" or not m or page>int(m[1]):
            raise ValueError("PDF_PAGE_UNAVAILABLE")
        _,status=_command(["pdftoppm","-f",str(page),"-l",str(page),"-cropbox","-scale-to","2200",
                 "-singlefile","-png",str(staged),str(work/"page")],work,timeout=60)
        image=work/"page.png"
        if status!="COMPLETED" or not image.is_file() or image.stat().st_size>MAX_RENDER:
            raise ValueError("RASTER_RENDER_FAILED")
        with Image.open(image,formats=["PNG"]) as im:
            w,h=im.size
            if not 175<=min(w,h) or max(w,h)>2300:
                raise ValueError("RASTER_FRAME_LIMIT")
            im.load()
            left,top,right,bottom=(0,0,w,h) if crop is None else crop
            if any(type(n) is not int for n in (left,top,right,bottom)) or not (
                0<=left<right<=w and 0<=top<bottom<=h and right-left>=175 and bottom-top>=175):
                raise ValueError("INVALID_RASTER_CROP")
            # Same owner scan algorithm, explicit validated PDF raster frame.
            # No eight-page owner-upload cap for this archive review.
            scan=_extract_display_scan(im.crop((left,top,right,bottom)),page)
            outlines=[]
            state=scan["status"]
            if state=="CANDIDATE" and scan.get("outline"):
                r=[[x+left,y+top] for x,y in scan["outline"]["ring"]]
                try:outlines.append(candidate(r,w,h,"RASTER_INK_CONTOUR"))
                except ValueError:state="CONTOUR_REVIEW_REQUIRED"
        png=image.read_bytes()
    case={"kind":"PRIVATE_ATLAS_GEOREFERENCE_CASE","schema_version":1,
          "document_sha256_private":sha,"index_sha256_private":ih,"document_bytes":size,
          "document_page_count":int(m[1]),"page":page,"page_unit":"PIXEL",
          "display_width_pixels":w,"display_height_pixels":h,"outlines_private":outlines,
          "scan_state":state,"crop_pixels":[left,top,right,bottom],
          "page_render_private":{"width_pixels":w,"height_pixels":h,"sha256":hashlib.sha256(png).hexdigest()},
          "page_observations_private":[clean_observation(o) for o in rows[0]["observations_private"] if o.get("page")==page],
          "official_plan_verified":None,"ground_verified":False,"public_export_permitted":False}
    output.mkdir(mode=0o700)
    write_bytes(output/"page.png",png)
    write_private_json(output/"case.json",case)
    write_trace_workbench(output,case,png)
    return case

def trace(case_path,annotation_path,output):
    case,ch=private_json(case_path)
    ann,ah=private_json(annotation_path)
    if (case.get("kind")!="PRIVATE_ATLAS_GEOREFERENCE_CASE" or case.get("page_unit")!="PIXEL"
        or case.get("ground_verified") is not False or case.get("official_plan_verified") is not None
        or case.get("public_export_permitted") is not False):
        raise ValueError("PRIVATE_RASTER_CASE_REQUIRED")
    frame=case["page_render_private"]
    if (ann.get("kind")!="PRIVATE_ATLAS_MANUAL_PIXEL_TRACE" or ann.get("case_sha256")!=ch
        or ann.get("document_sha256")!=case["document_sha256_private"] or ann.get("page")!=case["page"]
        or ann.get("render_sha256")!=frame["sha256"]):
        raise ValueError("TRACE_CASE_BINDING_MISMATCH")
    image=Path(case_path).parent/"page.png"
    if image.is_symlink() or not image.is_file() or image.stat().st_mode&0o077 or image.stat().st_size>MAX_RENDER:
        raise ValueError("PRIVATE_RASTER_REQUIRED")
    png=image.read_bytes()
    if hashlib.sha256(png).hexdigest()!=_sha(frame["sha256"]):
        raise ValueError("RENDER_SHA_MISMATCH")
    w,h=case["display_width_pixels"],case["display_height_pixels"]
    with Image.open(image,formats=["PNG"]) as im:
        if im.size!=(w,h) or not 175<=min(w,h) or max(w,h)>2300:
            raise ValueError("RASTER_FRAME_MISMATCH")
    previous=case["outlines_private"]
    if len(previous)>=1000:raise ValueError("OUTLINE_LIMIT")
    outline=candidate(ann.get("pixel_ring"),w,h,"MANUAL_PIXEL_TRACE",max([o["ordinal"] for o in previous],default=0)+1)
    result={**case,"outlines_private":[*previous,outline],
            "parent_case_sha256_private":ch,"manual_trace_sha256_private":ah}
    output=new_output(output)
    output.mkdir(mode=0o700)
    write_bytes(output/"page.png",png)
    write_private_json(output/"case.json",result)
    write_trace_workbench(output,result,png)
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest="command",required=True)
    a=sub.add_parser("prepare")
    a.add_argument("--index",type=Path,required=True);a.add_argument("--pdf",type=Path,required=True)
    a.add_argument("--sha256",required=True);a.add_argument("--page",type=int,required=True)
    a.add_argument("--crop",type=int,nargs=4);a.add_argument("--output-directory",type=Path,required=True)
    b=sub.add_parser("trace")
    b.add_argument("--case",type=Path,required=True);b.add_argument("--annotation",type=Path,required=True)
    b.add_argument("--output-directory",type=Path,required=True)
    args=p.parse_args()
    r=prepare_scan(args.index,args.pdf,args.sha256,args.page,args.output_directory,crop=args.crop) if args.command=="prepare" else trace(args.case,args.annotation,args.output_directory)
    print(json.dumps({"private_scan_case_written":True,"scan_state":r["scan_state"],
                      "outline_count":len(r["outlines_private"]),"official_plan_verified":"UNKNOWN"}))
if __name__=="__main__":main()
