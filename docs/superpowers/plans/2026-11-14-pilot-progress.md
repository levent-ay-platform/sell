# Ledger — 2026-11-14-pilot-to-publication
Baseline 1d73e1aa98f9. Fresh clone on codex/malak-pilot-complete; existing root-owned OCR runtime untouched. Native worktree tool rejected ref parameters twice; isolated clone used.
Baseline: 10/10 pilot domain/HTTP tests passed.
Ruling: use isolated SQLite workflow store for pilot submissions with durable accounts keyed solely by normalized phone, instead of deploying dormant legal-verification Postgres/Vault infrastructure. Retains existing legal tables/gates; admin publication does not fabricate legal approval. Cost: later migration adapter to production database.
Task 1: in progress.
Task 1: six-field regression RED (bad OCR response) → GREEN. Compiled native Mac Vision worker; actual synthetic sale image classified SALE_DEED and extracted register, sold lot/area/price. Synthetic identity still not a reliable positive ID benchmark.
Task 2: 13 Python workflow/handler/frame/bridge tests PASS. Durable phone-only identity, encrypted original evidence, administrator decisions, public listing redaction. Existing TS suite 387/387 PASS after setting TMPDIR to a real path (macOS /var alias otherwise trips existing private-store symlink guard).
Ruling: retain low-confidence sourced fields in the private preview with status/source; do not silently discard or auto-fill them. Cost: users/admin must review uncertain values.
Ruling: completed incomplete identity OCR may proceed to admin review; HTTP/inference failure requires retry. This implements owner's admin-review policy, replacing prior all-four-fields camera gate. Inconsistent provided CIN/DOB remain invalid.
Task 3: implementation in progress; Android build running.
