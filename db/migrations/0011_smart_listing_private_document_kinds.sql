-- Smart Listing V2: store all legal/supporting documents as private evidence.
-- Apply with a controlled migration BEFORE enabling new kind values in production.
-- Unknown document type stays OTHER_LEGAL_PROOF. No review or publication granted.
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
ALTER TABLE sell.evidence_upload_receipts
  DROP CONSTRAINT evidence_upload_receipts_evidence_kind_check,
  ADD CONSTRAINT evidence_upload_receipts_evidence_kind_check
    CHECK (evidence_kind IN (
      'IDENTITY','OWNERSHIP_CERTIFICATE','TITLE_EXTRACT','OTHER_LEGAL_PROOF',
      'SHARED_OWNERSHIP_CERTIFICATE','SALE_DEED','TRANSFER_CARD',
      'POSSESSION_REPORT','FINANCIAL_RECEIPT','PROPERTY_SERVICE_RECEIPT'
    ));
