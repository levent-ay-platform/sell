#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
app = root / "apps/android"
if not app.is_dir():
    raise SystemExit("Android project missing")
files = [p for p in app.rglob("*") if p.is_file() and p.suffix in (".kt", ".xml", ".kts")]
if not files:
    raise SystemExit("Android project source missing")
for file in files:
    txt = file.read_text(encoding="utf-8")
    # Reject only the legacy standalone brand, not domain vocabulary such as
    # SELLER (a valid legal actor label in the unified identity matcher).
    if re.search(r"(?<![A-Za-z0-9_])SELL(?![A-Za-z0-9_])",txt):
        raise SystemExit(f"Old customer-facing SELL branding in: {file}")
for folder in ("values", "values-fr", "values-ar"):
    strings = (app / "app/src/main/res" / folder / "strings.xml").read_text(encoding="utf-8")
    if '<string name="app_name">Amlak</string>' not in strings:
        raise SystemExit(f"Wrong Amlak app name in {folder}")
    if '<string name="pilot_login_title">' in strings:
        title = re.search(r'<string name="pilot_login_title">([^<]+)</string>', strings).group(1)
        if "Malak" in title or "ملاك" in title:
            raise SystemExit(f"Old brand in login title: {folder}")
brand = (app / "app/src/main/java/tn/malak/app/ui/Brand.kt").read_text(encoding="utf-8")
if 'APP_BRAND_NAME = "Amlak"' not in brand:
    raise SystemExit("Wrong Amlak brand constant")
print("PASS: Amlak launcher and UI brand resources for AR, FR and default")
