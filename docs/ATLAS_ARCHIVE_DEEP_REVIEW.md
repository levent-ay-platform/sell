# Archive deep-review companion

Independent branch based on the accepted Intel Mac revision 0fb0cae. Do not update the running batch checkout: its audit and queue must remain stable.

- Inventory opt-in --probe-other-extensions checks PDF headers in other regular files under the explicitly approved root, then matches exact SHA as before. The default scan remains unchanged. Keep its report separate; do not resume an existing batch with a changed eligible queue.
- Page-window deep review can read a PDF up to 300 MiB, in windows of at most 20 pages, with native layout and optional separate sparse Arabic/French raster OCR. It reports legacy size/page limit flags, separate observations and continuation page. It does not persist raw text. The private candidate references preserve leading zeros and suffixes; labeled area candidates are not validated. No automatic source links or geography. External command timeouts are enforced; run in a resource-isolated environment. Output bytes are checked after subprocess completion, not a hard OS disk quota.
- No actual source archives accessed or published by this branch. Current results are synthetic until owner runs a separate checkout on real data. Missing PDF recovery is not guaranteed. Do not change scopes or existing batch manifests.
