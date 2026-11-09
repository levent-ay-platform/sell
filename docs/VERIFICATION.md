# Verification engine contract v0.1

## Terminology
Phone verification proves control of a number; identity verification proves identity to the configured degree; claim review checks declared capacity to offer a specified interest; boundary review checks map alignment. None automatically guarantees a sale is legally safe.

## Immutable binding
Case -> claimant party + ownership claim -> property revision (canonical snapshot digest) -> evidence digest(s) -> reviewer decision.
Listing -> one or more listing items, each with reviewed property revision and verification case. Rendering computes eligibility from current records, not from a booleans column.

## Case states
DRAFT -> PENDING -> NEEDS_INFO | VERIFIED | REJECTED;
VERIFIED -> SUSPENDED | EXPIRED;
NEEDS_INFO -> PENDING (new evidence/review cycle, or new case);
new legal revision -> new review case; old approval cannot cover new revision.
Only assigned reviewers/admins can decide. Every decision includes reviewer ID, timestamp, reason code, evidence list and immutable audit record.

## Required barriers
- Public visibility checks seller/claim party alignment, latest revision, case status VERIFIED, approval decision, expiration, no suspension, listing status PUBLISHED, at least one item, and every item eligible.
- Insert/update/delete of listing items revokes current publication; re-publication requires a fresh atomic eligibility check.
- New legal revision causes old published version to disappear immediately from public discovery.
- Trusted APIs use proper roles, server-side permission checks, scoped signed downloads and request idempotency keys.
- Distinct permissions for view-evidence, decide-verification, suspend-listing, and audit-access.
- Revocation and expiry must be observable even if search indexes or caches lag: a final authorization filter is mandatory.
- Claim/decision records and evidence checks are append-only in the audit trail.
- Case concurrency: one final decision per cycle; losing racer gets conflict, not silent overwrite.

## Policy rules
- Price and marketing edits can remain publicly listed after moderation if no verified legal field changes.
- Address/reference, legal capacity, party, parcel scope, unit identity and reviewed boundary changes REQUIRE new property revision and case.
- Owner revokes listing: hide immediately.
- A report is not automatically proof of fraud; allow suspension with reviewer/admin audit reason and an appeal mechanism.
- A seller can offer an undivided share only when verified for that share; NEVER assume one co-owner may sell full property.
- Do not expose identity document/deed scans or legal reference in public responses.

## Attack tests (must be executable before pilot)
1. Direct API request with verified=true is ignored/rejected.
2. Concurrent approval/rejection cannot produce two effective final decisions.
3. A new property revision invalidates old public visibility.
4. An expired or suspended case removes listing even if listing.status remains PUBLISHED.
5. A multiple-property listing with one unapproved item is hidden.
6. Ineligible seller cannot reuse another seller's approved case.
7. A revoked file URL cannot be accessed outside assigned reviewer.
8. Unauthorized reviewer cannot approve or view evidence.
9. Price-only edit does not accidentally change legal snapshot hash.
10. Faked coordinates cannot become verified legal boundaries.
