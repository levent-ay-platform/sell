/**
 * Natural-person, Tunisian property document triage (F1).
 * No optical text recognition, identity or registry checks happen in this module.
 * Extracted fields are UNTRUSTED until checked against independent official evidence.
 */
export type DocumentKind =
  | 'CPF_OWNERSHIP_CERTIFICATE'
  | 'CPF_COOWNERSHIP_CERTIFICATE'
  | 'TITLE_EXTRACT'
  | 'OTHER_OR_UNKNOWN';
export type DocumentFormat = 'DIGITAL_CEV' | 'PAPER_SCAN' | 'UNKNOWN';
export type PersonCapacity = 'SOLE_OWNER' | 'CO_OWNER' | 'HEIR' | 'MANDATE';
export type EvidenceCheck = 'CONFIRMED' | 'NOT_CHECKED' | 'CONFLICT' | 'INCONCLUSIVE';

export interface UntrustedDocumentExtraction {
  kind: DocumentKind;
  format: DocumentFormat;
  declaredPageCount: number | null;
  receivedPageNumbers: readonly number[];
  hasCev: boolean;
  extractionWarnings?: readonly ('UNREADABLE_PAGE_COUNT' | 'INCONSISTENT_PAGE_COUNT' | 'LOW_CONFIDENCE' | 'CONFLICTING_FIELDS')[];
  /** Non-authoritative OCR fields, never forwarded to the public listing API. */
  extracted?: Readonly<{
    registryReference?: string;
    areaSquareMetres?: number;
    ownershipShareUnits?: number;
    ownershipTotalUnits?: number;
    parcelCount?: number;
    parcelNumber?: string;
    certificateIssuedAt?: string;
    lastRegistryUpdatedAt?: string;
  }>;
}

export interface IndividualClaimTriageInput {
  claimantKind: 'PERSON' | 'COMPANY';
  capacity: PersonCapacity;
  document: UntrustedDocumentExtraction;
  /** Supplied ONLY by an authorized server-side source connector or reviewer. */
  authoritative?: Readonly<{
    documentAuthenticity: EvidenceCheck;
    currentTitleAndOperations: EvidenceCheck;
    identityToOwnerAndShare: EvidenceCheck;
    offeredScope: EvidenceCheck;
  }>;
}

export type FindingCode =
  | 'INDIVIDUALS_ONLY'
  | 'MISSING_PAGES'
  | 'PAGE_COUNT_UNREADABLE'
  | 'PAGE_NUMBERS_INVALID'
  | 'LEGACY_NO_QR'
  | 'NEEDS_CEV_SIGNATURE_VALIDATION'
  | 'CEV_INVALID_OR_MISMATCHED'
  | 'DOCUMENT_TYPE_REQUIRES_LEGAL_REVIEW'
  | 'INDEPENDENT_REGISTRY_CHECK_REQUIRED'
  | 'APPLICANT_OWNERSHIP_MATCH_REQUIRED'
  | 'OFFERED_SHARE_SCOPE_CHECK_REQUIRED'
  | 'HEIR_OR_MANDATE_LEGAL_REVIEW'
  | 'FRESH_ISSUANCE_NOT_ESTABLISHED'
  | 'OWNERSHIP_SHARE_DENOMINATOR_UNKNOWN'
  | 'OFFICIAL_EVIDENCE_CONFLICT' | 'OCR_FIELD_CONFLICT' | 'OCR_LOW_CONFIDENCE';

export type VerificationLane =
  | 'OUT_OF_SCOPE'
  | 'NEEDS_INFORMATION'
  | 'OFFICIAL_SOURCE_CHECK'
  | 'LEGAL_REVIEW';

export interface DocumentTriageResult {
  lane: VerificationLane;
  findings: readonly FindingCode[];
  documentRoute: 'CEV' | 'PAPER_TO_CPF' | 'LEGAL_REVIEW';
  /** Deliberately never authorizes publication, regardless of check flags. */
  publicationAllowed: false;
  ownershipVerified: false;
  humanDecisionRequired: true;
}

/**
 * Pure eligibility/collection preflight. Cannot mark a property VERIFIED or publish.
 * Certificate issue date is distinct from registry-update date; do not infer one from the other.
 */
export function triageIndividualClaim(input: IndividualClaimTriageInput): DocumentTriageResult {
  const d = input.document;
  const findings: FindingCode[] = [];
  const add = (code: FindingCode) => { if (!findings.includes(code)) findings.push(code); };
  const documentRoute: DocumentTriageResult['documentRoute'] =
    d.hasCev && d.format === 'DIGITAL_CEV' ? 'CEV' :
    d.kind === 'OTHER_OR_UNKNOWN' ? 'LEGAL_REVIEW' : 'PAPER_TO_CPF';

  if (input.claimantKind !== 'PERSON') add('INDIVIDUALS_ONLY');
  if (d.extractionWarnings?.includes('CONFLICTING_FIELDS') ||
      d.extractionWarnings?.includes('INCONSISTENT_PAGE_COUNT')) add('OCR_FIELD_CONFLICT');
  if (d.extractionWarnings?.includes('LOW_CONFIDENCE')) add('OCR_LOW_CONFIDENCE');
  if (d.declaredPageCount === null || !Number.isSafeInteger(d.declaredPageCount) || d.declaredPageCount <= 0 || d.declaredPageCount > 100) {
    add('PAGE_COUNT_UNREADABLE');
  }
  const unique = new Set(d.receivedPageNumbers);
  if (unique.size !== d.receivedPageNumbers.length || d.receivedPageNumbers.some(n => !Number.isSafeInteger(n) || n < 1 || (d.declaredPageCount !== null && n > d.declaredPageCount))) {
    add('PAGE_NUMBERS_INVALID');
  }
  if (d.declaredPageCount !== null && Number.isSafeInteger(d.declaredPageCount) && d.declaredPageCount > 0 && d.declaredPageCount <= 100 && unique.size < d.declaredPageCount) {
    add('MISSING_PAGES');
  }
  if (documentRoute === 'PAPER_TO_CPF') add('LEGACY_NO_QR');
  if (documentRoute === 'LEGAL_REVIEW') add('DOCUMENT_TYPE_REQUIRES_LEGAL_REVIEW');
  if (documentRoute === 'CEV' && input.authoritative?.documentAuthenticity !== 'CONFIRMED') {
    add(input.authoritative?.documentAuthenticity === 'CONFLICT' ? 'CEV_INVALID_OR_MISMATCHED' : 'NEEDS_CEV_SIGNATURE_VALIDATION');
  }
  if (input.authoritative?.documentAuthenticity === 'CONFLICT' ||
      input.authoritative?.currentTitleAndOperations === 'CONFLICT' ||
      input.authoritative?.identityToOwnerAndShare === 'CONFLICT' ||
      input.authoritative?.offeredScope === 'CONFLICT') add('OFFICIAL_EVIDENCE_CONFLICT');
  if (input.authoritative?.currentTitleAndOperations !== 'CONFIRMED') add('INDEPENDENT_REGISTRY_CHECK_REQUIRED');
  if (input.authoritative?.identityToOwnerAndShare !== 'CONFIRMED') add('APPLICANT_OWNERSHIP_MATCH_REQUIRED');
  if (input.authoritative?.offeredScope !== 'CONFIRMED') add('OFFERED_SHARE_SCOPE_CHECK_REQUIRED');
  if (input.capacity === 'HEIR' || input.capacity === 'MANDATE') add('HEIR_OR_MANDATE_LEGAL_REVIEW');
  // A historical "date of last update" is not the date this certificate was issued.
  if (!d.extracted?.certificateIssuedAt) add('FRESH_ISSUANCE_NOT_ESTABLISHED');
  if (d.extracted?.ownershipShareUnits !== undefined && d.extracted.ownershipTotalUnits === undefined) {
    add('OWNERSHIP_SHARE_DENOMINATOR_UNKNOWN');
  }

  const lane: VerificationLane = input.claimantKind !== 'PERSON' ? 'OUT_OF_SCOPE' :
    findings.some(f => ['MISSING_PAGES', 'PAGE_COUNT_UNREADABLE', 'PAGE_NUMBERS_INVALID'].includes(f)) ? 'NEEDS_INFORMATION' :
    findings.includes('DOCUMENT_TYPE_REQUIRES_LEGAL_REVIEW') || findings.includes('HEIR_OR_MANDATE_LEGAL_REVIEW') || findings.includes('OFFICIAL_EVIDENCE_CONFLICT') ? 'LEGAL_REVIEW' :
    'OFFICIAL_SOURCE_CHECK';
  return {lane, findings, documentRoute, publicationAllowed: false, ownershipVerified: false, humanDecisionRequired: true};
}
