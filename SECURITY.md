# Security policy

This repository is public. **Never** open a GitHub issue with land titles, ID scans, owner phone numbers linked to identity, passwords, API keys, keystore files, customer lists or security tokens.

For private vulnerability reports, use GitHub's private vulnerability reporting feature when enabled by repository administrators. Otherwise contact the maintainers through a private verified channel; do not publish proof-of-concept exploitation of a live system.

## Security baseline
- Authorization on every server endpoint, even when the UI hides buttons.
- Verification decisions bound to immutable snapshots, case reviewer authorization and auditable history.
- Private encrypted evidence with short-lived scoped access, never public object URLs.
- No production/staging data in CI. Synthetic data only.
- Read-only GitHub Actions permissions and hosted Ubuntu runners; no deployment on PRs.
- Dependencies and action versions reviewed; branch protection to be configured before external contributions.
