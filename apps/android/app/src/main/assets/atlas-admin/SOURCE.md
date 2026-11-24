These unmodified GeoJSON files are the administrative reference used by Atlas Tunisia.
They contain 24 governorates and 279 delegations. The files are administrative geography.

- Reference pinned by Atlas to revision `8ac5c1d85ba0661f3352c153fe8542c0aaafe2ef`.
- Source [Map Tunisia by Alaa Taieb](https://github.com/Alaa-Taieb/map-tunisia/tree/8ac5c1d85ba0661f3352c153fe8542c0aaafe2ef).
- © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright). Data under the [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
- `governorates.geojson` SHA-256 `146ddd3be8be14f401f718afb40c1a87394b6426ed5b15d048953ef19c47de1b`.
- `delegations.geojson` SHA-256 `947ae6be5a0e4f493c6a7e0fb2be6140621d79f82ee1d4d31ee98c34391ca078`.

`places.json` is an ODbL derivative of these files with bilingual names and a
point on the largest polygon of each feature. Points are used for zooming to
an area, and do not locate properties.

## Sector / imada reference

CI prepares the unmodified medium-resolution sector GeoJSON from the same pinned revision using `python3 scripts/prepare_amlak_admin_sectors.py`. Git blob `3c867f1708cb3c1f752ee32c6288dfe13b591fb5` and exact size 2,845,889 bytes are checked before inclusion; a predownloaded file may be provided with `--source`. Upstream sector attribute index contains 2,083 rows, whereas Atlas INS target is 2,082. Preserve the upstream reference separately: no forced equivalence, no claim of official cadastral or INS coverage. Actual geometry count and SHA-256 are logged by the script. © OpenStreetMap contributors · Map Tunisia by Alaa Taieb, ODbL 1.0. Local builds without the optional sector asset explicitly show imada unavailable.
