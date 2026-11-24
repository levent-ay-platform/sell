"""Private, conservative Apple Vision supplement for Tunisian CIN previews.
Only labelled/front-region candidates confirmed in two image views are used.
No identity verification, logging or persistent document storage.
"""
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unicodedata
from datetime import date

from cin_mac_date import native_birth_dates

KEYS=("surname","givenName","cinFull","birthDate")
DIGITS=str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹","01234567890123456789")
LABELS={"surname":r"(?:اللقب|لقب)", "givenName":r"(?:الاسم|اسم)"}
def normalized(text):
    text=unicodedata.normalize("NFKC",text).translate(DIGITS)
    text="".join(c for c in text if not unicodedata.combining(c) and unicodedata.category(c)!="Cf")
    return " ".join(text.replace("أ","ا").replace("إ","ا").replace("آ","ا").replace("ـ","").split())

def candidates(document,w,h):
    out={k:set() for k in KEYS}
    if not isinstance(document,dict):return out
    pages=document.get("pages")
    if not isinstance(pages,list) or len(pages)!=1 or not isinstance(pages[0],dict):return out
    lines=pages[0].get("lines")
    if not isinstance(lines,list) or len(lines)>600:return out
    valid=[]
    for line in lines:
        if not isinstance(line,dict):continue
        t=line.get("text");s=line.get("confidence");b=line.get("bbox")
        if not isinstance(t,str) or len(t)>180 or type(s) not in (int,float) or not math.isfinite(s) or not .70<=s<=1:continue
        if not isinstance(b,dict) or not all(type(b.get(k)) is int for k in ("left","top","right","bottom")):continue
        if not (0<=b["left"]<b["right"]<=w and 0<=b["top"]<b["bottom"]<=h):continue
        y=(b["top"]+b["bottom"])/(2*h)
        valid.append((normalized(t),y))
    # Require evidence of a front-card layout, not an unrelated document.
    front=any(("بطاقة" in t and "تعريف" in t) and y<.32 for t,y in valid)
    labels={k for t,y in valid if .42<=y<=.73 for k,p in LABELS.items() if re.search(r"(?:^|\s)"+p+r"(?:\s|:|$)",t)}
    if not front and labels!=set(LABELS):return out
    given_rows=[]
    for t,y in valid:
        for k,p in LABELS.items():
            lo,hi=(.43,.61) if k=="surname" else (.54,.72)
            if not lo<=y<=hi:continue
            m=re.fullmatch(p+r"\s*:?\s*([\u0621-\u064a]+(?: [\u0621-\u064a]+){0,3})",t)
            if not m:continue
            value=m.group(1)
            if any(re.search(r"(?:^| )"+other+r"(?: |$)",value) for other in (*LABELS.values(),r"الولادة|الميلاد|الجمهورية|التونسية")):continue
            out[k].add(value)
            if k=="givenName":given_rows.append(y*h)
        if .23<=y<=.47:
            m=re.fullmatch(r"(?:(?:رقم(?: بطاقة التعريف)?|عدد)\s*:?\s*)?([0-9]{8})",t)
            if m:out["cinFull"].add(m.group(1))
    if len(given_rows)==1:
        for d in native_birth_dates(document,w,h,given_rows[0]):
            if date.fromisoformat(d)<=date.today():out["birthDate"].add(d)
    return out

def consensus(first,second):
    return {k:next(iter(first[k])) if len(first[k])==1 and first[k]==second[k] else None for k in KEYS}

def merge_fields(v5,native):
    """Conflicting high-confidence candidates require owner correction."""
    result=dict(v5)
    for k in KEYS:
        a,b=v5.get(k),native.get(k)
        if a and b and normalized(a)!=normalized(b):result[k]=None
        elif not a and b:result[k]=b
    result["cinLast4"]=result["cinFull"][-4:] if result.get("cinFull") else None
    # The original confidence is v5's name confidence, not a native probability.
    if any(result.get(k)!=v5.get(k) for k in ("surname","givenName")):
        result["confidencePercent"]=0
    return result

def run_document(executable,image,directory):
    import cv2
    path=Path(directory)/"front.png"
    path.touch(mode=0o600,exist_ok=True)
    if not cv2.imwrite(str(path),image):raise ValueError("image encode")
    env={k:v for k,v in os.environ.items() if k!="MALAK_VISION_PLAN_RASTERS"}
    proc=subprocess.run([executable,str(path)],stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,timeout=5,check=True,env=env)
    if len(proc.stdout)>4_000_000:raise ValueError("worker output")
    doc=json.loads(proc.stdout)
    if not isinstance(doc,dict) or doc.get("format")!="image" or not isinstance(doc.get("pages"),list) or len(doc["pages"])!=1:raise ValueError("worker schema")
    return doc

def probe_vision():
    executable=os.environ.get("MALAK_VISION_BIN")
    if not executable or not Path(executable).is_file() or not os.access(executable,os.X_OK):return False
    try:
        import numpy as np
        with tempfile.TemporaryDirectory(prefix="malak-vision-probe-") as directory:
            run_document(executable,np.full((240,380,3),255,dtype=np.uint8),directory)
        return True
    except (OSError,ValueError,TypeError,subprocess.SubprocessError):return False

def read_vision(photo,normalize_card):
    executable=os.environ.get("MALAK_VISION_BIN")
    if not executable or not Path(executable).is_file() or not os.access(executable,os.X_OK):return {}, "unavailable"
    import cv2
    card=gray=None
    try:
        card=normalize_card(photo)
        if card is None:return {}, "card-not-localized"
        h,w=card.shape[:2]
        gray=cv2.cvtColor(cv2.cvtColor(card,cv2.COLOR_BGR2GRAY),cv2.COLOR_GRAY2BGR)
        with tempfile.TemporaryDirectory(prefix="malak-cin-dual-") as directory:
            views=[candidates(run_document(executable,image,directory),w,h) for image in (card,gray)]
        return consensus(*views), "completed"
    except (OSError,ValueError,TypeError,KeyError,cv2.error,subprocess.SubprocessError):return {}, "failed"
    finally:
        if gray is not None:gray.fill(0)
        if card is not None and card is not photo:card.fill(0)
