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

## Expanded private PDF triage — 2026-11-24

This branch now performs a **high-recall, candidate-only triage** across every hashed PDF resource, without requiring a cadastral keyword. It recognizes additional hints for lotissement, bornage, topographic plans, PAU/planning, ownership certificates, sale/transfer documents, and land-title/parcel references in Arabic and French (including Tunisian `عدد` and dotted `T.F.` notation). Every hint remains **unverified**, and same-hash multi-resource associations are preserved.

**Exhaustive accounting:** hashed PDFs are split into a `review_queue` and a separate `unresolved_pdf_documents` queue. The latter retains SHA-256, source-row multiplicity, document-index presence, OCR page/text presence, and whether OCR is missing or text simply contains no recognized hints. The script asserts that these two private queues total exactly all distinct hashed PDF resources. PDF resource rows without a hash are counted separately and remain in the source catalogue. Unknown does not equal invalid or zero.

Report `schema_version=2`; these queues, candidate references and document hashes are **PRIVATE_DERIVED**, never part of `ATLAS_PUBLIC`, GitHub workflow artifacts, public APK, or browser payloads. No official status, geo-linked geometry, title/parcel match, source license, or live-DB consistency is inferred from the hints. No new Mac archive totals have been claimed based on this branch: the expanded script still needs a clean run against a previously verified private snapshot.

Output creation uses a restrictive 0600 file, restores process umask, refuses an existing target, and uses same-directory atomic link creation to avoid replacement races.

### Operator verification before trusting live coverage

1. Record the running Atlas DB owner/process, directory permissions and evidence of WAL state *without checkpoint, journal-mode migration, WAL file fabrication, or reopening the database read-write*. Do not equate a standalone copied main file with WAL-consistent data.
2. Coordinate a transactionally consistent SQLite Backup API copy with the actual process/owner, via a connection that demonstrably sees current committed state. Independently check the backup integrity, source/backup schema, counts, manifest, SHA-256 and ownership (private `0600`).
3. Run the candidate triage on that protected backup, **not against the production database**. Review the unresolved queue and PDF/source provenance manually. Confirmed official cadastral plans require authoritative documents and verified identifiers; other map types remain separate.
4. Treat any blocked or timed-out remote command as **not executed** unless a matching canonical command-result proof explicitly confirms success.

## Next gates

1. Capture a transactionally consistent owner-authorized backup, verify SHA256, integrity, counts and rights.
2. Improve candidate detection using source provenance, document layout and PDF vector/raster extraction; classify official plans based on authoritative evidence, not text search alone.
3. Build source-scoped many-to-many title/parcel links and keep every unresolved or conflicting match.
4. Validate georeferencing separately from page-outline extraction, retaining plan-space polygons until reliable coordinates or sufficient control points exist.
5. Publish only an independently reviewed, consented, rights-cleared Atlas projection. Preserve public/private separation and revoked-listing withdrawal.
6. Connect the accepted projection to the Amlak Smart Listing editor and map, and test on Android (AR/FR, offline, physical phone and backend revocation).

No automatic merge, production deployment, legacy worktree cleanup or original database modification is implied by this checkpoint.
