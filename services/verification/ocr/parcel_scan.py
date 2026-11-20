#!/usr/bin/env python3
"""Find one closed ink outline in a scanned plan. The result is page-local evidence."""
from __future__ import annotations

import argparse
from collections import deque
import json
from pathlib import Path
import re

from PIL import Image, ImageFilter, ImageOps

MAX_SCAN_PIXELS = 25_000_000
MAX_WORK_SIDE = 640
MIN_INTERIOR = 0.027
MAX_INTERIOR = 0.64
MAX_VERTICES = 64
PLAN_HEADING = re.compile(
    r"\bextrait\s+(?:du|de)\s+(?:plan|lotissement)\b|\bplan\s+(?:cadastral|parcellaire|foncier|topographique|d[eu’' ]+"
    r"(?:lotissement|bornage|parcelle|terrain|morcellement))\b|"
    r"(?:مخطط|مثال)\s+(?:ال)?(?:مسح|تقسيم|تجزئة|قطعة|عقار|أرض|هندسي|طبوغرافي)",
    re.IGNORECASE,
)

Point = tuple[int, int]
DIRECTIONS = {(1, 0):0, (0, 1):1, (-1, 0):2, (0, -1):3}


def has_parcel_plan_heading(lines: list[dict]) -> bool:
    return any(PLAN_HEADING.search(line.get('text', '')) for line in lines)


def scan_metadata_for_page(image: Image.Image, page: int, lines: list[dict]) -> dict:
    if not has_parcel_plan_heading(lines):
        return {}
    try:
        return {'parcelPlanScan': extract_parcel_scan(image,page)}
    except ValueError:
        # The raster can be too small or too large for contour work while its
        # printed OCR lines remain available for private review.
        return {}


def _collect_interior(bright: bytearray, index: int, width: int, height: int) -> tuple[list[int], bool]:
    queue = deque([index])
    visited = []
    outer = False
    bright[index] = 0
    while queue:
        point = queue.popleft()
        visited.append(point)
        y, x = divmod(point, width)
        outer = outer or x == 0 or x == width-1 or y == 0 or y == height-1
        neighbours = (
            point-1 if x else -1,
            point+1 if x+1 < width else -1,
            point-width if y else -1,
            point+width if y+1 < height else -1,
        )
        for neighbour in neighbours:
            if neighbour >= 0 and bright[neighbour]:
                bright[neighbour] = 0
                queue.append(neighbour)
    return visited, outer


def _polygon_boundary(component: list[int], width: int) -> list[Point]:
    # Collect directed exposed edges. Starting at the topmost pixel follows
    # the outer contour, leaving text and other interior holes off the ring.
    inside = set(component)
    edges: dict[Point, list[Point]] = {}
    for i in component:
        y, x = divmod(i, width)
        for adjacent, start, end in (
            (i-width, (x,y), (x+1,y)), (i+1, (x+1,y), (x+1,y+1)),
            (i+width, (x+1,y+1), (x,y+1)), (i-1, (x,y+1), (x,y)),
        ):
            if adjacent not in inside:
                edges.setdefault(start, []).append(end)
    start = min(edges, key=lambda p:(p[1],p[0]))
    ring = [start]
    current = start
    heading = 0
    for _ in range(sum(map(len, edges.values()))):
        options = edges.get(current)
        if not options:
            return []
        directions = [(DIRECTIONS[(end[0]-current[0], end[1]-current[1])],end) for end in options]
        # Keep ink on the right at diagonal contacts.
        ranking = {turn:priority for priority,turn in enumerate((1,0,3,2))}
        heading, end = min(directions, key=lambda pair:ranking[(pair[0]-heading)%4])
        ring.append(end)
        if end == start:
            return ring
        options.remove(end)
        current = end
    return []


def _distance_sq(a: Point, b: Point) -> int:
    return (a[0]-b[0])**2 + (a[1]-b[1])**2


def _segment_distance_sq(point: Point, start: Point, end: Point) -> float:
    dx, dy = end[0]-start[0], end[1]-start[1]
    if dx == 0 and dy == 0:
        return _distance_sq(point, start)
    amount = ((point[0]-start[0])*dx + (point[1]-start[1])*dy) / (dx**2+dy**2)
    t = max(0, min(1, amount))
    return (point[0]-start[0]-t*dx)**2 + (point[1]-start[1]-t*dy)**2


def _simplify(ring: list[Point], tolerance: float) -> list[Point]:
    # Ramer–Douglas–Peucker on two open paths; preserve a meaningful split
    # rather than collapsing a closed path whose endpoints are the same.
    end = len(ring)-1
    split = max(range(1,end), key=lambda i:_distance_sq(ring[0],ring[i]))
    selected = {0, split, end}
    pending = [(0,split), (split,end)]
    while pending:
        start, stop = pending.pop()
        pivot, distance = max(
            ((i,_segment_distance_sq(ring[i],ring[start],ring[stop])) for i in range(start+1,stop)),
            key=lambda pair:pair[1], default=(-1,0),
        )
        if distance > tolerance**2:
            selected.add(pivot)
            if len(selected) > MAX_VERTICES+1:
                return []
            pending.extend(((start,pivot),(pivot,stop)))
    return [ring[i] for i in sorted(selected)]


def _probe(image: Image.Image) -> tuple[str, list[Point] | None]:
    width,height = image.size
    gray = ImageOps.autocontrast(image.convert('L'), cutoff=0.1)
    # A small median filter removes dust. It does not close a large gap or
    # extrapolate an edge through an interrupted drawing.
    gray = gray.filter(ImageFilter.MedianFilter(3))
    bright = bytearray(gray.point(lambda level: 1 if level > 130 else 0).tobytes())
    page_area = width*height
    candidates = []
    for index in range(page_area):
        if not bright[index]:
            continue
        component,outer = _collect_interior(bright,index,width,height)
        area = len(component)
        if outer or not MIN_INTERIOR*page_area <= area <= MAX_INTERIOR*page_area:
            continue
        path = _polygon_boundary(component,width)
        if len(path)<4:
            continue
        poly = _simplify(path,max(2.0, max(width,height)*.006))
        if not 4 <= len(poly) <= MAX_VERTICES+1 or len(set(poly[:-1])) != len(poly)-1:
            continue
        left, right = min(p[0] for p in poly), max(p[0] for p in poly)
        top, bottom = min(p[1] for p in poly), max(p[1] for p in poly)
        if left < width*.025 or right > width*.975 or top < height*.025 or bottom > height*.975:
            continue
        if min((right-left)/width,(bottom-top)/height)<.12:
            continue
        shape_area = abs(sum(a[0]*b[1] - b[0]*a[1] for a,b in zip(poly,poly[1:])))/2
        if shape_area<=0 or not .72 <= area/shape_area <= 1.09:
            continue
        if shape_area < (right-left)*(bottom-top)*.22:
            continue
        candidates.append((area,poly))
    if not candidates:
        return 'NO_OUTLINE', None
    candidates.sort(key=lambda entry:entry[0],reverse=True)
    if len(candidates)>1 and candidates[1][0]>=candidates[0][0]*.54:
        return 'AMBIGUOUS', None
    return 'CANDIDATE', candidates[0][1]


def extract_parcel_scan(image: Image.Image, page: int) -> dict:
    """Candidate outline in this raster's pixels. The algorithm cannot locate it on Earth."""
    return _extract_display_scan(_display_image(image,page),page)


def _display_image(image: Image.Image, page: int) -> Image.Image:
    width,height = image.size
    if not 1 <= page <= 8 or min(width,height)<175 or max(width,height)>10000 or width*height>MAX_SCAN_PIXELS:
        raise ValueError('PLAN_RASTER_OUT_OF_RANGE')
    return ImageOps.exif_transpose(image)


def _extract_display_scan(image: Image.Image,page: int) -> dict:
    width,height=image.size
    work = image.convert('L')
    work.thumbnail((MAX_WORK_SIDE,MAX_WORK_SIDE),Image.Resampling.BILINEAR)
    status, ring = _probe(work)
    outline = None
    if ring:
        x_scale,y_scale = width/work.width,height/work.height
        outline = {
            'unit':'PIXEL', 'page':page,
            'ring':[[round(x*x_scale,2),round(y*y_scale,2)] for x,y in ring],
        }
    return {'page':page,'width':width,'height':height,'status':status,'outline':outline}


def extract_parcel_scan_crop(image: Image.Image, page: int, rect: tuple[int,int,int,int]) -> dict:
    """Operator-selected plan region, restoring the full page frame and crop provenance."""
    display = _display_image(image,page)
    width,height=display.size
    left,top,right,bottom=rect
    if not (0<=left<right<=width and 0<=top<bottom<=height
            and right-left>=175 and bottom-top>=175):
        raise ValueError('INVALID_PLAN_SELECTION')
    result=_extract_display_scan(display.crop(rect),page)
    result.update(width=width,height=height,selection=dict(left=left,top=top,right=right,bottom=bottom))
    if result['outline'] is not None:
        result['outline']['ring']=[[round(x+left,2),round(y+top,2)] for x,y in result['outline']['ring']]
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description='Extract a private page-local outline from a known plan raster.')
    parser.add_argument('image',type=Path)
    parser.add_argument('--page',type=int,default=1)
    parser.add_argument('--crop',nargs=4,type=int,metavar=('LEFT','TOP','RIGHT','BOTTOM'),
                        help='Operator-selected rectangle in full-page display pixels')
    args = parser.parse_args()
    Image.MAX_IMAGE_PIXELS = MAX_SCAN_PIXELS
    try:
        if args.image.is_symlink() or not args.image.is_file() or not 20 <= args.image.stat().st_size <= 12*1024*1024:
            raise ValueError('PLAN_RASTER_OUT_OF_RANGE')
        with Image.open(args.image,formats=['PNG','JPEG','TIFF']) as image:
            if getattr(image,'n_frames',1)!=1:
                raise ValueError('PLAN_RASTER_OUT_OF_RANGE')
            result = (extract_parcel_scan_crop(image,args.page,tuple(args.crop))
                      if args.crop is not None else extract_parcel_scan(image,args.page))
    except (OSError,ValueError,Image.DecompressionBombError):
        print(json.dumps({'error':'INVALID_PLAN_RASTER'}))
        return 2
    print(json.dumps(result,separators=(',',':')))
    return 0


if __name__=='__main__':
    raise SystemExit(main())
