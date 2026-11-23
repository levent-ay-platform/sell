# Atlas Cadastre Evidence Audit — implementation checkpoint

Date: 2026-11-24. This is an implementation note under [ATLAS MASTER Issue #20](https://github.com/levent-ay-platform/sell/issues/20), not a competing plan.

## Candidate-only reader

The script at scripts/atlas_cadastre_evidence_audit.py reads an SQLite snapshot with immutable read-only access. It preserves total resource counts, duplicate source-resource links to the same PDF digest, unknown or missing hashes, and leading zeros in candidate title/parcel references. It scans French/Arabic document terms and writes a private JSON report with 0600 permissions, excluding raw URLs and raw OCR text. It refuses to overwrite existing reports.

No keyword, PDF row, matching number, or planning polygon can be promoted to verified official cadastre, actual land-title ownership, georeferenced boundaries or publishing rights.

## Operator commands

Run on a transactionally consistent, independently verified private SQLite backup:

    python3 scripts/atlas_cadastre_evidence_audit.py --db /private/operator-snapshot/catalogue.sqlite --snapshot-kind transactional_backup --output /private/operator-reports/cadastre-candidates.json

For a main-file-only diagnostic, use --snapshot-kind mainfile_diagnostic. This explicitly does not establish live consistency; SQLite WAL state and file ownership must be checked separately.

Run synthetic contract tests:

    python3 -m unittest discover -s scripts/tests -p test_atlas_cadastre_evidence_audit.py -v

## Mac diagnostic checkpoint

Canonical LEVENT-MAC via AI Agent CTR responded. Historical Atlas/Amlak worktrees were left intact, including modified and untracked files. Active catalogue's normal read-only SQL access failed, while immutable main-file diagnostics returned quick_check=ok. Live transactional backup is still an outstanding gate.

Main-file-only readings: 3,149 resource rows, 3,097 PDF resource rows, 822 indexed document rows, 21,831 indexed page rows, 845 resource rows linked to indexed document SHA, and 1,671 polygon records. The new candidate reader found 9 unique document hashes with cadastral keywords. None of these figures is a verified official plan count, a legal title match or a mapped parcel count.

Private full-file SHA256, raw evidence and candidate details remain on the Mac, not in GitHub or the public Android build.

## Next gates

1. Capture a transactionally consistent owner-authorized backup, verify SHA256, integrity, counts and rights.
2. Improve candidate detection using source provenance, document layout and PDF vector/raster extraction; classify official plans based on authoritative evidence, not text search alone.
3. Build source-scoped many-to-many title/parcel links and keep every unresolved or conflicting match.
4. Validate georeferencing separately from page-outline extraction, retaining plan-space polygons until reliable coordinates or sufficient control points exist.
5. Publish only an independently reviewed, consented, rights-cleared Atlas projection. Preserve public/private separation and revoked-listing withdrawal.
6. Connect the accepted projection to the Amlak Smart Listing editor and map, and test on Android (AR/FR, offline, physical phone and backend revocation).

No automatic merge, production deployment, legacy worktree cleanup or original database modification is implied by this checkpoint.
