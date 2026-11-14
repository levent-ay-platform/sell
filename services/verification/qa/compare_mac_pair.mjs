// Private stdin/stdout bridge; calls the existing document-family matcher.
import {parseOcrWorkerOutput,readUntrustedOcrDocument,asTextOnlyOcrDocument} from '../src/reader.ts';
import {compareUnifiedPrivateDocumentIdentity} from '../src/unified-identity-v2.ts';
let input='';for await(const chunk of process.stdin){input+=chunk;if(input.length>8_000_000)process.exit(1);}
try{
 const {documents,front}=JSON.parse(input);
 if(!Array.isArray(documents)||documents.length<1||documents.length>8)throw Error();
 const docs=documents.map(parseOcrWorkerOutput);
 const identity=parseOcrWorkerOutput(front);
 if(identity.format!=='image'||identity.pages.length!==1)throw Error();
 let p=0;
 const pages=docs.flatMap(d=>d.pages.map(page=>{
  const n=++p;
  return {...page,page:n,lines:page.lines.map(l=>({...l,page:n})),
   ...(page.privateSpatialPage?{privateSpatialPage:{...page.privateSpatialPage,page:n,
    words:page.privateSpatialPage.words.map(w=>({...w,page:n}))}}:{})};
 }));
 if(p>8)throw Error();
 const document={format:'pdf',pages,mayApproveOwnership:false};
 const comparison=compareUnifiedPrivateDocumentIdentity(document,identity);
 // All photographed continuation pages share the first page's document context.
 const facts=readUntrustedOcrDocument(asTextOnlyOcrDocument(document)).privateFamilyFields.facts
   .filter(f=>['CANDIDATE','LOW_CONFIDENCE'].includes(f.status)&&f.value!==null&&f.evidence.length>0)
   .map(f=>({key:f.key,value:f.value,page:f.evidence[0].page,status:f.status,source:f.evidence[0].source,confidence:f.evidence[0].confidence,bbox:f.evidence[0].bbox}));

 const keys=new Set(['registerReference','parentRegisterReference','registeredAreaM2',
  'soldAreaM2','possessedAreaM2','transferredAreaM2','registeredPlotNumber','soldPlotNumber',
  'possessedPlotNumber','salePriceTnd','transferReference','serviceRequestNumber','serviceRequestDate','rightsDenominator']);
 const selected=['ROLE_TEXT_MATCH_ONLY','NAME_ONLY_REVIEW'].includes(comparison.assessment)?
  comparison.roles.filter(r=>r.assessment==='PRELIMINARY_TEXT_AGREEMENT'):[];
 const role=selected.length===1?selected[0]:null;
 const propertyFacts=facts.filter(f=>keys.has(f.key)&&
  facts.filter(x=>x.key===f.key).every(x=>x.value===f.value)).filter((f,i,a)=>a.findIndex(x=>x.key===f.key)===i);
 process.stdout.write(JSON.stringify({detectedKind:comparison.detectedKind,
  assessment:comparison.assessment,matchedRole:role?.role??null,
  documentCinStatus:role?.documentCinStatus??null,
  matchedRightsHolder:role?comparison.matchedRightsHolder:null,propertyFacts,
  certificatePages:p,identityPages:1,ownershipVerified:false,identityVerified:false,
  mayPublish:false,requiresHumanReview:true}));
}catch{process.stderr.write('PAIR_PROCESSING_FAILED\n');process.exitCode=1;}
