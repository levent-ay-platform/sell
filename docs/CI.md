# Hosted CI policy

Repository is PUBLIC to use GitHub standard hosted Actions, not to make production data public.

- Allow jobs with `runs-on: ubuntu-latest` only; forbid `self-hosted`, Mac/macos, organization runner labels.
- Use `pull_request`, `push` and optional manual `workflow_dispatch`, not `pull_request_target` for untrusted code.
- Workflow token is `contents: read`; never make a PR job access secrets or deployment/signing credentials.
- Workflow must not upload real customer documents, build logs containing identity data, or live connection strings.
- Android APK/AAB build will use GitHub-hosted Ubuntu, not any existing Mac, Windows or server.
- CI service databases use synthetic/test-only credentials and disposable data; never production records.
- Separate release approval and signing keys, stored in restricted secrets or trusted external custody, only when release process exists.
- Use concurrency to cancel obsolete workflow runs and cap timeouts.
- Pin actions by full commit SHA during supply-chain hardening before first public contributors; baseline uses version tags temporarily.
- GitHub Settings manual follow-up: require PR reviews and passing checks for main; restrict Actions permissions to read-only defaults; require approval of outside-contributor runs; disable unsafe workflow triggers.
