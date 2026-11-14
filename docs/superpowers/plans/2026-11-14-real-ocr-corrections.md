# Real OCR corrections Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Recover readable front-ID birth dates without guessing and correct the demonstrated Arabic identity adapter and hidden review reasons.

**Architecture:** Retain v5 numeric/name recognition. Add a Mac-only date fallback that requires two native image views, a date in the birth row, an exact Tunisian month and agreement with v5 day/year votes. Preserve strict document match thresholds and encrypted originals.

**Tech Stack:** Python/OpenCV/ONNX, existing Swift Vision worker, TypeScript matcher, Android Kotlin.

**Spec:** User's real-phone findings and explicit approval to start improvements, 2026-11-14.

## Constraints and review focus

- No private source images or extracted personal values in Git or logs.
- No fabricated dates, approximate month spelling, threshold lowering, automatic legal approval or synthetic accuracy claims.
- Different dates between views, invalid/future dates, missing digits, an issue-date row or conflicting v5 readings must abstain.
- Do not treat historical deed price or entire registered area as the customer's current asking price or offered area.
- Keep phone accounts and pilot PIN 1234 unchanged.

## Tasks

- [x] Reproduce the failed month on the encrypted real sample; inspect the month visually and record sanitized observations.
- [x] Write failing date-consensus/geometry/conflict tests, then implement bounded optional Mac fallback. Verify existing v5 tests and real sample without saving its values.
- [x] Write failing Arabic adapter and bridge-reason/missing-page tests; correct the adapter and preserve explanations through the API and Android UI.
- [ ] Run Python, TypeScript and Android checks. Obtain final independent review; fix important findings.
- [ ] Build versioned APK, deploy the tested existing Mac runtime, verify live extraction and record honest accuracy limits.

## Ledger

Baseline: clean codex/malak-pilot-complete at 80c6a63. Existing isolated clone retained.
Real sample: visually legible December; v5 day/year agree, month fails. Native Vision reads exact month in full card and date crop, but its line score is 0.5. This score is not interpreted as calibrated accuracy. A native-only date is insufficient: require two views plus independent v5 numeric agreement and a bounded birth-row location.

Local evidence: 32 OCR tests, 20 pilot workflow/bridge tests, 387 TypeScript tests and 61 Android JVM tests pass; debug APK and instrumentation APK build. Local real-pair processing recovers DOB, preserves prior name/CIN, reports missing page 2 and unconfirmed document type. Comparison remains INSUFFICIENT_EVIDENCE; only registerReference extracted. No real review/submission was rewritten.

Fresh independent review found two Important edge cases. Both reproduced RED then GREEN: reject confident partial day/year conflicts even when the neighbouring component is unreadable; preserve missing-page warning even when the missing page exceeds upload limit 8. Historical deed price no longer populates current asking price (separate RED→GREEN Android regression).

Broader legacy Python suite: 35/40 pass; five environment errors remain in existing Linux/Tesseract ingest tests, unrelated to the active Mac Vision path: test_force_ocr_bypasses_native_fast_path, test_image_ocr_path, test_image_only_pdf_goes_through_ocr, test_native_pdf_fast_path, test_ocr_page_contains_only_advisory_quality_flags. This Mac lacks their Linux fonts, tesseract and pdftotext. Do not report this suite as passing.

Ruling: this is a corroborated field-reading improvement, not model training or a multi-identity accuracy benchmark. Only one available real identity/document pair has been evaluated. Existing encrypted evidence and phone/PIN behaviour stay intact.
