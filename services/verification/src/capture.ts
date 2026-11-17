/**
 * Privacy-preserving metadata preflight for a natural person's property evidence.
 * Runs server-side before scheduling private OCR. Never accepts raw image data,
 * owner names, title numbers or client assertions of ownership verification.
 * A client can suggest page order, but server must independently hash/size/type
 * each uploaded object and authorize its case before constructing these inputs.
 */
export type MediaType = 'image/png'|'image/jpeg'|'image/tiff'|'application/pdf';
export type QualityWarning = 'LOW_RESOLUTION'|'LOW_CONTRAST'|'POSSIBLE_BLUR'|'REVIEW_ORIENTATION';
export interface PageCaptureMetadata {
  /** Logical page in the official document, as claimed by submitter. */
  logicalPage: number;
  /** Page inside one physical file; images are always 1. */
  filePage: number;
  /** Server SHA256 of original immutable file bytes (not a caller hash). */
  fileSha256: string;
  mime: MediaType;
  sizeBytes: number;
  /** Only from isolated private-worker quality inspector; untrusted for legal decisions. */
  qualityWarnings: readonly QualityWarning[];
}
export interface CapturePreflightInput {
  claimantKind: 'PERSON'|'COMPANY';
  /** Explicit informed consent captured by authenticated server transaction. */
  consentGiven: boolean;
  /** OCR-derived printed total or null; this is a *claim*, not a proof. */
  printedTotalPages: number|null;
  pages: readonly PageCaptureMetadata[];
}
export type CaptureFinding =
  | 'INDIVIDUALS_ONLY'|'CONSENT_REQUIRED'|'NO_DOCUMENT_PAGES'|'TOO_MANY_PAGES'
  | 'INVALID_PAGE_METADATA'|'INVALID_FILE_METADATA'|'PAGE_NUMBER_DUPLICATE'
  | 'PAGE_NUMBER_GAP'|'PRINTED_PAGE_COUNT_UNKNOWN'|'PRINTED_PAGE_COUNT_INVALID'
  | 'DOCUMENT_PAGES_MISSING'|'DUPLICATE_CAPTURE'|'CAPTURE_QUALITY_REVIEW';
export type CaptureAction =
  | 'PROVIDE_CONSENT'|'SEND_FULL_DOCUMENT'|'RETAKE_DUPLICATED_PAGE'
  | 'RETAKE_LOW_QUALITY_PAGE'|'CONFIRM_PAGE_NUMBERING'|'AUTHORIZED_REVIEW_REQUIRED';
export interface CapturePreflightResult {
  status: 'REJECTED_INPUT'|'NEEDS_CONSENT'|'NEEDS_RECAPTURE'|'READY_FOR_PRIVATE_OCR';
  /** Eligibility for OCR only (may be true while pages remain missing). */
  canProcessInPrivateWorker: boolean;
  mayPublish: false;
  ownershipVerified: false;
  findings: readonly CaptureFinding[];
  actions: readonly CaptureAction[];
  /** Logical page numbers, not original PDF filenames or owner identity. */
  missingPages: readonly number[];
  qualityReviewPages: readonly number[];
}
const HASH=/^[0-9a-f]{64}$/;
const KINDS=new Set<MediaType>(['image/png','image/jpeg','image/tiff','application/pdf']);
const WARNINGS=new Set<QualityWarning>(['LOW_RESOLUTION','LOW_CONTRAST','POSSIBLE_BLUR','REVIEW_ORIENTATION']);
const integer=(n:unknown,max:number):n is number=>Number.isSafeInteger(n) && (n as number)>0 && (n as number)<=max;
export function preflightIndividualCapture(input: CapturePreflightInput): CapturePreflightResult {
  const findings:CaptureFinding[]=[];
  const actions:CaptureAction[]=[];
  const add=(v:CaptureFinding)=>{if(!findings.includes(v))findings.push(v);};
  const action=(v:CaptureAction)=>{if(!actions.includes(v))actions.push(v);};
  if(input.claimantKind!=='PERSON')add('INDIVIDUALS_ONLY');
  if(input.consentGiven!==true){add('CONSENT_REQUIRED');action('PROVIDE_CONSENT');}
  if(!Array.isArray(input.pages)||!input.pages.length)add('NO_DOCUMENT_PAGES');
  if(Array.isArray(input.pages)&&input.pages.length>8)add('TOO_MANY_PAGES');
  const seenLogical=new Set<number>();
  const seenPhysical=new Set<string>();
  const qualityReviewPages=new Set<number>();
  for(const pg of Array.isArray(input.pages)?input.pages.slice(0,9):[]){
    if(!pg || !integer(pg.logicalPage,8) || !integer(pg.filePage,8)){
      add('INVALID_PAGE_METADATA');continue;
    }
    if(seenLogical.has(pg.logicalPage))add('PAGE_NUMBER_DUPLICATE');
    seenLogical.add(pg.logicalPage);
    if(typeof pg.fileSha256!=='string'||!HASH.test(pg.fileSha256)||
      !KINDS.has(pg.mime)||!integer(pg.sizeBytes,12*1024*1024)||
      (pg.mime!=='application/pdf'&&pg.filePage!==1)){
      add('INVALID_FILE_METADATA');continue;
    }
    const exactCapture=pg.fileSha256+':'+String(pg.filePage);
    if(seenPhysical.has(exactCapture)){add('DUPLICATE_CAPTURE');action('RETAKE_DUPLICATED_PAGE');}
    seenPhysical.add(exactCapture);
    if(!Array.isArray(pg.qualityWarnings)||pg.qualityWarnings.length>4||
       pg.qualityWarnings.some(q=>!WARNINGS.has(q))) {add('INVALID_PAGE_METADATA');continue;}
    if(pg.qualityWarnings.length){
      qualityReviewPages.add(pg.logicalPage);
      add('CAPTURE_QUALITY_REVIEW');action('RETAKE_LOW_QUALITY_PAGE');
    }
  }
  if(input.printedTotalPages===null){add('PRINTED_PAGE_COUNT_UNKNOWN');action('CONFIRM_PAGE_NUMBERING');}
  else if(!integer(input.printedTotalPages,8)){add('PRINTED_PAGE_COUNT_INVALID');action('CONFIRM_PAGE_NUMBERING');}
  const max=integer(input.printedTotalPages,8)?input.printedTotalPages:0;
  const missingPages=max?Array.from({length:max},(_,i)=>i+1).filter(p=>!seenLogical.has(p)):[];
  if(missingPages.length){add('DOCUMENT_PAGES_MISSING');action('SEND_FULL_DOCUMENT');}
  if(seenLogical.size>0 && Array.from(seenLogical).some(pg=>pg>8||(max>0&&pg>max))){
    add('PAGE_NUMBER_GAP');action('CONFIRM_PAGE_NUMBERING');
  }
  if(max===0 && seenLogical.size>0 && Array.from({length:Math.max(...seenLogical)},(_,i)=>i+1).some(p=>!seenLogical.has(p))){
    add('PAGE_NUMBER_GAP');action('CONFIRM_PAGE_NUMBERING');
  }
  // A full, consistent capture remains UNVERIFIED; the backend must perform
  // malware scanning, protected storage and CPF evidence checks separately.
  const invalid=findings.some(f=>[
    'INDIVIDUALS_ONLY','NO_DOCUMENT_PAGES','TOO_MANY_PAGES',
    'INVALID_PAGE_METADATA','INVALID_FILE_METADATA','PAGE_NUMBER_DUPLICATE','DUPLICATE_CAPTURE',
    'PAGE_NUMBER_GAP','PRINTED_PAGE_COUNT_INVALID'].includes(f));
  const status=invalid?'REJECTED_INPUT':!input.consentGiven?'NEEDS_CONSENT':
    findings.some(f=>['PRINTED_PAGE_COUNT_UNKNOWN','DOCUMENT_PAGES_MISSING','CAPTURE_QUALITY_REVIEW'].includes(f)) ?
      'NEEDS_RECAPTURE':'READY_FOR_PRIVATE_OCR';
  action('AUTHORIZED_REVIEW_REQUIRED');
  return {status,canProcessInPrivateWorker:!invalid && input.consentGiven===true,
    mayPublish:false,ownershipVerified:false,
    findings,actions,missingPages,qualityReviewPages:Array.from(qualityReviewPages).sort((a,b)=>a-b)};
}
