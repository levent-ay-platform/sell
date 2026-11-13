#!/usr/bin/env python3
"""Private-worker prototype: text-first PDF / scanned-image OCR into untrusted lines.

This code never verifies ownership and does not upload or log documents. Caller must
provide authenticated access, isolate the worker and protect STDOUT (contains OCR text).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from quality import inspect_photo_quality

MAX_BYTES = 12 * 1024 * 1024
MAX_PAGES = 8
MAX_TEXT_CHARS = 100_000
MAX_PIXELS = 12_000_000
MAX_PDF_PAGE_WIDTH_PT = 16000
MAX_LINES_PER_PAGE = 600
MAX_WORD_BOXES_PER_PAGE = 1000
MAX_WORD_BOXES_PER_DOCUMENT = 2500

class InvalidDocument(ValueError):
    pass


def _cmd(args: list[str], *, timeout: int, cwd: Path | None = None) -> bytes:
    try:
        p = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           check=False, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise InvalidDocument('DOCUMENT_PROCESSING_UNAVAILABLE') from exc
    if p.returncode != 0:
        raise InvalidDocument('DOCUMENT_PROCESSING_FAILED')
    if len(p.stdout) > 4_000_000:
        raise InvalidDocument('OUTPUT_SIZE_EXCEEDED')
    return p.stdout


def _validate_input(file: Path) -> tuple[bytes, str]:
    if not file.is_file() or file.is_symlink():
        raise InvalidDocument('INVALID_FILE')
    n = file.stat().st_size
    if n < 20 or n > MAX_BYTES:
        raise InvalidDocument('INVALID_FILE_SIZE')
    with file.open('rb') as fh:
        header = fh.read(16)
    if header.startswith(b'%PDF-'):
        return header, 'pdf'
    if header.startswith(b'\x89PNG\r\n\x1a\n'):
        return header, 'image'
    if header.startswith(b'\xff\xd8\xff'):
        return header, 'image'
    if header.startswith((b'II*\x00', b'MM\x00*')):
        return header, 'image'
    raise InvalidDocument('UNSUPPORTED_DOCUMENT_TYPE')


def _pdf_page_count(path: Path) -> int:
    stdout = _cmd(['pdfinfo', str(path)], timeout=12).decode('utf-8','replace')
    m = re.search(r'^Pages:\s+(\d+)\s*$', stdout, re.MULTILINE)
    if not m:
        raise InvalidDocument('UNREADABLE_PDF_PAGE_COUNT')
    count=int(m.group(1))
    if count <= 0 or count > MAX_PAGES:
        raise InvalidDocument('PAGE_COUNT_OUT_OF_RANGE')
    return count


def _lines_from_native(page_text: str, page: int) -> list[dict]:
    return [dict(text=s[:1200],page=page,confidence=None,source='pdf-text')
            for s in page_text.splitlines() if s.strip()][:MAX_LINES_PER_PAGE]


def _has_text(page_text: str) -> bool:
    # Short native legal text is still worth extracting; avoid OCR only if useful glyphs present.
    return len(re.findall(r'[A-Za-z\u0600-\u06FF0-9]',page_text)) >= 14


def _png_size(path: Path) -> tuple[int,int]:
    # Pillow is optional in production only if we accept TIFF, but required for safe
    # decoding/dimension guard in this worker; no decompression bombs.
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = MAX_PIXELS
        with Image.open(path) as image:
            image.verify()
        with Image.open(path) as image:
            width,height=image.size
            if width < 40 or height < 40 or width * height > MAX_PIXELS:
                raise InvalidDocument('IMAGE_DIMENSIONS_OUT_OF_RANGE')
            if getattr(image,'n_frames',1) != 1:
                raise InvalidDocument('MULTIFRAME_IMAGE_UNSUPPORTED')
            return width,height
    except InvalidDocument:
        raise
    except Exception as exc:
        raise InvalidDocument('INVALID_OR_UNREADABLE_IMAGE') from exc


def _tesseract_lines(path: Path, page: int, *, lang: str = 'ara+fra',
                     psm: str = '3', with_area_anchors: bool = False):
    image_width,image_height = _png_size(path)
    # OCR data is never sent to the shell; subprocess receives argv only.
    raw = _cmd(['tesseract',str(path),'stdout','--psm',psm,'-l',lang,'tsv'],timeout=30)
    rows=raw.decode('utf-8','replace').splitlines()
    if len(rows)<2:
        return []
    header=rows[0].split('\t')
    try:
        indexes={k:header.index(k) for k in ('level','page_num','block_num','par_num','line_num','text','conf','left','top','width','height')}
    except ValueError as exc:
        raise InvalidDocument('UNEXPECTED_OCR_FORMAT') from exc
    groups: dict[tuple[int,int,int],list[tuple[str,float,int,int,int,int]]] = {}
    area_anchors: list[tuple[int,int]] = []
    # Private OCR word geometry, not a public JSON response or an ownership fact.
    spatial_words: list[dict] = []
    spatial_truncated = False
    for row in rows[1:]:
        cols=row.split('\t')
        if len(cols)<=max(indexes.values()):
            continue
        if cols[indexes['level']]!='5':
            continue
        word=cols[indexes['text']].strip()
        if not word:
            continue
        try:
            confidence=float(cols[indexes['conf']])
        except ValueError:
            continue
        if confidence < 0 or confidence > 100:
            continue
        if re.search(r'(المساح[ةه]|superficie)', word, re.I) and len(area_anchors) < 2:
            try:
                area_anchors.append((int(cols[indexes['left']]),int(cols[indexes['top']])))
            except ValueError:
                pass
        try:
            left, top = (int(cols[indexes[k]]) for k in ('left','top'))
            box_width, box_height = (int(cols[indexes[k]]) for k in ('width','height'))
            if left < 0 or top < 0 or box_width < 1 or box_height < 1:
                continue
            if left + box_width > image_width or top + box_height > image_height:
                continue
            key=tuple(int(cols[indexes[k]]) for k in ('block_num','par_num','line_num'))
        except ValueError:
            continue
        groups.setdefault(key,[]).append((word,confidence,left,top,
                                          left+box_width,top+box_height))
        if with_area_anchors:
            if len(spatial_words)<MAX_WORD_BOXES_PER_PAGE:
                spatial_words.append(dict(
                    page=page,text=word[:120],confidence=round(confidence/100,4),
                    bbox=dict(left=left,top=top,
                              right=left+box_width,bottom=top+box_height)))
            else:
                spatial_truncated=True
    lines=[]
    for words in groups.values():
        joined=' '.join(word[0] for word in words).strip()
        if joined:
            confidence=round(sum(word[1] for word in words)/len(words)/100,4)
            # Integer, original-page pixel coordinates, not arbitrary region
            # labels. Each bounding rectangle only localizes OCR evidence,
            # not document authenticity, a legal title or a verified owner.
            bounds=dict(left=min(word[2] for word in words),
                        top=min(word[3] for word in words),
                        right=max(word[4] for word in words),
                        bottom=max(word[5] for word in words))
            lines.append(dict(text=joined[:1200],page=page,confidence=confidence,
                              source='ocr-ar' if 'ara' in lang else 'ocr-fr',
                              bbox=bounds))
        if len(lines)>=MAX_LINES_PER_PAGE:
            break
    return (lines, area_anchors, spatial_words, spatial_truncated) if with_area_anchors else lines


def _focused_area_lines(path: Path, page: int, anchors: list[tuple[int,int]]) -> list[dict]:
    """Re-read only the labeled-area region; never infer missing digits.
    ROI is located by OCR word coordinates, NOT hard-coded to a sample document.
    """
    if not anchors:
        return []
    from PIL import Image, ImageEnhance
    result=[]
    with Image.open(path) as source:
        image=source.convert('RGB')
        width,height=image.size
        with tempfile.TemporaryDirectory(prefix='malak_ocr_roi_') as folder:
            for n,(x,y) in enumerate(anchors[:2]):
                rect=(max(0,x-int(width*.25)),max(0,y-int(height*.02)),
                      min(width,x+int(width*.19)),min(height,y+int(height*.035)))
                if rect[2]-rect[0]<40 or rect[3]-rect[1]<15:
                    continue
                crop=image.crop(rect)
                crop=crop.resize((crop.width*3,crop.height*3),Image.Resampling.LANCZOS)
                crop=ImageEnhance.Contrast(crop).enhance(2.0)
                destination=Path(folder)/f'roi-{n}.png'
                crop.save(destination)
                # Keep OCR evidence as a second candidate; discrepancies must
                # raise field CONFLICT, not silently overwrite the first read.
                for line in _tesseract_lines(destination,page,psm='6'):
                    if re.search(r'(المساح[ةه]|superficie)',line['text'],re.I):
                        # ROI coordinates refer to the resized crop, NOT the
                        # original page. Never return them as source bboxes.
                        line.pop('bbox',None)
                        result.append(line)
    return result


def _ocr_page(path: Path, page: int) -> list[dict]:
    first, anchors, spatial_words, spatial_truncated=_tesseract_lines(path,page,psm='3',with_area_anchors=True)
    focused=_focused_area_lines(path,page,anchors)
    # Sparse layout is a fallback only. Common Tesseract typo شهعادة is
    # incomplete legal evidence but enough to avoid re-OCR of entire page.
    has_heading=any(re.search(r'(شهاد|شهعاد|certificat)',line['text'],re.I)
                    for line in first)
    second=[] if has_heading and len(first)>=5 else _tesseract_lines(path,page,psm='11')
    seen=set()
    combined=[]
    for line in [*first,*focused,*second]:
        key=' '.join(line['text'].split())
        if key not in seen:
            seen.add(key)
            combined.append(line)
    return combined[:MAX_LINES_PER_PAGE], spatial_words, spatial_truncated


def extract_document(path: str, *, mode: str = 'auto') -> dict:
    """Produce untrusted text lines, omitting PII from metadata. Not a validator."""
    file=Path(path)
    if file.is_symlink():
        raise InvalidDocument('INVALID_FILE')
    file=file.resolve()
    _, kind = _validate_input(file)
    if mode not in ('auto','force-ocr'):
        raise InvalidDocument('INVALID_MODE')
    result={'format':kind, 'pages':[], 'ocrEngine':'tesseract', 'mayApproveOwnership':False}
    if kind=='image':
        word_lines, spatial_words, truncated=_ocr_page(file,1)
        width,height=_png_size(file)
        result['pages'].append({'page':1,'method':'ocr',
                                'pageImageWidth':width,'pageImageHeight':height,
                                'ocrWords':spatial_words,'spatialWordsTruncated':truncated,
                                'qualityWarnings':inspect_photo_quality(file)['flags'],
                                'lines':word_lines})
    else:
        count=_pdf_page_count(file)
        text=b''
        if mode=='auto':
            text=_cmd(['pdftotext','-enc','UTF-8','-layout',str(file),'-'],timeout=15)
            if len(text) > MAX_TEXT_CHARS * 4:
                raise InvalidDocument('PDF_TEXT_TOO_LARGE')
        pages = text.decode('utf-8','replace').split('\f') if text else []
        with tempfile.TemporaryDirectory(prefix='malak_ocr_') as tmp:
            work=Path(tmp)
            for index in range(1,count+1):
                page_text=pages[index-1] if len(pages)>=index else ''
                if mode=='auto' and _has_text(page_text):
                    result['pages'].append({'page':index,'method':'pdf-text',
                                            'lines':_lines_from_native(page_text,index)})
                    continue
                image=work / f'page-{index}.png'
                # DPI bounded; avoid rendering 8 pages at once and storing real docs.
                _cmd(['pdftoppm','-f',str(index),'-l',str(index),'-r','160',
                     '-singlefile','-png',str(file),str(work/f'page-{index}')],timeout=30)
                word_lines, spatial_words, truncated=_ocr_page(image,index)
                width,height=_png_size(image)
                result['pages'].append({'page':index,'method':'ocr',
                                        'pageImageWidth':width,'pageImageHeight':height,
                                        'ocrWords':spatial_words,
                                        'spatialWordsTruncated':truncated,
                                        'qualityWarnings':inspect_photo_quality(image)['flags'],
                                        'lines':word_lines})
    if sum(len(ln['text']) for pg in result['pages'] for ln in pg['lines'])>MAX_TEXT_CHARS:
        raise InvalidDocument('EXTRACTED_TEXT_TOO_LARGE')
    # Preserve OCR availability for long documents: disable spatial layout
    # rather than silently processing a partial set of owner-table words.
    word_total=sum(len(pg.get('ocrWords',[])) for pg in result['pages'])
    if word_total>MAX_WORD_BOXES_PER_DOCUMENT:
        for pg in result['pages']:
            if 'ocrWords' in pg:
                pg['ocrWords']=[]
                pg['spatialWordsTruncated']=True
    return result


def main() -> int:
    parser=argparse.ArgumentParser(description='Private synthetic OCR prototype, never authorizes ownership')
    parser.add_argument('file',help='Private file path: PDF, PNG, JPG or TIFF')
    parser.add_argument('--mode',choices=['auto','force-ocr'],default='auto')
    args=parser.parse_args()
    try:
        result=extract_document(args.file,mode=args.mode)
    except InvalidDocument as exc:
        # Safe error: no path or document contents.
        print(json.dumps({'error':str(exc),'mayApproveOwnership':False}),file=sys.stderr)
        return 2
    # Calling service MUST safeguard this stdout because it may contain PII.
    print(json.dumps(result,ensure_ascii=False,separators=(',',':')))
    return 0

if __name__=='__main__':
    raise SystemExit(main())
