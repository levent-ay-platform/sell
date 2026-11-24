#!/usr/bin/env python3
"""Private offline page-window evidence extraction for large archive PDFs.

Separate from the owner-document worker. No raw text persisted; no legal claims.
Use only on an approved local PDF in an isolated processing environment.
"""
from __future__ import annotations
import argparse
from collections import Counter
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import unicodedata
from scripts.atlas_cadastre_evidence_audit import (
    DIGITS, CADASTRAL_WORDS, FAMILY_PATTERNS, write_private_json,
)
from scripts.atlas_pdf_blob_inventory import _hash_file

MAX_BYTES = 300 * 1024**2
MAX_PAGE_WINDOW = 20
MAX_TEXT_BYTES = 2 * 1024**2
TITLE = re.compile(
    r"(?:\btitres?\s+fonciers?\b(?:\s+m[èe]re)?|\bT\s*\.?\s*F\s*\.?|"
    r"الرسم\s+العقاري|رسم\s+عقاري)"
    r"\s*(?:n[°ºo.]?\s*|num[ée]ro\s*|رقم\s*|عدد\s*)?[:#\s-]*"
    r"([0-9]{3,12})(?![0-9])(?:\s*[/\\]\s*([A-Za-z0-9\u0600-\u06ff-]{1,32}))?",
    re.I)
PARCEL = re.compile(
    r"(?:\bparcelle\b|\blot\b|المقسم|القطعة)"
    r"\s*(?:n[°ºo.]?\s*|num[ée]ro\s*|رقم\s*|عدد\s*)?[:#\s-]*"
    r"([0-9]{1,12})(?![0-9])(?:\s*[/\\]\s*([A-Za-z0-9\u0600-\u06ff-]{1,32}))?",
    re.I)
AREA = re.compile(
    r"(?:superficie|surface|contenance|المساحة)\s*[:=]?\s*"
    r"([0-9]{1,12}(?:[.,][0-9]{1,4})?)\s*(m2|m²|ha|م2|م²|هكتار)(?!\w)", re.I)


def extract_candidates(text):
    # Preserve reference zeros and suffixes. Never join arbitrary OCR lines.
    # Native and raster passes remain independent observations.
    refs, parcels, areas = set(), set(), set()
    for line in text.splitlines():
        line = unicodedata.normalize("NFKC", line).translate(DIGITS)
        line = re.sub(r"[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]", "", line)
        for pattern, dest in ((TITLE, refs), (PARCEL, parcels)):
            for m in pattern.finditer(line):
                dest.add((m[1], m[2] or ""))
        for m in AREA.finditer(line):
            # Decimal separator only; grouping/ambiguous formats are not inferred.
            if m[1].count(".") + m[1].count(",") and len(re.split("[.,]", m[1])[-1]) == 3:
                continue
            v = Decimal(m[1].replace(",", "."))
            if v > 0:
                areas.add((str(v), m[2].lower()))
    return {
        "title_candidates": [{"number": n, "suffix": s or None} for n,s in sorted(refs)],
        "parcel_candidates": [{"number": n, "suffix": s or None} for n,s in sorted(parcels)],
        "area_candidates": [{"value": n, "unit": u} for n,u in sorted(areas)],
        "cadastral_term_present": bool(CADASTRAL_WORDS.search(text)),
        "family_hints": sorted(k for k,p in FAMILY_PATTERNS.items() if p.search(text)),
        "text_present": bool(text.strip()),
    }


def _command(args, work, timeout=45):
    # Files are private and bounded after execution; process group is stopped on timeout.
    out = work / "child.stdout"
    try:
        with out.open("wb") as f:
            p = subprocess.Popen(args, stdout=f, stderr=subprocess.DEVNULL,
                                 start_new_session=True, cwd=work)
            try:
                p.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(p.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                p.wait()
                return None, "TIMEOUT"
        if p.returncode:
            return None, "COMMAND_FAILED"
        if out.stat().st_size > MAX_TEXT_BYTES:
            return None, "OUTPUT_LIMIT"
        return out.read_text(encoding="utf-8", errors="replace"), "COMPLETED"
    except OSError:
        return None, "COMMAND_UNAVAILABLE"
    finally:
        out.unlink(missing_ok=True)


def review_pdf(source, expected_sha, output, *, start_page=1, max_pages=20, raster=False):
    source, output = Path(source), Path(output)
    if not re.fullmatch("[a-f0-9]{64}", expected_sha):
        raise ValueError("EXPECTED_SHA256_REQUIRED")
    if not 1 <= start_page or not 1 <= max_pages <= MAX_PAGE_WINDOW:
        raise ValueError("BOUNDED_PAGE_WINDOW_REQUIRED")
    if source.is_symlink() or not source.is_file() or output.exists() or output.is_symlink():
        raise ValueError("REGULAR_SOURCE_AND_NEW_OUTPUT_REQUIRED")
    if any(p.is_symlink() for p in output.parents):
        raise ValueError("SYMLINK_OUTPUT_DIRECTORY")
    if not all(shutil.which(tool) for tool in
               (["pdfinfo", "pdftotext", "pdftoppm", "tesseract"] if raster else ["pdfinfo", "pdftotext"])):
        raise ValueError("REQUIRED_TOOLS_MISSING")
    if not output.parent.is_dir():
        raise ValueError("EXISTING_PRIVATE_OUTPUT_PARENT_REQUIRED")
    if output.parent.stat().st_mode & 0o077:
        raise ValueError("PRIVATE_OUTPUT_PARENT_REQUIRED")
    source = source.resolve(strict=True)
    output = output.parent.resolve(strict=True) / output.name
    old = os.umask(0o077)
    try:
        with tempfile.TemporaryDirectory(prefix="deep-review-", dir=output.parent) as temp:
            work = Path(temp)
            staged = work / "document.pdf"
            # Copy only within the explicit size ceiling, hash the exact staged bytes.
            total = 0
            with source.open("rb") as src, staged.open("xb") as dst:
                while chunk := src.read(1024*1024):
                    total += len(chunk)
                    if total > MAX_BYTES:
                        raise ValueError("ARCHIVE_PDF_SIZE_LIMIT")
                    dst.write(chunk)
            proof = _hash_file(staged, maximum=MAX_BYTES)
            if proof.get("sha256_private") != expected_sha or not proof.get("pdf_header_hint"):
                raise ValueError("PDF_SHA_OR_HEADER_MISMATCH")
            info, state = _command(["pdfinfo", str(staged)], work)
            match = re.search(r"^Pages:\s+([0-9]+)\s*$", info or "", re.M)
            if not match:
                raise ValueError("PDF_PAGE_COUNT_UNAVAILABLE_" + state)
            count = int(match[1])
            if not 1 <= start_page <= count:
                raise ValueError("PAGE_START_OUT_OF_RANGE")
            end = min(count, start_page + max_pages - 1)
            pages = []
            for page in range(start_page, end + 1):
                native, status = _command(["pdftotext", "-f", str(page), "-l", str(page),
                    "-layout", "-enc", "UTF-8", str(staged), "-"], work)
                observations = [{"method": "native_layout", "status": status,
                                 "evidence": extract_candidates(native) if native is not None else None}]
                if raster:
                    prefix = work / "render"
                    image = prefix.with_suffix(".png")
                    _, status = _command(["pdftoppm", "-f", str(page), "-l", str(page),
                        "-scale-to", "2400", "-singlefile", "-png", str(staged), str(prefix)], work)
                    if status == "COMPLETED" and image.is_file():
                        ocr, status = _command(["tesseract", str(image), "stdout", "-l", "ara+fra",
                                                "--psm", "11"], work)
                    else:
                        ocr = None
                        status = "RENDER_FAILED_" + status
                    image.unlink(missing_ok=True)
                    observations.append({"method": "raster_sparse_ocr", "status": status,
                                         "evidence": extract_candidates(ocr) if ocr is not None else None})
                pages.append({"page": page, "observations": observations})
            result = {"kind": "PRIVATE_ARCHIVE_DEEP_REVIEW", "schema_version": 1,
                      "document_sha256_private": expected_sha, "document_bytes": total,
                      "document_page_count": count, "legacy_size_limit_exceeded": total > 12*1024**2,
                      "legacy_page_limit_exceeded": count > 8, "page_start": start_page,
                      "page_end": end, "next_page": end+1 if end < count else None,
                      "pages_private": pages, "official_plan_verified": None,
                      "ground_verified": False, "public_export_permitted": False}
            write_private_json(output, result)
            return result
    finally:
        os.umask(old)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--pdf", type=Path, required=True)
    p.add_argument("--expected-sha256", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--start-page", type=int, default=1)
    p.add_argument("--max-pages", type=int, default=20)
    p.add_argument("--raster", action="store_true")
    a = p.parse_args()
    r = review_pdf(a.pdf, a.expected_sha256, a.output, start_page=a.start_page,
                   max_pages=a.max_pages, raster=a.raster)
    stats = Counter(o["status"] for page in r["pages_private"] for o in page["observations"])
    print(json.dumps({"private_review_written": True, "document_page_count": r["document_page_count"],
                      "next_page": r["next_page"], "observation_states": stats,
                      "official_plan_verified": "UNKNOWN"}))


if __name__ == "__main__":
    main()
