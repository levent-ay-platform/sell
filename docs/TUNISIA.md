# Tunisia domain and localization checklist

- Default launch country: TN. Initial discovery focused on Nabeul/Kelibia geography, yet database supports broader Tunisia.
- Languages: fr-TN and ar-TN, bidirectional layouts, no forced account for browsing; phone numbers normalized to E.164 with country +216.
- Currency: TND, three decimal places at the storage boundary; all inputs, rounding, filters and sorting use exact decimals, never floating-point price calculations.
- Area: square meters as primary; hectares optional for agricultural land. Legal area and measured GIS area are different values.
- Geography: governorate/delegation/locality identifiers are dataset-versioned, not hardcoded free-text labels.
- Land ownership identifiers: internal plot number is NOT the same as title/reference number or cadastral parcel number. Keep separate nullable typed identifiers and reviewer scope.
- Parcel polygon: upload/draw is user-supplied geometry until an authorized reviewer confirms alignment; render approximate point separately.
- Seller role: individual owner, co-owner, heir, company representative, authorized mandate; each has a different legal evidence workflow configured outside public repository.
- Phone contact: consent and spam controls; buyer receives no private documents by default.
- Seller onboarding should be save-and-resume, low-bandwidth, accessible to budget Android devices.
- External map data must have attribution/licensing reviewed before production.
- No fake property photos, fake ownership badges or automatically generated legal confirmations.
