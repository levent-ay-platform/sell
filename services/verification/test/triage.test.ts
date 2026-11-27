import test from 'node:test';
import assert from 'node:assert/strict';
import {triageIndividualClaim} from '../src/triage.ts';
import type {IndividualClaimTriageInput} from '../src/triage.ts';

/** Fictional Arabic-format scan: NO data from a real person's certificate. */
const sample:IndividualClaimTriageInput={
  claimantKind:'PERSON',capacity:'SOLE_OWNER',
  document:{kind:'CPF_OWNERSHIP_CERTIFICATE',format:'PAPER_SCAN',hasCev:false,
    declaredPageCount:2,receivedPageNumbers:[1],
    extracted:{registryReference:'TEST-TITLE-000',areaSquareMetres:843,
      ownershipShareUnits:512,lastRegistryUpdatedAt:'2014-07-13'}}
};
test('paper without QR is accepted as evidence but not verified',()=>{
 const r=triageIndividualClaim(sample);
 assert.equal(r.documentRoute,'PAPER_TO_CPF');
 assert.equal(r.lane,'NEEDS_INFORMATION');
 assert.ok(r.findings.includes('LEGACY_NO_QR'));
 assert.equal(r.publicationAllowed,false);
 assert.equal(r.ownershipVerified,false);
});
test('page 1 of 2 must request second page',()=>{
 assert.ok(triageIndividualClaim(sample).findings.includes('MISSING_PAGES'));
});
test('shares are not area and denominator must be known',()=>{
 const r=triageIndividualClaim(sample);
 assert.ok(r.findings.includes('OWNERSHIP_SHARE_DENOMINATOR_UNKNOWN'));
 assert.equal(sample.document.extracted?.areaSquareMetres,843);
 assert.equal(sample.document.extracted?.ownershipShareUnits,512);
});
test('the historical last-update date is not an issue date',()=>{
 assert.ok(triageIndividualClaim(sample).findings.includes('FRESH_ISSUANCE_NOT_ESTABLISHED'));
});
test('all pages do not establish current registered ownership',()=>{
 const r=triageIndividualClaim({...sample,document:{...sample.document,receivedPageNumbers:[1,2]}});
 assert.equal(r.lane,'OFFICIAL_SOURCE_CHECK');
 assert.ok(r.findings.includes('INDEPENDENT_REGISTRY_CHECK_REQUIRED'));
});
test('a positive signed QR still needs an authorized reviewer',()=>{
 const r=triageIndividualClaim({
   claimantKind:'PERSON',capacity:'SOLE_OWNER',
   document:{kind:'CPF_OWNERSHIP_CERTIFICATE',format:'DIGITAL_CEV',hasCev:true,
     declaredPageCount:1,receivedPageNumbers:[1],extracted:{certificateIssuedAt:'2026-11-10'}},
   authoritative:{documentAuthenticity:'CONFIRMED',currentTitleAndOperations:'CONFIRMED',
     identityToOwnerAndShare:'CONFIRMED',offeredScope:'CONFIRMED'}
 });
 assert.equal(r.documentRoute,'CEV');
 assert.equal(r.humanDecisionRequired,true);
 assert.equal(r.publicationAllowed,false);
});
test('unverified QR signature cannot silently pass',()=>{
 const r=triageIndividualClaim({...sample,document:{...sample.document,format:'DIGITAL_CEV',hasCev:true}});
 assert.ok(r.findings.includes('NEEDS_CEV_SIGNATURE_VALIDATION'));
});
test('duplicated page numbers cannot establish completeness',()=>{
 const r=triageIndividualClaim({...sample,document:{...sample.document,receivedPageNumbers:[2,2]}});
 assert.ok(r.findings.includes('PAGE_NUMBERS_INVALID'));
 assert.ok(r.findings.includes('MISSING_PAGES'));
});
test('individual heir and proxy claims require specialist legal review',()=>{
 for(const capacity of ['HEIR','MANDATE'] as const) {
  const r=triageIndividualClaim({...sample,capacity,document:{...sample.document,receivedPageNumbers:[1,2]}});
  assert.equal(r.lane,'LEGAL_REVIEW');
 }
});
test('companies are excluded from the first phase',()=>{
 const r=triageIndividualClaim({...sample,claimantKind:'COMPANY'});
 assert.equal(r.lane,'OUT_OF_SCOPE');
});
test('registry conflict must route to legal review even if document present',()=>{
 const r=triageIndividualClaim({...sample,document:{...sample.document,receivedPageNumbers:[1,2]},
   authoritative:{documentAuthenticity:'CONFIRMED',currentTitleAndOperations:'CONFLICT',
     identityToOwnerAndShare:'CONFIRMED',offeredScope:'CONFIRMED'}});
 assert.equal(r.lane,'LEGAL_REVIEW');
 assert.ok(r.findings.includes('OFFICIAL_EVIDENCE_CONFLICT'));
});
test('unknown document is routed to specialist review not rejected as forgery',()=>{
 const r=triageIndividualClaim({...sample,document:{...sample.document,kind:'OTHER_OR_UNKNOWN',
   receivedPageNumbers:[1,2]}});
 assert.equal(r.documentRoute,'LEGAL_REVIEW');
});
