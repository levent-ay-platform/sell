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

MAX_BYTES = 12 * 1024 * 1024
MAX_PAGES = 8
MAX_TEXT_CHARS = 100_000
MAX_PIXELS = 12_000_000
MAX_PDF_PAGE_WIDTH_PT = 16000
MAX_LINES_PER_PAGE = 600

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
                     psm: str = '3') -> list[dict]:
    _png_size(path)
    # OCR data is never sent to the shell; subprocess receives argv only.
    raw = _cmd(['tesseract',str(path),'stdout','--psm',psm,'-l',lang,'tsv'],timeout=30)
    rows=raw.decode('utf-8','replace').splitlines()
    if len(rows)<2:
        return []
    header=rows[0].split('\t')
    try:
        indexes={k:header.index(k) for k in ('level','page_num','block_num','par_num','line_num','text','conf')}
    except ValueError as exc:
        raise InvalidDocument('UNEXPECTED_OCR_FORMAT') from exc
    groups: dict[tuple[int,int,int],list[tuple[str,float]]] = {}
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
        key=tuple(int(cols[indexes[k]]) for k in ('block_num','par_num','line_num'))
        groups.setdefault(key,[]).append((word,confidence))
    lines=[]
    for words in groups.values():
        joined=' '.join(w for w,_ in words).strip()
        if joined:
            confidence=round(sum(score for _,score in words)/len(words)/100,4)
            lines.append(dict(text=joined[:1200],page=page,confidence=confidence,
                              source='ocr-ar' if 'ara' in lang else 'ocr-fr'))
        if len(lines)>=MAX_LINES_PER_PAGE:
            break
    return lines


def _ocr_page(path: Path, page: int) -> list[dict]:
    first=_tesseract_lines(path,page,psm='3')
    # Sparse-layout mode can recover the Arabic document heading suppressed by
    # standard page segmentation. Run ONLY when the first pass is incomplete.
    if any('شهاد' in line['text'] for line in first) and len(first)>=5:
        return first
    second=_tesseract_lines(path,page,psm='11')
    seen=set()
    combined=[]
    for line in [*first,*second]:
        key=' '.join(line['text'].split())
        if key not in seen:
            seen.add(key)
            combined.append(line)
    return combined[:MAX_LINES_PER_PAGE]


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
        result['pages'].append({'page':1,'method':'ocr','lines':_ocr_page(file,1)})
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
                result['pages'].append({'page':index,'method':'ocr',
                                        'lines':_ocr_page(image,index)})
    if sum(len(ln['text']) for pg in result['pages'] for ln in pg['lines'])>MAX_TEXT_CHARS:
        raise InvalidDocument('EXTRACTED_TEXT_TOO_LARGE')
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
