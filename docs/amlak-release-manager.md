# Amlak consolidated release gate

One candidate branch: `fix/amlak-cpf-property-location-20261124` (draft PR #32). This integrates the already inherited PR #28/#29 code and the independent release-manager PR #30, preserving the main-branch CPF documentation. No merge to main, deployment or data migration.

Every push to the candidate runs the existing six offline CI workflows: full Android build/UI, private attachment, identity OCR, CPF, Atlas evidence and deep archive review. The release manager requires exact SHA and successful named jobs; it also checks the APK archive digest and expiry. Use push run IDs, not skipped PR jobs.

```sh
python3 scripts/amlak_release_manager.py --sha FULL_SHA --android FULL_GATE_RUN --private PRIVATE_RUN --ocr OCR_RUN --cpf CPF_RUN --atlas ATLAS_RUN --deep DEEP_REVIEW_RUN
```

This is a CI-only gate. Real certificate fields, owner share, local compatible adapter, enrolled-device authorization, actual official cadastral evidence, physical handset, Nozha live inventory and reviewed deployment remain separate. No private data in CI.
