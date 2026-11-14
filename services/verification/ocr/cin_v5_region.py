"""Bounded geometry finder for printed Latin numeral groups on a CIN front.

Does NOT decode characters, memorize any user examples, or return identity
data. Used as an optional OCR crop hint, never as a verification signal.
"""
import cv2
import numpy as np


def find_digit_crop(card, region, expected_digits):
    """Return tighter (x0,y0,x1,y1), or None if geometry is ambiguous.

    Requires a plausible group of individually visible, similarly tall
    printed digit components in the expected area. Ink noise, masked digits,
    incomplete groups and excessive components fail closed. The OCR model
    still must independently read and agree on every character.
    """
    if expected_digits not in (2,4,8):
        raise ValueError("bad digit count")
    if not isinstance(card,np.ndarray) or card.ndim!=3:
        return None
    h,w=card.shape[:2]
    if w<320 or h<200:
        return None
    left,top,right,bottom=region
    if not (0<=left<right<=1 and 0<=top<bottom<=1):
        return None
    ox,oy=int(w*left),int(h*top)
    rx,by=int(w*right),int(h*bottom)
    crop=card[oy:by,ox:rx]
    if crop.shape[0]<30 or crop.shape[1]<40:
        return None
    gray=cv2.cvtColor(crop,cv2.COLOR_BGR2GRAY)
    blurred=cv2.GaussianBlur(gray,(3,3),0)
    mask=cv2.threshold(blurred,0,255,
                       cv2.THRESH_BINARY_INV|cv2.THRESH_OTSU)[1]
    count,_,stats,_=cv2.connectedComponentsWithStats(mask,8,cv2.CV_32S)
    if count>350:
        return None
    components=[]
    for x,y,cw,ch,area in stats[1:]:
        if (.022*h<=ch<=.13*h and .007*w<=cw<=.065*w
                and 30<=area<=.015*w*h):
            components.append((int(x),int(y),int(cw),int(ch)))
    if len(components)<expected_digits or len(components)>80:
        return None

    candidates={}
    for anchor in components:
        center=anchor[1]+anchor[3]*.5
        aligned=[v for v in components
                 if abs(v[1]+v[3]*.5-center)<=.026*h
                 and abs(v[3]-anchor[3])<=.046*h]
        aligned.sort(key=lambda v:v[0])
        for offset in range(len(aligned)-expected_digits+1):
            group=aligned[offset:offset+expected_digits]
            x0=min(v[0] for v in group)
            x1=max(v[0]+v[2] for v in group)
            y0=min(v[1] for v in group)
            y1=max(v[1]+v[3] for v in group)
            span=(x1-x0)/w
            if not (.014*expected_digits<=span<=.060*expected_digits):
                continue
            starts=[v[0] for v in group]
            gaps=[starts[i+1]-starts[i] for i in range(len(starts)-1)]
            if min(gaps)<=0:
                continue
            median=float(np.median(gaps))
            if (median<=0 or max(gaps)>2.1*median
                    or min(gaps)<.42*median):
                continue
            # A real printed number forms one compact baseline.
            vertical=max(v[1]+v[3]*.5 for v in group)-min(
                v[1]+v[3]*.5 for v in group)
            if vertical>.025*h:
                continue
            rect=(ox+x0,oy+y0,ox+x1,oy+y1)
            penalty=sum(abs(g-median) for g in gaps)/max(median,1)
            score=float(np.mean([v[3] for v in group]))-.5*penalty
            candidates[rect]=max(score,candidates.get(rect,float("-inf")))
    if not candidates:
        return None
    ranked=sorted(candidates.items(),key=lambda a:a[1],reverse=True)
    if len(ranked)>1:
        first,second=ranked[0][0],ranked[1][0]
        intersection=(max(0,min(first[2],second[2])-max(first[0],second[0]))
                     *max(0,min(first[3],second[3])-max(first[1],second[1])))
        union=((first[2]-first[0])*(first[3]-first[1])
              +(second[2]-second[0])*(second[3]-second[1])-intersection)
        if intersection/max(1,union)<.55 and ranked[1][1]>=ranked[0][1]-2:
            return None
    x0,y0,x1,y1=ranked[0][0]
    dx=max(5,round(w*.008))
    dy=max(5,round(h*.010))
    return (max(0,x0-dx),max(0,y0-dy),
            min(w,x1+dx),min(h,y1+dy))


def crop_printed_number(card, region, expected_digits):
    rect=find_digit_crop(card,region,expected_digits)
    if rect is None:
        return None
    x0,y0,x1,y1=rect
    return card[y0:y1,x0:x1]
