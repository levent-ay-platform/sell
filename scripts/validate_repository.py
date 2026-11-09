#!/usr/bin/env python3
"""Public repo governance check. Uses only the Python standard library."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
required = (
    "README.md", "SECURITY.md", "docs/PRODUCT.md", "docs/ARCHITECTURE.md",
    "docs/VERIFICATION.md", "docs/TUNISIA.md", "docs/DECISIONS/0001-foundation.md",
    "docs/ROADMAP.md", "docs/CI.md", "db/migrations/0001_foundation.sql",
    "db/tests/0001_publication_gate.sql", ".github/workflows/foundation.yml",
)
missing = [name for name in required if not (root / name).is_file()]
if missing:
    raise SystemExit(f"Missing foundation files: {missing}")

workflows = list((root / ".github/workflows").glob("*.yml")) + list(
    (root / ".github/workflows").glob("*.yaml")
)
if not workflows:
    raise SystemExit("No GitHub Actions workflow found")

for workflow in workflows:
    content = workflow.read_text(encoding="utf-8")
    if not re.search(r"(?m)^\s*runs-on:\s*ubuntu-latest\s*$", content):
        raise SystemExit(f"{workflow.name}: only hosted ubuntu-latest is permitted")
    forbidden = (
        r"self-hosted", r"runs-on:\s*macos", r"runs-on:\s*windows",
        r"pull_request_target", r"secrets\.", r"permissions:\s*write-all",
    )
    for pattern in forbidden:
        if re.search(pattern, content, flags=re.IGNORECASE):
            raise SystemExit(f"{workflow.name}: forbidden CI token/runner: {pattern}")
    if not re.search(r"(?m)^permissions:\s*\n\s*contents:\s*read\s*$", content):
        raise SystemExit(f"{workflow.name}: missing read-only workflow permissions")
    if "actions/checkout@" not in content:
        raise SystemExit(f"{workflow.name}: missing checkout")
    if re.search(r"(?m)^\\s*runs-on:\\s*(?:(?!ubuntu-latest).)+$", content):
        raise SystemExit(f"{workflow.name}: unexpected runner label")

migration = (root / "db/migrations/0001_foundation.sql").read_text(encoding="utf-8")
assert "CREATE VIEW sell.public_listing_cards" in migration
assert "CREATE FUNCTION sell.listing_items_are_verified" in migration
assert "CREATE TRIGGER enforce_listing_publication" in migration
assert "CREATE TRIGGER property_revisions_immutable" in migration
print(f"PASS: {len(required)} foundation files, {len(workflows)} hosted Ubuntu workflow(s), public source policy")
