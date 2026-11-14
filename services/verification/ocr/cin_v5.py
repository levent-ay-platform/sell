#!/usr/bin/env python3
"""Huawei-only Arabic PP-OCRv5 CIN-front OCR. Input: private tmpfs file.
Stdout: bounded JSON fields; no photos, OCR text, or tokens in logs.
MUST run inside a non-networked unprivileged memory/CPU-limited container.
"""
import json
import os
from pathlib import Path
import re
import sys

import cv2
import numpy as np
import onnxruntime as ort
import yaml
from cin_v5_vote import select_name
from cin_v5_date import date_from_v5_votes
from cin_v5_digits import decode_numeric_ctc
from cin_v5_region import crop_printed_number

MODEL_ROOT=Path("/opt/malak/models/arabic-v5")
ARABIC=re.compile(r"^[\u0621-\u064a]+(?: [\u0621-\u064a]+){0,3}$")
MAX_BYTES=6*1024*1024
MAX_EDGE=6000
OUTPUT_EMPTY={"surname":None,"givenName":None,"cinLast4":None,
              "cinFull":None,"birthDate":None,"confidencePercent":0}
cv2.setNumThreads(1)


def image_from_private_file(path):
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC)
    try:
        st=os.fstat(fd)
        if st.st_size<20 or st.st_size>MAX_BYTES:
            raise ValueError("bad image size")
        data=bytearray()
        while len(data)<st.st_size:
            block=os.read(fd,min(32768,st.st_size-len(data)))
            if not block:
                raise ValueError("truncated image")
            data.extend(block)
    finally:
        os.close(fd)
    try:
        if not (data[:3]==b"\xff\xd8\xff" or
                data[:8]==b"\x89PNG\r\n\x1a\n"):
            raise ValueError("unrecognized image")
        image=cv2.imdecode(np.frombuffer(data,dtype=np.uint8),
                           cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("image decode")
        h,w=image.shape[:2]
        if not (175<=min(w,h) and max(w,h)<=MAX_EDGE and
                w*h<=25_000_000):
            raise ValueError("image dimensions")
        return image
    finally:
        data[:]=b"\0"*len(data)


def order_quad(poly):
    pts=poly.reshape(4,2).astype(np.float32)
    sums=pts.sum(axis=1)
    differences=np.diff(pts,axis=1).ravel()
    return np.stack([pts[np.argmin(sums)],pts[np.argmin(differences)],
                     pts[np.argmax(sums)],pts[np.argmax(differences)]],axis=0)


def normalize_card(image):
    """Find the COMPLETE front card before any field crop.

    Portrait camera photos can contain the card anywhere in the viewport.
    The old arbitrary top-49%-only crop destroyed lower-centred cards and
    made OCR look broken even when the captured JPEG was usable. Never
    assume a screen/camera orientation implies card placement.
    """
    if image is None or image.size==0:
        return None
    sh,sw=image.shape[:2]
    scale=min(1.0,900/max(sh,sw))
    small=cv2.resize(image,None,fx=scale,fy=scale)
    gray=cv2.cvtColor(small,cv2.COLOR_BGR2GRAY)
    blurred=cv2.GaussianBlur(gray,(5,5),0)
    edges=cv2.Canny(blurred,35,110)
    edges=cv2.morphologyEx(edges,cv2.MORPH_CLOSE,
                           cv2.getStructuringElement(cv2.MORPH_RECT,(7,7)))
    contours,_=cv2.findContours(edges,cv2.RETR_LIST,
                                 cv2.CHAIN_APPROX_SIMPLE)
    best=None
    score=float("-inf")
    canvas_area=small.shape[0]*small.shape[1]
    for contour in contours:
        area=cv2.contourArea(contour)
        if area < canvas_area*.12:
            continue
        perimeter=cv2.arcLength(contour,True)
        for epsilon in (.014,.025,.04,.06):
            poly=cv2.approxPolyDP(contour,perimeter*epsilon,True)
            if len(poly)!=4 or not cv2.isContourConvex(poly):
                continue
            p=order_quad(poly)
            width=(np.linalg.norm(p[0]-p[1])+
                   np.linalg.norm(p[3]-p[2]))/2
            height=(np.linalg.norm(p[0]-p[3])+
                    np.linalg.norm(p[1]-p[2]))/2
            ratio=width/max(height,1)
            if not (1.30<=ratio<=2.05):
                continue
            rank=2*area/canvas_area-abs(ratio-1.585)*.4
            if rank>score:
                best=p/scale
                score=rank
    if best is None:
        ratio=image.shape[1]/image.shape[0]
        if not 1.25<=ratio<=2.05:
            return None
        return cv2.resize(image,(1200,760))
    target=np.array([[0,0],[1199,0],[1199,759],[0,759]],
                    dtype=np.float32)
    matrix=cv2.getPerspectiveTransform(best,target)
    return cv2.warpPerspective(image,matrix,(1200,760))


def find_name_rows(card):
    h,w=card.shape[:2]
    x0,x1=int(w*.66),int(w*.88)
    area=cv2.cvtColor(card[int(h*.43):int(h*.69),x0:x1],
                      cv2.COLOR_BGR2GRAY)
    median=np.median(area[::3,::4])
    threshold=np.clip(median-(40 if median>185 else 25),90,155)
    ink=cv2.cvtColor(card[:,x0:x1],cv2.COLOR_BGR2GRAY)<threshold
    density=ink.mean(axis=1)
    # Gaussian smoothing, equivalent to bounded row density in Android.
    smooth=cv2.GaussianBlur(density.reshape(-1,1).astype(np.float32),
                            (1,15),0).ravel()
    lo,hi=int(h*.43),int(h*.70)
    peaks=[]
    for y in range(lo+2,hi-2):
        if (smooth[y]<.05 or smooth[y]<smooth[y-1] or
            smooth[y]<=smooth[y+1]):
            continue
        margin=max(4,int(.025*h))
        if smooth[y]-min(smooth[max(lo,y-margin)],
                         smooth[min(hi,y+margin)])<.01:
            continue
        if peaks and y-peaks[-1]<int(.054*h):
            if smooth[y]>smooth[peaks[-1]]:
                peaks[-1]=y
        else:
            peaks.append(y)
    best=None
    score=float("-inf")
    for index,a in enumerate(peaks):
        for b in peaks[index+1:]:
            top,bottom=a/h,b/h
            gap=bottom-top
            if not (.46<=top<=.59 and .56<=bottom<=.69 and
                    .065<=gap<=.125):
                continue
            rank=smooth[a]+smooth[b]-2*abs(gap-.092)
            if rank>score:
                best=(a,b)
                score=rank
    return best


def field_crop(card,center,left_fraction=.46):
    h,w=card.shape[:2]
    left,right=int(w*left_fraction),int(w*.88)
    top=max(0,round(center-h*.050))
    bottom=min(h,round(center+h*.046))
    if right-left<25 or bottom-top<18:
        raise ValueError("missing field")
    return card[top:bottom,left:right]


def recognizer():
    config=yaml.safe_load((MODEL_ROOT/"inference.yml").read_text("utf8"))
    characters=config["PostProcess"]["character_dict"]
    if len(characters)!=747:
        raise ValueError("unverified public dictionary")
    options=ort.SessionOptions()
    options.intra_op_num_threads=2
    options.inter_op_num_threads=1
    session=ort.InferenceSession(str(MODEL_ROOT/"inference.onnx"),
                                 sess_options=options,
                                 providers=["CPUExecutionProvider"])
    return session,characters+[" "]


def recognize(session,characters,crop):
    if crop.size==0:
        return "",0.
    resized_width=max(1,min(320,int(np.ceil(48*crop.shape[1]/crop.shape[0]))))
    resized=cv2.resize(crop,(resized_width,48))
    if resized_width<320:
        resized=cv2.copyMakeBorder(resized,0,0,0,320-resized_width,
                                   cv2.BORDER_CONSTANT,value=(0,0,0))
    tensor=(resized.astype(np.float32)/255-.5)/.5
    tensor=tensor.transpose(2,0,1)[None,:,:,:]
    scores=session.run(None,{session.get_inputs()[0].name:tensor})[0][0]
    indices=np.argmax(scores,axis=1)
    visual=[]
    confidence=[]
    last=-1
    for row,idx in zip(scores,indices):
        idx=int(idx)
        if idx!=last and idx>0 and idx-1<len(characters):
            visual.append(characters[idx-1])
            confidence.append(float(row[idx]))
        last=idx
    return "".join(visual)[::-1].strip(),(
        sum(confidence)/len(confidence) if confidence else 0.)


def recognize_digits(session, characters, crop, bright_padding=False):
    """Numeric-only CTC: hold glyph alignment fixed, vary padding.

    Public-model synthetic calibration on the Mac (2026-11-13) showed that
    centering digits loses or degrades short day/year and complete CIN rows.
    Both readings keep the glyphs LEFT-aligned, but use independent dark vs
    light letterbox backgrounds and gray-vs-color preprocessing.
    No confidence-floor reduction, missing-digit completion or ID lookup.
    """
    if crop.size == 0:
        return "", 0.0
    width=max(1,min(300,int(np.ceil(48*crop.shape[1]/crop.shape[0]))))
    resized=cv2.resize(crop,(width,48),interpolation=cv2.INTER_CUBIC)
    if width<320:
        pad=(255,255,255) if bright_padding else (0,0,0)
        resized=cv2.copyMakeBorder(resized,0,0,0,320-width,
                                  cv2.BORDER_CONSTANT,value=pad)
    tensor=(resized.astype(np.float32)/255-.5)/.5
    tensor=tensor.transpose(2,0,1)[None,:,:,:]
    scores=session.run(None,{session.get_inputs()[0].name:tensor})[0][0]
    return decode_numeric_ctc(scores,characters)


def numeric_pair(session,letters,crop):
    if crop.size == 0:
        return [("",0.),("",0.)]
    gray=cv2.cvtColor(crop,cv2.COLOR_BGR2GRAY)
    gray=cv2.cvtColor(gray,cv2.COLOR_GRAY2BGR)
    return [recognize_digits(session,letters,crop,False),
            recognize_digits(session,letters,gray,True)]


def arabic_only(s):
    text=" ".join(s.split())
    if 2<=len(text)<=55 and ARABIC.fullmatch(text) and all(
            len(part)>=2 for part in text.split(" ")):
        return text
    return None


def consensus_name(session,letters,card,center):
    # Three independent label-safe horizontal crops; field text must agree
    # in at least two, never choose by model confidence alone.
    candidates=[
        recognize(session,letters,field_crop(card,center,left))
        for left in (.36,.46,.52)
    ]
    return select_name(candidates)


def _cin_full_front(session,letters,card):
    """Private in-memory complete eight-digit OCR candidate.

    Full digits are released only to the authorized submitting user through
    a short-lived private preview. They are not logged or stored and do NOT
    establish identity, ownership, or legal validity.
    """
    h,w=card.shape[:2]
    votes=set()
    tight=crop_printed_number(card,(.29,.22,.80,.48),8)
    # First OCR the isolated digit line; fallback crops remain bounded to
    # the printed front-CIN band for cards that defeat edge segmentation.
    candidates=([tight] if tight is not None else [])
    for top,bottom in ((.28,.42),(.30,.44),(.33,.46),(.26,.40)):
        for left,right in ((.33,.75),(.38,.73)):
            candidates.append(card[int(h*top):int(h*bottom),
                                   int(w*left):int(w*right)])
    for crop in candidates:
            if crop.size==0:
                continue
            first,second=numeric_pair(session,letters,crop)
            def complete(sample):
                text,quality=sample
                if quality<.72:
                    return None
                # Numeric CTC returns the same visual order as v5 text OCR.
                value=text[::-1]
                return value if re.fullmatch(r"[0-9]{8}",value) else None
            a,b=complete(first),complete(second)
            if a is not None and a==b:
                votes.add(a)
            # Never retain multiple conflicting numbers as accepted.
            if len(votes)>1:
                return None
    return next(iter(votes)) if len(votes)==1 else None


def cin_number(session,letters,card):
    full=_cin_full_front(session,letters,card)
    return full[-4:] if full is not None else None


def birth_date(session,letters,card,given_name_row):
    """Front-only date: separate day / Arabic month / year from three ROIs.

    DOB is independently evaluated on every sample, even when its CIN is
    intentionally hidden. Calendar validity and two image reads for EACH
    segment are mandatory. Never use issue, expiry or reverse-side dates.
    """
    h,w=card.shape[:2]
    dates=set()
    for offset in (.145,.175,.205):
        center=round(given_name_row+h*offset)
        if not h*.70<=center<=h*.92:
            continue
        y0=max(0,round(center-h*.046))
        y1=min(h,round(center+h*.046))
        if y1-y0<18:
            continue
        def segment(left,right):
            x0,x1=round(w*left),round(w*right)
            return card[y0:y1,x0:x1]
        # Segment printed Latin numerals before the v5 CTC recognizer.
        # If no complete group is visible, retain strict bounded ROI
        # inference as a fallback; never invent missing characters.
        date_top=max(.65,y0/h-.025)
        date_bottom=min(.96,y1/h+.025)
        day_tight=crop_printed_number(
            card,(.69,date_top,.90,date_bottom),2)
        year_tight=crop_printed_number(
            card,(.29,date_top,.60,date_bottom),4)
        day=numeric_pair(session,letters,
                         day_tight if day_tight is not None
                         else segment(.71,.87))
        year=numeric_pair(session,letters,
                          year_tight if year_tight is not None
                          else segment(.28,.54))
        month_crop=segment(.47,.76)
        gray=cv2.cvtColor(month_crop,cv2.COLOR_BGR2GRAY)
        gray=cv2.cvtColor(gray,cv2.COLOR_GRAY2BGR)
        month=[recognize(session,letters,month_crop),
               recognize(session,letters,gray)]
        value=date_from_v5_votes(day,month,year)
        if value is None:
            # On printed CINs the broad month ROI can include the date
            # label or adjacent name. Retry a narrower MIDDATE region;
            # still require exact two-view agreement and both numeric
            # votes, never fabricate a date.
            alt=segment(.50,.70)
            alt_gray=cv2.cvtColor(alt,cv2.COLOR_BGR2GRAY)
            alt_gray=cv2.cvtColor(alt_gray,cv2.COLOR_GRAY2BGR)
            month_alt=[recognize(session,letters,alt),
                       recognize(session,letters,alt_gray)]
            value=date_from_v5_votes(day,month_alt,year)
        if value is not None:
            dates.add(value)
        if len(dates)>1:
            return None
    return next(iter(dates)) if len(dates)==1 else None

def extract_fields(photo,session,letters):
    """Read preliminary OCR fields only; never verify legal ownership."""
    card=normalize_card(photo)
    if card is None:
        return dict(OUTPUT_EMPTY)
    try:
        centers=find_name_rows(card)
        if centers is None:
            return dict(OUTPUT_EMPTY)
        a,sc=consensus_name(session,letters,card,centers[0])
        b,gc=consensus_name(session,letters,card,centers[1])
        if a is None or b is None or min(sc,gc)<.72:
            return dict(OUTPUT_EMPTY)
        # Two matching reads, known Tunisian month and a real calendar date.
        # Hidden or ambiguous dates remain None. No automatic identity approval.
        try:
            dob=birth_date(session,letters,card,centers[1])
        except (ValueError,cv2.error):
            dob=None
        # Unverified candidate, exposed only via a case/QA-session scoped
        # ephemeral response. Never infer missing digits or persist the ID.
        full_cin=_cin_full_front(session,letters,card)
        return {"surname":a,"givenName":b,
                "cinLast4":full_cin[-4:] if full_cin is not None else None,
                "cinFull":full_cin,
                "birthDate":dob,
                "confidencePercent":max(0,min(100,int(min(sc,gc)*100)))}
    finally:
        # This worker stays warm across requests. Clear derived pixel buffers.
        card.fill(0)


def main():
    if len(sys.argv)==2 and sys.argv[1]=="--serve":
        from cin_v5_socket import main as socket_main
        socket_main()
        return
    # Public-model-only startup probe; safe in CI and isolated Huawei preflight.
    # No document, filename, field text or user data is used or returned.
    if len(sys.argv)==2 and sys.argv[1]=="--self-test":
        session,characters=recognizer()
        synthetic=np.full((48,320,3),255,dtype=np.uint8)
        _ignored,confidence=recognize(session,characters,synthetic)
        if not isinstance(confidence,float) or not np.isfinite(confidence):
            raise ValueError("invalid OCR inference")
        print(json.dumps({"ok":True,"engine":"arabic-v5",
                          "modelReady":True},separators=(",",":")))
        return
    if len(sys.argv)!=2 or not sys.argv[1].startswith("/"):
        raise ValueError("private input required")
    photo=image_from_private_file(sys.argv[1])
    session,letters=recognizer()
    result=extract_fields(photo,session,letters)
    print(json.dumps(result,ensure_ascii=False,separators=(",",":")))


if __name__=="__main__":
    main()
