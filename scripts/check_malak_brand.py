#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
app = root / "apps/android"
if not app.is_dir():
    raise SystemExit("Android project missing")
files = [p for p in app.rglob("*") if p.is_file() and p.suffix in (".kt", ".xml", ".kts")]
if not files:
    raise SystemExit("Android project source missing")
for file in files:
    txt = file.read_text(encoding="utf-8")
    if "SELL" in txt:
        raise SystemExit(f"Old customer-facing SELL branding in: {file}")
if "<string name=\"app_name\">ملاك</string>" not in (app/"app/src/main/res/values/strings.xml").read_text():
    raise SystemExit("Wrong display app name")
if "APP_BRAND_ARABIC = \"ملاك\"" not in (app/"app/src/main/java/tn/malak/app/ui/Brand.kt").read_text():
    raise SystemExit("Wrong brand constant")
print("PASS: Android app name and customer-facing code locked to ملاك")
