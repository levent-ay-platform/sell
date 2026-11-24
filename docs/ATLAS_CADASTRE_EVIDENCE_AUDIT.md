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

Report `schema_version=3`; each hashed PDF also retains the **private** resource-row identifiers that point to it (without deduplication), and reference evidence is recorded per OCR page; an OCR hit with missing page number is retained instead of dropped. Resources with no SHA-256 retain private identifiers for remediation. These queues, candidate references and document hashes are **PRIVATE_DERIVED**, never part of `ATLAS_PUBLIC`, GitHub workflow artifacts, public APK, or browser payloads. No official status, geo-linked geometry, title/parcel match, source license, or live-DB consistency is inferred from the hints. No new Mac archive totals have been claimed based on this branch: the expanded script still needs a clean run against a previously verified private snapshot.

Output creation uses a restrictive 0600 file, restores process umask, refuses an existing target, and uses same-directory atomic link creation to avoid replacement races.

### Operator verification before trusting live coverage

1. Record the running Atlas DB owner/process, directory permissions and evidence of WAL state *without checkpoint, journal-mode migration, WAL file fabrication, or reopening the database read-write*. Do not equate a standalone copied main file with WAL-consistent data.
2. Coordinate a transactionally consistent SQLite Backup API copy with the actual process/owner, via a connection that demonstrably sees current committed state. Independently check the backup integrity, source/backup schema, counts, manifest, SHA-256 and ownership (private `0600`).
3. Run the candidate triage on that protected backup, **not against the production database**. Review the unresolved queue and PDF/source provenance manually. Confirmed official cadastral plans require authoritative documents and verified identifiers; other map types remain separate.
4. Treat any blocked or timed-out remote command as **not executed** unless a matching canonical command-result proof explicitly confirms success.

## P0–P4 operator pipeline (implementation verified on synthetic CI)

The Atlas repo now includes standalone, private, non-destructive entrypoints. Invoke with `python3 -m` from the repository root. **Never pass raw DBs, names, title numbers, OCR or source URLs into CI artifacts, log output, public GitHub, static Android assets, or the web.** All arguments below denote operator-controlled private paths; do not substitute live source paths except for the authorized backup step.

```sh
# Only under the real Atlas DB owner's authorization / file-owner permissions.
# If the live DB uses WAL but lacks its companion files, this fails closed.
python3 -m scripts.atlas_sqlite_snapshot \
  --source /private/atlas-live/catalogue.sqlite \
  --target /private/atlas-snapshots/owner-consistent.sqlite \
  --manifest /private/atlas-snapshots/owner-consistent-manifest.json

python3 -m scripts.atlas_cadastre_evidence_audit \
  --db /private/atlas-snapshots/owner-consistent.sqlite \
  --snapshot-kind transactional_backup \
  --output /private/atlas-reports/private-evidence.json

# Scope annotations and other-source records must have already been
# curated privately; source identity requires namespace/service/layer/OID/version.
python3 -m scripts.atlas_document_linkage \
  --audit /private/atlas-reports/private-evidence.json \
  --scope-annotations /private/atlas-review/scope-annotations.json \
  --source-records /private/atlas-review/source-records.json \
  --output /private/atlas-reports/link-candidates.json

# Operator-supplied page outline + at least 3 fit GCPs, 1 held-out GCP.
python3 -m scripts.atlas_geometry_evidence \
  --input /private/atlas-review/plan-and-controls.json \
  --output /private/atlas-reports/geometry-qa.json

# REQUIRED HUMAN/OWNER-MANAGER REVIEW GATE:
# verify official source, physical title/parcel links, 4326 geography,
# independent control points, licensing, consent and revocations.
# Generate separately approved private reviewed-records.json only after this.
# ATLAS_PUBLIC_HMAC_KEY is a strong secret managed outside the repo.
python3 -m scripts.atlas_public_projection \
  --reviews /private/atlas-review/reviewed-records.json \
  --revocations /private/atlas-review/current-complete-revocations.json \
  --output /private/atlas-release/public-stage.geojson

python3 -m scripts.atlas_amlak_private_bridge \
  --reviews /private/atlas-review/reviewed-records.json \
  --revocations /private/atlas-review/current-complete-revocations.json \
  --output /private/atlas-release/amlak-private-import.json
```

The `atlas_sqlite_snapshot` command uses the actual SQLite Backup API through standard `mode=ro`, **without `immutable=1`**. It fails if the WAL-oriented source is missing `-wal` or `-shm`, so operator coordination is needed rather than forcibly creating sidecars, checkpointing or repairing the operational archive. A backup includes private SHA-256, integrity and row-count verification; no source database mutation is authorized. This has been tested only with synthetic source databases, **not yet executed against the live Atlas WAL database**.

**Source-scoped linkage:** `atlas_document_linkage` preserves unknown/unlinked docs, composite source keys, ambiguous candidates and leading zeros. It requires the title and parcel to appear on the same page and an explicitly reviewed registry scope for candidate matches; it never marks a title or legal owner verified. Where records lack a reviewer-verified scope, no candidate edge is created.

**Geometry:** `atlas_geometry_evidence` only accepts explicit PIXEL/PAGE_POINT coordinates, three or more FIT control points, independent CHECK points, Tunisia WGS84 bounds and a maximum holdout error; it also checks ring topology and printed-area disagreement. A passing result is **GEOREFERENCED_CANDIDATE**, never `GROUND_VERIFIED`. There is no CRS inference or fictitious title-to-location transform.

**Release:** `atlas_public_projection` uses an explicit deny-by-default field allowlist and a private HMAC public identifier; only reviewer-verified official plans, independently reviewed ground boundaries, rights-cleared consented active listings, and a complete current revocation manifest can enter the staged public GeoJSON. `atlas_amlak_private_bridge` generates a confidential batch conforming to `ReviewedAtlasParcel` accepted by the existing `services/verification/src/atlas-parcel-exchange.ts`. It does **not** send the batch or modify Amlak/PostGIS. Publication/runtime integration still requires authenticated admin review and live read-time revocation enforcement.

**Current proof:** The CI workflow executes synthetic audit, document linkage, independent GCP QA, safe release, private Amlak bridge and a full offline CLI smoke that tests removal on revocation. Passing these tests proves the software gates, not actual official-plan provenance, archive-wide GIS coverage, device-level Android UX, or live source consistency. No source archive files were committed or exposed.

## Live Mac metadata-only decision — 2026-11-24

**Actual canonical LEVENT-MAC reads succeeded** through AI Agent CTR for read-only metadata and aggregate SQL. This is not based on synthetic CI. The operational `/Users/levent/Projects/tunisia-atlas/data/control/catalogue.sqlite` exists and currently has **208,605,184 bytes**; SQLite header byte pair `[2,2]` indicates WAL-format and **neither `-wal` nor `-shm` was present** during the preflight. Do not open/rewrite/repair that live database or claim it has a consistent Backup API copy yet. All figures below come from a separate **main-file-only private diagnostic** and may not reflect newer transactions.

| Scope | Main-file-only observation |
| --- | ---: |
| PDF resource rows | 3,097 |
| Distinct hashed PDFs | 2,829 |
| PDF resource rows without SHA | 2 |
| Indexed document rows | 822 |
| Page rows | 21,831 |
| Page rows with nonblank text | 13,481 |
| Pages without usable text | 8,350 |
| PDFs with nonblank indexed text | 635 |
| Indexed documents `ocr_pending` | 207 |
| Indexed documents `mixed_ocr_pending` | 101 |
| Indexed documents `processing_failed` | 2 |

Source-associated PDF digests (overlaps across different sources must not be summed as globally unique): OTC **137**, GeoPAU **764**, AFH **276**, OTHER **1,658**. Their missing document-index associations: OTC **79**, GeoPAU **0**, AFH **275**, OTHER **1,655**. Their `ocr_pending` or `mixed_ocr_pending` associations: OTC **48**, GeoPAU **260**, AFH **1**, OTHER **1**. In the OCR text index, two OTC-origin document hashes contained the word `cadastre`; **this is a keyword observation, not an official plan or title/parcel proof**. The historical initial nine cadastre-keyword document candidates also remain unverified.

**Priority shift:** verify actual blob existence, signature and SHA before OCR; index the unindexed PDF resources by original hash without destructive deduplication; run bounded OCR for real missing-text pages; review the source/issuer provenance of the two OTC text hints and all Nabeul/Kélibia priority results; only then certify any particular `Plan de cadastre`. Do not automatically classify an AFH subdivision or GeoPAU planning PDF as official cadastral geometry.

The new `scripts/atlas_ocr_backlog.py` stages a **private, metadata-only, source-aware queue** with `VERIFY_BLOB_AND_INDEX`, `OCR_REQUIRED_AFTER_BLOB_CHECK`, `REVIEW_INDEX_FAILURE` and `TEXT_AVAILABLE_REVIEW`. Each SHA retains all source-resource pointers; filename/locality hints affect ordering only. The tool does not open document blobs and does not assert an official plan, nor does it modify the live catalogue. Synthetic tests are in `scripts/tests/test_atlas_ocr_backlog.py`.

Run only against a previously verified private stable database copy, never the live SQLite path:

```sh
python3 -m scripts.atlas_ocr_backlog \
  --db /private/atlas-snapshots/owner-consistent.sqlite \
  --snapshot-kind transactional_backup \
  --output /private/atlas-reports/ocr-backlog-private.json
```

If the only available artifact is the historical mainfile-only copy, the explicit `--snapshot-kind mainfile_diagnostic` is required; resulting findings are diagnostic only. Do not upload private queue/hash/source IDs to public CI or Android bundles. An attempted additional Mac source-blob eligibility inspection was client-blocked **before dispatch**; it produced no verification evidence.

## Next gates

1. Capture a transactionally consistent owner-authorized backup, verify SHA256, integrity, counts and rights.
2. Improve candidate detection using source provenance, document layout and PDF vector/raster extraction; classify official plans based on authoritative evidence, not text search alone.
3. Build source-scoped many-to-many title/parcel links and keep every unresolved or conflicting match.
4. Validate georeferencing separately from page-outline extraction, retaining plan-space polygons until reliable coordinates or sufficient control points exist.
5. Publish only an independently reviewed, consented, rights-cleared Atlas projection. Preserve public/private separation and revoked-listing withdrawal.
6. Connect the accepted projection to the Amlak Smart Listing editor and map, and test on Android (AR/FR, offline, physical phone and backend revocation).

No automatic merge, production deployment, legacy worktree cleanup or original database modification is implied by this checkpoint.
