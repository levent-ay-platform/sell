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

### Pilot publication and V2 parcels

Enable the Android publishing flow with `-PmalakV2Discovery=true`, `-PmalakDirectPublishing=true`, and the isolated Pilot HTTPS origin. The Pilot service enables its routes with `MALAK_DIRECT_PUBLISHING=1`.

The connected Pilot additionally requires `MALAK_PILOT_DEVICE_ENROLLMENT`, a
private JSON file mapping each enrolled +216 phone number to its SHA-256
device ID shown in Android Settings. Keep the file outside the repository.
Sessions from installations outside that enrollment are rejected, including
previously issued sessions. The existing code 1234 requires possession of the
enrolled installation. The APK reads `/api/public/capabilities` before opening
direct publishing. A legacy gateway keeps its existing review journey.

Radar search uses public map coordinates only and excludes area-only listings.
The Pilot filters with a great-circle distance. Discovery V2 applies the same
radius in PostGIS before pagination and clustering.

The Pilot gateway uses `MALAK_PILOT_PARCEL_ORIGIN` and `MALAK_PILOT_PARCEL_TOKEN` to reach the V2 gateway. Configure the same dedicated token as `discoveryV2.pilotParcelToken` in V2. The server accepts the link when the published V2 parcel belongs to the same title and plot, the point is within 1 km of the approved boundary, and the protected identity and ownership evidence fingerprints match.

A signed-in reviewer opens the Pilot listing in the review desk and attaches the approved V2 listing ID. The buyer and Atlas keep the Pilot listing ID. They read the current approved polygon and permitted document renditions through the Pilot gateway. A withdrawn approval, paused advertisement, changed parcel scope, or replacement review removes the verified projection until the conditions are met again.
