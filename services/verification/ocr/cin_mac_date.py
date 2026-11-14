"""Optional local Mac birth-row reader, corroborated by v5 numeric votes.

Native Arabic scores are not comparable to v5 scores. Two native image views
must read the same complete labelled date, then v5 must independently agree on
both day and year. No native-only date, fuzzy month or confidence substitution.
"""
from datetime import date
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile

from cin_v5_date import _MONTHS, _two_agree, _ascii_digits


def native_birth_dates(document,width,height,given_name_row):
    result=set()
    if not isinstance(document,dict):return result
    pages=document.get('pages',[])
    if not isinstance(pages,list) or len(pages)!=1 or not isinstance(pages[0],dict):return result
    lines=pages[0].get('lines',[])
    if not isinstance(lines,list) or len(lines)>600:return result
    for line in lines:
        if not isinstance(line,dict):continue
        text=line.get('text','');score=line.get('confidence');box=line.get('bbox',{})
        if not isinstance(text,str) or len(text)>180 or isinstance(score,bool) or not isinstance(score,(int,float)) or not math.isfinite(score) or not .5<=score<=1:continue
        if not isinstance(box,dict) or not all(type(box.get(k)) is int for k in ('left','top','right','bottom')):continue
        center=(box['top']+box['bottom'])/2
        if not (0<=box['left']<box['right']<=width and
                height*.67<=box['top']<box['bottom']<=height*.94 and
                height*.10<=center-given_name_row<=height*.27):continue
        normalized=(text.translate(str.maketrans('٠١٢٣٤٥٦٧٨٩','0123456789'))
                    .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ـ',''))
        # Explicit birth label + location exclude issue/expiry dates elsewhere.
        if not re.search(r'(?:الولادة|الميلاد|\bولد\b|\bولدت\b|\bمولود\b)',normalized):continue
        if re.search(r'اصدار|اصدرت|انتهاء|صلاحية',normalized):continue
        tokens=re.findall(r'[\u0621-\u064a]+',normalized)
        months=[_MONTHS[token] for token in tokens if token in _MONTHS]
        groups=re.findall(r'[0-9]+',normalized)
        days=[g for g in groups if len(g)==2];years=[g for g in groups if len(g)==4]
        if len(months)!=1 or len(groups)!=2 or len(days)!=1 or len(years)!=1:continue
        try:
            birth=date(int(years[0]),months[0],int(days[0]))
            if 1900<=birth.year<=2100:result.add(birth.isoformat())
        except ValueError:pass
    return result


def corroborated_birth_date(numeric_votes,native_views,*,today=None):
    if len(native_views)!=2 or any(len(view)!=1 for view in native_views):return None
    if native_views[0]!=native_views[1]:return None
    value=next(iter(native_views[0]))
    try:birth=date.fromisoformat(value)
    except (TypeError,ValueError):return None
    if birth>(today or date.today()) or birth.year<1900:return None
    days=set();years=set()
    for day,year in numeric_votes:
        # A conflicting component must not disappear because its neighbour
        # is blank. Collect every confident decode, then require a full pair
        # for each component as positive corroboration.
        for samples,width,into in ((day,2,days),(year,4,years)):
            for sample in samples:
                decoded=_ascii_digits(sample,width)
                if decoded is not None:into.add(int(decoded))
    day_agrees=any(_two_agree(d,lambda sample:_ascii_digits(sample,2)) is not None
                   for d,_ in numeric_votes)
    year_agrees=any(_two_agree(y,lambda sample:_ascii_digits(sample,4)) is not None
                   for _,y in numeric_votes)
    return value if day_agrees and year_agrees and days=={birth.day} and years=={birth.year} else None


def guided_year_votes(document,card,given_name_row,candidate,numeric_reader):
    """Use native geometry only; v5 still independently decodes the pixels."""
    h,w=card.shape[:2]
    year=str(date.fromisoformat(candidate).year)
    pages=document.get('pages',[])
    if len(pages)!=1:return []
    boxes=[]
    for word in pages[0].get('ocrWords',[]):
        if word.get('text')!=year:continue
        box=word.get('bbox',{})
        if not all(type(box.get(k)) is int for k in ('left','top','right','bottom')):continue
        l,t,r,b=(box[k] for k in ('left','top','right','bottom'))
        if not (.25*w<=l<r<=.65*w and .035*w<=r-l<=.35*w and
                .67*h<=t<b<=.94*h and 12<=b-t<=.22*h and
                .10*h<=(t+b)/2-given_name_row<=.27*h):continue
        boxes.append((max(0,l-8),max(0,t-7),min(w,r+8),min(h,b+7)))
    if len(boxes)!=1:return []
    l,t,r,b=boxes[0]
    return [([],numeric_reader(card[t:b,l:r]))]


def read_mac_birth_date(card,given_name_row,numeric_votes,*,numeric_reader=None):
    executable=os.environ.get('MALAK_VISION_BIN')
    if not executable or not Path(executable).is_file():return None
    import cv2
    h,w=card.shape[:2]
    gray=cv2.cvtColor(cv2.cvtColor(card,cv2.COLOR_BGR2GRAY),cv2.COLOR_GRAY2BGR)
    try:
        views=[];documents=[]
        with tempfile.TemporaryDirectory(prefix='malak-birth-row-') as directory:
            os.chmod(directory,0o700)
            path=Path(directory)/'front.png'
            for image in (card,gray):
                # Reserve privately before writing source pixels to disk.
                path.touch(mode=0o600,exist_ok=True)
                if not cv2.imwrite(str(path),image):return None
                result=subprocess.run([executable,str(path)],stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,timeout=10,check=True)
                if len(result.stdout)>4_000_000:return None
                document=json.loads(result.stdout);documents.append(document)
                views.append(native_birth_dates(document,w,h,given_name_row))
        value=corroborated_birth_date(numeric_votes,views)
        if value is not None:return value
        if numeric_reader is None or len(views)!=2 or len(views[0])!=1 or views[0]!=views[1]:return None
        candidate=next(iter(views[0]))
        extra=[]
        for document in documents:
            extra.extend(guided_year_votes(document,card,given_name_row,candidate,numeric_reader))
        return corroborated_birth_date(numeric_votes+extra,views)
    except (OSError,ValueError,TypeError,KeyError,subprocess.SubprocessError):return None
    finally:gray.fill(0)
