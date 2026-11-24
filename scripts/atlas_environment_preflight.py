"""Atlas environment readiness; does not open archives, install or modify files."""
from __future__ import annotations

import argparse
import importlib
import json
import shutil
import subprocess
import sys


def _import_ok(name):
    try:
        importlib.import_module(name)
        return True
    except Exception:
        return False


def _languages():
    if not shutil.which("tesseract"):
        return set()
    try:
        result = subprocess.run(["tesseract", "--list-langs"], capture_output=True,
                                text=True, timeout=10, check=True)
        return set(result.stdout.splitlines())
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return set()


def readiness(*, ocr=False):
    checks = {
        "python_at_least_3_11": sys.version_info >= (3, 11),
        "pypdf": _import_ok("pypdf"),
    }
    if ocr:
        checks["pillow"] = _import_ok("PIL.Image")
        for command in ("pdfinfo", "pdftotext", "pdftoppm", "tesseract"):
            checks[command] = bool(shutil.which(command))
        langs = _languages()
        checks["ocr_arabic"] = "ara" in langs
        checks["ocr_french"] = "fra" in langs
    return {"mode": "vector_and_ocr" if ocr else "vector",
            "python_version": ".".join(map(str, sys.version_info[:3])),
            "checks": checks, "ready": all(checks.values()),
            "missing": [key for key, ok in checks.items() if not ok]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ocr", action="store_true")
    args = parser.parse_args()
    result = readiness(ocr=args.ocr)
    print(json.dumps(result, sort_keys=True))
    return 0 if result["ready"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
