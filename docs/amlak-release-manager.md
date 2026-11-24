# Amlak Release Manager (CI-only)
One read-only check, not a deployment agent. It uses existing authenticated GitHub CLI access, not CTR or a new remote execution channel.

For the frozen 3ed4857 candidate:
```sh
python3 scripts/amlak_release_manager.py --sha 3ed485746663d0d8cb79d12fb0668bfbe17e1a99 --android 45464649184 --private 45464649293 --ocr 45464658394
```
Exit 0 only when all named push workflows and required jobs succeeded for the exact SHA and the matching unexpired APK archive has a reported SHA256 digest. Exit 2 otherwise. No deploy, merge, service changes, or personal documents. CI readiness does not prove all instrumentation cases executed (inspect reports/skips), exact OCR accuracy, device enrollment, successful live private API acceptance, or official cadastral verification. The artifact digest covers the archive; verify it and the embedded APK SHA256SUMS before installation. Follow PR #29 cutover checklist through an authorized execution session after acceptance.
