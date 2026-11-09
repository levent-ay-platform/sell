# ملاك — Android Native starter

**Status:** preview code only. Uses synthetic property data and an illustrated coastline placeholder. No backend, verification result, physical map SDK, signing key, or production deployment is connected yet.

## Build (GitHub-hosted Ubuntu)
```bash
gradle -p apps/android :app:assembleDebug
```

The root GitHub Actions Android workflow uses GitHub-managed Ubuntu and never a Mac/self-hosted runner.

- App display name: `ملاك`, package: `tn.malak.app`.
- Compose Kotlin, light Tunisian-crimson/pearl tokens from user-approved reference.
- No login required to open discovery.
- Demo mode must not be mistaken for true verified listings.
- Mapping/imagery is an explicit next engineering gate; do not show fabricated coastal image as verified cadastral data.

No release signing configured. Do not distribute as production APK.
