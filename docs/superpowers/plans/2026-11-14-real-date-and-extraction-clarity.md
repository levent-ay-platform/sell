# Real date failure and extraction clarity

Goal: fix the latest real birth-date failure and make the actual certificate extraction explicit. No new documents/models or confidence reduction.

Evidence: latest real review cbdd56a6 has names/CIN but no DOB; certificate has registerReference only, missing page2 and unconfirmed type. Both native card views read the same complete labelled date; v5 agrees on day but static year crops return no digits. Native word geometry locates year; two independent v5 numeric views of that crop agree with native year at unchanged thresholds. Three existing captures of the same printed CIN now recover the same date. PDF has no extractable native text in PDFKit.

Tasks:
- [x] RED→GREEN guarded native-year geometry/v5 corroboration; private three-capture validation.
- [x] Audited owner-requested DOB refinement from stored original, only pending/changes-requested or unsubmitted reviews; never change matched names/CIN or override an existing differing DOB. Bump submission version to invalidate stale admin review. No real record is modified by agent validation.
- [x] Explicit private readout for DOB and certificate reference/area/plot including unavailable fields; accessible in wizard and My requests. Existing reports are not silently recomputed.
- [x] Targeted Python/Android checks, independent review, deploy existing runtime/proxy and build APK. Verify live API using synthetic existing evidence, and preview latest real photo without rewriting its review.

Limits: certificate parser accuracy is not fixed by presentation. Only reference is currently available for the real certificate; owner match remains unresolved. Three captures are one identity, not a broad identity benchmark.

Verification: 33 OCR Python tests, 26 workflow/HTTP tests and 66 Android JVM tests pass. Independent review found no Important/Critical findings. Active Mac source ea6dc6a19d4db315c5739ad00690422ee1510f3b; isolated public proxy POST route deployed and nginx configuration checked. Latest real capture preview over public HTTPS now returns DOB with unchanged name/CIN and legal flags false. Installed emulator My requests → readout → consented stored-card reread passes in 19.85 seconds using a new QA copy of existing fictional evidence seeded with null DOB; actual Mac OCR fills it, audit count1 and version2, still pending/unpublished. Original real review remains unchanged. Screenshots and sanitized proof are in dist/evidence for local delivery.
