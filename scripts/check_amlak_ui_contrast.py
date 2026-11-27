#!/usr/bin/env python3
"""Amlak Android typography / high-contrast static QA gate.

Tests the product palette for WCAG AA normal-sized text and prevents reintroduction
of sub-12sp text across the native UI. This is source-level protection; physical
device screenshots and TalkBack remain required before visual acceptance.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "apps/android/app/src/main/java/tn/malak/app/ui"
brand = (UI / "Brand.kt").read_text(encoding="utf-8")
theme = (UI / "AmlakTheme.kt").read_text(encoding="utf-8")

def color_token(token: str) -> str:
    match = re.search(rf"val {token}\s*=\s*Color\(0xFF([0-9A-Fa-f]{{6}})\)", brand)
    if not match:
        raise SystemExit(f"Missing Amlak color token: {token}")
    return "#" + match.group(1)

def channel(value: float) -> float:
    return value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4

def luminance(hex_color: str) -> float:
    rgb = [int(hex_color[i : i + 2], 16) / 255 for i in (1, 3, 5)]
    return sum(a * channel(v) for a, v in zip((0.2126, 0.7152, 0.0722), rgb))

def contrast(a: str, b: str) -> float:
    left, right = sorted((luminance(a), luminance(b)), reverse=True)
    return (left + 0.05) / (right + 0.05)

pearl, rose, crimson = (color_token(token) for token in ("Pearl", "Rose", "Crimson"))
pairs = (
    ("primary label on pearl", crimson, pearl),
    ("primary label on rose", crimson, rose),
    ("white text on primary button", "#FFFFFF", crimson),
    ("body text on pearl", color_token("Ink"), pearl),
    ("secondary text on pearl", color_token("Muted"), pearl),
    ("secondary text on white card", color_token("Muted"), "#FFFFFF"),
    ("links on pearl", color_token("Azure"), pearl),
    ("review approval on pearl", color_token("TrustGreen"), pearl),
    ("error label on pearl", "#B3261E", pearl),
    ("seller review amber on white", "#84571E", "#FFFFFF"),
)
errors = []
for label, foreground, background in pairs:
    ratio = contrast(foreground, background)
    if ratio < 4.5:
        errors.append(f"{label}: {ratio:.2f}:1 below WCAG AA 4.5:1")
    print(f"CONTRAST {label}: {ratio:.2f}:1")

for required in (
    "onPrimary =", "onPrimaryContainer =", "onSecondary =", "onTertiary =",
    "onBackground =", "onSurface =", "onSurfaceVariant =", "onError =",
    "outline =", "inverseOnSurface =",
):
    if required not in theme:
        errors.append(f"Missing Material3 readable role: {required}")

screen_count, text_sp_count = 0, 0
for source in UI.rglob("*.kt"):
    if source.name.endswith("Test.kt"):
        continue
    screen_count += 1
    text = source.read_text(encoding="utf-8")
    for match in re.finditer(r"fontSize\s*=\s*(\d+(?:\.\d+)?)\.sp", text):
        text_sp_count += 1
        if float(match.group(1)) < 12:
            line = text.count("\n", 0, match.start()) + 1
            errors.append(f"Sub-12sp text in {source.relative_to(UI)}:{line}: {match.group(1)}sp")

for folder in ("values", "values-ar", "values-fr"):
    text = (ROOT / "apps/android/app/src/main/res" / folder / "strings.xml").read_text(encoding="utf-8")
    if '<string name="app_name">Amlak</string>' not in text:
        errors.append(f"Amlak launcher name missing from {folder}")
if 'APP_BRAND_NAME = "Amlak"' not in brand:
    errors.append("Amlak top masthead missing")

print(f"UI_AUDIT_SCANNED {screen_count} Kotlin files; {text_sp_count} explicit text sizes")
if errors:
    raise SystemExit("AMLAK_UI_AUDIT_FAILED:\n" + "\n".join(errors))
print("AMLAK_UI_AUDIT_PASS")
