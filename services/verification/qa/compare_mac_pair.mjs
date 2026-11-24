// Private stdin/stdout bridge; calls the existing document-family matcher.
import {parseOcrWorkerOutput,readUntrustedOcrDocument,asTextOnlyOcrDocument} from '../src/reader.ts';
import {auditPrintedPagination} from '../src/cpf-pagination.ts';
import {extractPrivateLandDocumentFacts} from '../src/document-family-fields-v2.ts';
import {moveParcelPlanScanToPage} from '../src/parcel-document-foundation.ts';
import {compareUnifiedPrivateDocumentIdentity} from '../src/unified-identity-v2.ts';
let input='';for await(const chunk of process.stdin){input+=chunk;if(input.length>8_000_000)process.exit(1);}
try{
 const {documents,front,propertyViews=[]}=JSON.parse(input);
 if(!Array.isArray(documents)||documents.length<1||documents.length>8)throw Error();
 const docs=documents.map(parseOcrWorkerOutput);
 const identity=parseOcrWorkerOutput(front);
 if(identity.format!=='image'||identity.pages.length!==1)throw Error();
 let p=0;
 const pages=docs.flatMap(d=>d.pages.map(page=>{
  const n=++p;
  return {...page,page:n,lines:page.lines.map(l=>({...l,page:n})),
   ...(page.parcelPlanScan?{parcelPlanScan:moveParcelPlanScanToPage(page.parcelPlanScan,n)}:{}),
   ...(page.privateSpatialPage?{privateSpatialPage:{...page.privateSpatialPage,page:n,
    words:page.privateSpatialPage.words.map(w=>({...w,page:n}))}}:{})};
 }));
 if(p>8)throw Error();
 const document={format:'pdf',pages,mayApproveOwnership:false};
 const comparison=compareUnifiedPrivateDocumentIdentity(document,identity);
 // All photographed continuation pages share the first page's document context.
 const reading=readUntrustedOcrDocument(asTextOnlyOcrDocument(document));
 const reasons=[...comparison.reasons];
 const certificate=['OWNERSHIP_CERTIFICATE','SHARED_OWNERSHIP_CERTIFICATE'].includes(reading.documentFamilyCandidate.kind);
 const pagination=auditPrintedPagination(document.pages);
 const missingPages=certificate?pagination.missingPages:[];
 if(certificate&&pagination.status==='MISSING_PAGES')reasons.push('CERTIFICATE_PAGES_MISSING');
 if(certificate&&pagination.status==='CONFLICT')reasons.push('CERTIFICATE_PAGE_NUMBER_INCONSISTENT');
 if(certificate&&pagination.status==='UNREADABLE')reasons.push('CERTIFICATE_PAGE_COUNT_UNREADABLE');
 // Alternate OCR is private PROPERTY evidence only. Do not send crop
 // lines into identity comparison, owner-row layout, or parcel geometry.
 if(!Array.isArray(propertyViews)||propertyViews.length>6)throw Error();
 const recoverKeys=new Set(['registeredAreaM2','rightsDenominator','registeredPlotNumber',
   'registeredParcelCount','registeredLandCategory','parentRegisterReference',
   'registeredPropertyName','registeredPropertyContent']);
 let familyFacts=reading.privateFamilyFields.facts;
 if(propertyViews.length&&['OWNERSHIP_CERTIFICATE','SHARED_OWNERSHIP_CERTIFICATE'].includes(reading.documentFamilyCandidate.kind)){
   const extra=propertyViews.flatMap(v=>{
     const parsed=parseOcrWorkerOutput(v);
     if(parsed.format!=='image'||parsed.pages.length!==1||parsed.pages[0].lines.length>60)throw Error();
     return parsed.pages[0].lines.map(l=>({text:l.text,page:1,source:'ocr-ar',confidence:Math.min(.5,l.confidence??.5)}));
   });
   const recovered=extractPrivateLandDocumentFacts(
     [...asTextOnlyOcrDocument(document).pages.flatMap(p=>p.lines),...extra],reading.documentFamilyCandidate);
   familyFacts=familyFacts.map(f=>recoverKeys.has(f.key)?recovered.facts.find(x=>x.key===f.key)??f:f);
 }
 if(familyFacts.some(f=>recoverKeys.has(f.key)&&f.status==='CONFLICT'))reasons.push('CPF_PROPERTY_FIELDS_CONFLICT');
 if(familyFacts.some(f=>f.key==='registeredAreaM2'&&f.status==='CONFLICT'))reasons.push('CPF_PROPERTY_AREA_CONFLICT');
 const facts=familyFacts
   .filter(f=>['CANDIDATE','LOW_CONFIDENCE'].includes(f.status)&&f.value!==null&&f.evidence.length>0)
   .map(f=>({key:f.key,value:f.value,page:f.evidence[0].page,status:f.status,source:f.evidence[0].source,confidence:f.evidence[0].confidence,bbox:f.evidence[0].bbox}));

 const keys=new Set(['registerReference','parentRegisterReference','registeredAreaM2',
  'soldAreaM2','possessedAreaM2','transferredAreaM2','registeredPlotNumber','soldPlotNumber',
  'possessedPlotNumber','salePriceTnd','transferReference','serviceRequestNumber','serviceRequestDate','rightsDenominator','propertyGovernorate','propertyDelegation','propertyImada']);
 const extensionKeys=new Set(['registeredLocationText','historicalLocality','registeredParcelCount','registeredPropertyName',
  'registeredPropertyContent','registeredLandCategory','creationNature']);
 const selected=['ROLE_TEXT_MATCH_ONLY','NAME_ONLY_REVIEW'].includes(comparison.assessment)?
  comparison.roles.filter(r=>r.assessment==='PRELIMINARY_TEXT_AGREEMENT'):[];
 const role=selected.length===1?selected[0]:null;
 const selectFacts=allowed=>facts.filter(f=>allowed.has(f.key)&&
  facts.filter(x=>x.key===f.key).every(x=>x.value===f.value)).filter((f,i,a)=>a.findIndex(x=>x.key===f.key)===i);
 const propertyFacts=selectFacts(keys);
 const extendedPropertyFacts=selectFacts(extensionKeys);
 // Owner-scoped report only, not public listing fields. No actor names or
 // identity values are included. Preserve bounded numeric alternatives and
 // whether the observation came from the original page or a derived view.
 const originalLineCount=asTextOnlyOcrDocument(document).pages.reduce((n,p)=>n+p.lines.length,0);
 const numericReviewKeys=new Set(['registeredAreaM2','rightsDenominator','registeredPlotNumber','registeredParcelCount']);
 const propertyConflicts=familyFacts.filter(f=>numericReviewKeys.has(f.key)&&f.status==='CONFLICT').map(f=>({
   key:f.key,status:'CONFLICT',alternatives:f.evidence.filter(e=>typeof e.normalizedValue==='string'&&
     /^[0-9]{1,12}(?:[.][0-9]{1,3})?$/.test(e.normalizedValue)).slice(0,12).map(e=>({
       value:e.normalizedValue,page:e.page,source:e.source,confidence:e.confidence,
       view:e.lineIndex<originalLineCount?'ORIGINAL_PAGE':'PROPERTY_CROP',
       bbox:e.lineIndex<originalLineCount?e.bbox:null
     })),
   truncated:f.evidence.length>12
 }));

 process.stdout.write(JSON.stringify({detectedKind:comparison.detectedKind,
  assessment:comparison.assessment,matchedRole:role?.role??null,
  documentCinStatus:role?.documentCinStatus??null,
  matchedRightsHolder:role?comparison.matchedRightsHolder:null,propertyFacts,extendedPropertyFacts,propertyConflicts,
  reasons:[...new Set(reasons)],missingPages,
  parcelPlanScans:reading.parcelPlanScans,parcelPlanFacts:reading.parcelPlanFacts,
  parcelEdgeMeasurements:reading.parcelEdgeMeasurements,
  certificatePages:p,identityPages:1,ownershipVerified:false,identityVerified:false,
  mayPublish:false,requiresHumanReview:true}));
}catch{process.stderr.write('PAIR_PROCESSING_FAILED\n');process.exitCode=1;}
