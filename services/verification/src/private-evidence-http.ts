/**
 * Unmounted HTTP adapter for Malak's private evidence service.
 * It MUST only be bound behind separately reviewed TLS, identity/session,
 * firewall and per-instance/routed rate limit controls; never mount on a public
 * port until deployment authorization. No document, token or personal data logging.
 * It exposes an OWNER-ONLY extracted field presentation (not raw OCR) on the
 * OCR route when configured. The service must NOT be mounted publicly until
 * genuine identity/session, consent and TLS isolation have been approved.
 */
import type {IncomingMessage, ServerResponse} from 'node:http';
import {randomUUID} from 'node:crypto';
import type {Authenticator, EvidenceKind, EvidenceUploadRequest, EvidenceUploadResult} from './evidence-upload.ts';
import type {ReviewRequest, ReviewInspectionResult} from './evidence-review.ts';
import type {PrivateOcrPresentation} from './ocr-presentation.ts';
import {PrivateOcrAccessDenied} from './seller-ocr-service.ts';
import {PrivatePairAccessDenied} from './private-pair-service.ts';
import type {PrivatePairReport,PrivatePairRequest} from './private-pair-service.ts';
import {OwnerCaseDenied} from './private-owner-case.ts';
import type {OwnerCaseResult} from './private-owner-case.ts';

const MAX_BYTES=12*1024*1024;
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const KEY=/^[A-Za-z0-9_-]{16,100}$/;
const TOKEN=/^Bearer ([A-Za-z0-9._~+\/-]{20,4096})$/;
const KINDS=new Set<EvidenceKind>(['IDENTITY','OWNERSHIP_CERTIFICATE','TITLE_EXTRACT','OTHER_LEGAL_PROOF']);
const UPLOAD=/^\/internal\/v1\/evidence\/([0-9a-f-]{36})\/upload$/i;
const OCR_UPLOAD=/^\/internal\/v1\/evidence\/([0-9a-f-]{36})\/upload-ocr$/i;
const PAIR=/^\/internal\/v1\/evidence\/([0-9a-f-]{36})\/compare-pair$/i;
const REVIEW=/^\/internal\/v1\/review\/([0-9a-f-]{36})\/([0-9a-f-]{36})\/inspect$/i;
const OWNER_CASE=/^\/internal\/v1\/owner\/cases$/;
const CONSENT_REVOKE=/^\/internal\/v1\/owner\/cases\/([0-9a-f-]{36})\/revoke-consent$/i;
const MIME=new Set(['application/pdf','image/png','image/jpeg','image/tiff']);
export type PrivateRoute='UPLOAD'|'INSPECT'|'OCR'|'PAIR'|'OWNER_CASE'|'CONSENT_REVOKE';
export type DenialReason='BAD_ROUTE'|'BAD_REQUEST'|'UNAUTHENTICATED'|'FORBIDDEN'|'RATE_LIMITED'|'FILE_REJECTED'|'REPLAY_CONFLICT'|'UPSTREAM_UNAVAILABLE';
export interface DenialAuditRecord {route:PrivateRoute|'UNKNOWN';reason:DenialReason;actorUserId:string|null;requestId:string;}
export interface PrivateEvidenceHttpDeps {
  auth:Authenticator;
  /** Must be atomic and distributed over every API instance. NO in-memory fallback in production. */
  limits:{consume(scope:{route:PrivateRoute|'UNKNOWN';remote:string}):Promise<boolean>};
  /** Append-only, parameterized DB audit. Failure blocks request. No raw URL/token/IP in audit. */
  audit:{recordDenied(event:DenialAuditRecord):Promise<void>};
  upload:(request:EvidenceUploadRequest)=>Promise<EvidenceUploadResult>;
  /** Private, server-only inspection. Must return status only; no plaintext. */
  inspect:(request:ReviewRequest)=>Promise<ReviewInspectionResult>;
  /** Private owner-only field DTO. Called AFTER a successful, scanned and sealed upload. */
  analyzeOwnerUpload?:(request:{bearerToken:string;caseId:string;evidenceId:string;bytes:Uint8Array;mime:'application/pdf'|'image/png'|'image/jpeg'|'image/tiff';declaredArea:string|null})=>Promise<PrivateOcrPresentation>;
  /** Explicitly injected, server-only owner comparison of already sealed evidence IDs. */
  analyzeOwnerPair?:(request:PrivatePairRequest)=>Promise<PrivatePairReport>;
  /** Trusted auth, transaction and consent service. No public listener is mounted. */
  provisionOwnerCase?:(bearerToken:string,input:unknown)=>Promise<OwnerCaseResult>;
  withdrawOwnerConsent?:(bearerToken:string,caseId:string)=>Promise<{revoked:true;ownershipVerified:false;identityVerified:false;mayPublish:false}>;
}

function json(res:ServerResponse,status:number,body:{ok:boolean;[key:string]:unknown}):void {
  if(res.writableEnded||res.destroyed)return;
  const encoded=Buffer.from(JSON.stringify(body),'utf8');
  res.writeHead(status,{
    'Content-Type':'application/json; charset=utf-8',
    'Content-Length':String(encoded.length),
    'Cache-Control':'no-store',
    'Pragma':'no-cache',
    'X-Content-Type-Options':'nosniff',
    'Content-Security-Policy':"default-src 'none'",
    'Referrer-Policy':'no-referrer',
    'Connection':'close',
  });
  res.end(encoded);
}
function single(req:IncomingMessage,name:string):string|null {
  const matches:string[]=[];
  for(let i=0;i<req.rawHeaders.length;i+=2) {
    if(req.rawHeaders[i]?.toLowerCase()===name)matches.push(req.rawHeaders[i+1]!);
  }
  return matches.length===1?matches[0]!:null;
}
function noDuplicate(req:IncomingMessage,name:string):boolean {
  let count=0;
  for(let i=0;i<req.rawHeaders.length;i+=2)if(req.rawHeaders[i]?.toLowerCase()===name)count++;
  return count<=1;
}
async function limitedBody(req:IncomingMessage,size:number):Promise<Uint8Array> {
  const chunks:Buffer[]=[];
  let length=0;
  try {
    for await (const piece of req) {
      if(!(piece instanceof Buffer))throw Error('BAD_BODY');
      length+=piece.length;
      if(length>MAX_BYTES||length>size) {
        // A hostile oversized chunk must also be cleared before fail-closed.
        piece.fill(0);
        throw Error('BODY_TOO_LARGE');
      }
      chunks.push(piece);
    }
    if(length!==size)throw Error('BODY_LENGTH_MISMATCH');
    return Buffer.concat(chunks,length);
  } finally {
    // Node's input chunks are separate buffers from Buffer.concat's copy.
    // Zeroize them on both success and malformed/aborted uploads.
    for(const chunk of chunks)chunk.fill(0);
  }
}

function reasonFromUpload(result:EvidenceUploadResult):{code:number;reason:DenialReason} {
  if(result.ok)return {code:200,reason:'BAD_REQUEST'};
  switch(result.code) {
    case 'AUTH_REQUIRED':return {code:401,reason:'UNAUTHENTICATED'};
    case 'CASE_UNAVAILABLE':case 'CONSENT_REQUIRED':return {code:403,reason:'FORBIDDEN'};
    case 'REPLAY_CONFLICT':return {code:409,reason:'REPLAY_CONFLICT'};
    case 'FILE_REJECTED':case 'UPLOAD_KEY_INVALID':return {code:422,reason:'FILE_REJECTED'};
    case 'FILE_UNSAFE':return {code:422,reason:'FILE_REJECTED'};
    default:return {code:503,reason:'UPSTREAM_UNAVAILABLE'};
  }
}
function reasonFromReview(result:ReviewInspectionResult):{code:number;reason:DenialReason} {
  if(result.ok)return {code:200,reason:'BAD_REQUEST'};
  if(result.code==='AUTH_REQUIRED')return {code:401,reason:'UNAUTHENTICATED'};
  if(result.code==='ACCESS_DENIED')return {code:403,reason:'FORBIDDEN'};
  return {code:503,reason:'UPSTREAM_UNAVAILABLE'};
}
/**
 * Receives authenticated bearer-only POST requests. Does not listen on a port.
 * Does not permit cookies, query strings, or client-asserted roles, owners or IDs.
 */
export function createPrivateEvidenceHttpHandler(deps:PrivateEvidenceHttpDeps) {
  return async (req:IncomingMessage,res:ServerResponse):Promise<void>=>{
    const requestId=randomUUID();let actor:string|null=null;
    const url=req.url??'';
    const upload=UPLOAD.exec(url),ocrUpload=OCR_UPLOAD.exec(url),review=REVIEW.exec(url),pair=PAIR.exec(url);
    const caseCreate=OWNER_CASE.test(url),consentRevoke=CONSENT_REVOKE.exec(url);
    const route:PrivateRoute|'UNKNOWN'=caseCreate?'OWNER_CASE':consentRevoke?'CONSENT_REVOKE':
      pair?'PAIR':ocrUpload?'OCR':upload?'UPLOAD':review?'INSPECT':'UNKNOWN';
    // Do not log/return raw URL, bearer token, file name or document identifiers.
    const reject=async(status:number,reason:DenialReason)=>{
      try {await deps.audit.recordDenied({route,reason,actorUserId:actor,requestId});}
      catch {json(res,503,{ok:false,code:'SERVICE_UNAVAILABLE'});return;}
      json(res,status,{ok:false,code:status===429?'RATE_LIMITED':status===401?'AUTH_REQUIRED':
        status===503?'SERVICE_UNAVAILABLE':status===413?'TOO_LARGE':'REQUEST_DENIED'});
    };
    try {
      const remote=req.socket.remoteAddress??'UNKNOWN';
      // Rate limit before authentication and even for malformed routes to bound brute force.
      let allow:boolean;
      try {allow=await deps.limits.consume({route,remote});}
      catch {await reject(503,'UPSTREAM_UNAVAILABLE');return;}
      if(allow!==true){await reject(429,'RATE_LIMITED');return;}
      if(req.method!=='POST'||route==='UNKNOWN') {await reject(404,'BAD_ROUTE');return;}
      if(req.headers.cookie!==undefined||req.headers.origin!==undefined||
         req.headers['x-forwarded-proto']!==undefined||
         !noDuplicate(req,'authorization')||!noDuplicate(req,'content-type')||
         !noDuplicate(req,'content-length')||!noDuplicate(req,'x-idempotency-key')||
         !noDuplicate(req,'x-evidence-kind')||!noDuplicate(req,'x-page-number')||
         !noDuplicate(req,'x-claimed-mime')||
         !noDuplicate(req,'x-declared-area-m2')||
         req.headers['transfer-encoding']){await reject(400,'BAD_REQUEST');return;}
      const authorization=single(req,'authorization')??'';
      const match=TOKEN.exec(authorization);
      if(!match){await reject(401,'UNAUTHENTICATED');return;}
      const bearerToken=match[1]!;
      try {
        const id=await deps.auth.authenticate(bearerToken);
        if(!id?.userId||!UUID.test(id.userId)){await reject(401,'UNAUTHENTICATED');return;}
        actor=id.userId;
      }catch {await reject(503,'UPSTREAM_UNAVAILABLE');return;}
      if(route==='OWNER_CASE'||route==='CONSENT_REVOKE') {
        // Bearer must resolve to a real server identity; the injected
        // service independently rechecks that identity before COMMIT.
        if(req.headers['x-evidence-kind']||req.headers['x-page-number']||
           req.headers['x-idempotency-key']||req.headers['x-claimed-mime']||
           req.headers['x-declared-area-m2']){
          await reject(400,'BAD_REQUEST');return;
        }
        if(route==='CONSENT_REVOKE'){
          if(!deps.withdrawOwnerConsent){await reject(503,'UPSTREAM_UNAVAILABLE');return;}
          const caseId=consentRevoke![1]!;
          if(!UUID.test(caseId)||single(req,'content-type')!==null||
             ![null,'0'].includes(single(req,'content-length'))){
            await reject(400,'BAD_REQUEST');return;
          }
          let revoked;
          try {revoked=await deps.withdrawOwnerConsent(bearerToken,caseId);}
          catch(e){
            if(e instanceof OwnerCaseDenied){
              const status=e.code==='AUTH_REQUIRED'?401:e.code==='INVALID_REQUEST'?400:403;
              await reject(status,status===401?'UNAUTHENTICATED':status===400?'BAD_REQUEST':'FORBIDDEN');
            }else await reject(503,'UPSTREAM_UNAVAILABLE');
            return;
          }
          if(revoked?.revoked!==true||revoked.ownershipVerified!==false||
             revoked.identityVerified!==false||revoked.mayPublish!==false){
            await reject(503,'UPSTREAM_UNAVAILABLE');return;
          }
          json(res,200,{ok:true,revoked:true,ownershipVerified:false,
            identityVerified:false,mayPublish:false});
          return;
        }
        if(!deps.provisionOwnerCase){await reject(503,'UPSTREAM_UNAVAILABLE');return;}
        const header=single(req,'content-length');
        const length=header&&/^[0-9]{1,4}$/.test(header)?Number(header):NaN;
        if(single(req,'content-type')!=='application/json'||
           !Number.isSafeInteger(length)||length<2||length>1536){
          await reject(400,'BAD_REQUEST');return;
        }
        let value:unknown,raw:Uint8Array;
        try {raw=await limitedBody(req,length);}
        catch{await reject(400,'BAD_REQUEST');return;}
        try {value=JSON.parse(Buffer.from(raw).toString('utf8'));}
        catch{await reject(400,'BAD_REQUEST');return;}
        finally{raw.fill(0);}
        let created:OwnerCaseResult;
        try {created=await deps.provisionOwnerCase(bearerToken,value);}
        catch(e) {
          if(e instanceof OwnerCaseDenied){
            const status=e.code==='AUTH_REQUIRED'?401:e.code==='INVALID_REQUEST'?400:
              e.code==='REPLAY_CONFLICT'?409:403;
            await reject(status,status===401?'UNAUTHENTICATED':status===400?'BAD_REQUEST':
              status===409?'REPLAY_CONFLICT':'FORBIDDEN');
          }else await reject(503,'UPSTREAM_UNAVAILABLE');
          return;
        }
        if(!created||!UUID.test(created.caseId)||created.consentRecorded!==true||
           created.ownershipVerified!==false||created.identityVerified!==false||
           created.mayPublish!==false||typeof created.reused!=='boolean'){
          await reject(503,'UPSTREAM_UNAVAILABLE');return;
        }
        json(res,created.reused?200:201,{ok:true,caseId:created.caseId,
          reused:created.reused,consentRecorded:true,ownershipVerified:false,
          identityVerified:false,mayPublish:false});
        return;
      }
      if(route==='INSPECT') {
        if(![null,'0'].includes(single(req,'content-length')) || single(req,'content-type')!==null ||
           req.headers['x-evidence-kind']||req.headers['x-page-number']||req.headers['x-idempotency-key']||req.headers['x-claimed-mime']||req.headers['x-declared-area-m2']) {
          await reject(400,'BAD_REQUEST');return;
        }
        const caseId=review![1]!,evidenceId=review![2]!;
        if(!UUID.test(caseId)||!UUID.test(evidenceId)){await reject(400,'BAD_REQUEST');return;}
        let result:ReviewInspectionResult;
        try {result=await deps.inspect({bearerToken,caseId,evidenceId});}
        catch {await reject(503,'UPSTREAM_UNAVAILABLE');return;}
        if(!result.ok){const response=reasonFromReview(result);await reject(response.code,response.reason);return;}
        // Never surface OCR, decrypted bytes, names, shares, title IDs or storage links.
        json(res,200,{ok:true,inspected:true,mayPublish:false,ownershipVerified:false});
        return;
      }
      if(route==='PAIR') {
        if(!deps.analyzeOwnerPair){await reject(503,'UPSTREAM_UNAVAILABLE');return;}
        if(req.headers['x-evidence-kind']||req.headers['x-page-number']||
           req.headers['x-idempotency-key']||req.headers['x-claimed-mime']||
           req.headers['x-declared-area-m2']||
           single(req,'content-type')!=='application/json'){
          await reject(400,'BAD_REQUEST');return;
        }
        const sizeHeader=single(req,'content-length');
        const size=sizeHeader&&/^[0-9]{1,4}$/.test(sizeHeader)?Number(sizeHeader):NaN;
        if(!Number.isSafeInteger(size)||size<2||size>1024){
          await reject(400,'BAD_REQUEST');return;
        }
        let data:unknown;
        let bytes:Uint8Array;
        try {bytes=await limitedBody(req,size);}
        catch {await reject(400,'FILE_REJECTED');return;}
        try {data=JSON.parse(Buffer.from(bytes).toString('utf8'));}
        catch {await reject(400,'BAD_REQUEST');return;}
        finally {bytes.fill(0);}
        if(!data||typeof data!=='object'||Array.isArray(data)||
           Object.keys(data).sort().join(',')!=='certificateEvidenceIds,identityEvidenceIds'){
          await reject(400,'BAD_REQUEST');return;
        }
        const {certificateEvidenceIds,identityEvidenceIds}=data as Record<string,unknown>;
        if(!Array.isArray(certificateEvidenceIds)||certificateEvidenceIds.length<1||
           certificateEvidenceIds.length>8||
           !Array.isArray(identityEvidenceIds)||identityEvidenceIds.length<1||
           identityEvidenceIds.length>2||
           [...certificateEvidenceIds,...identityEvidenceIds].some(id=>
             typeof id!=='string'||!UUID.test(id))||
           new Set([...certificateEvidenceIds,...identityEvidenceIds]).size!==
             certificateEvidenceIds.length+identityEvidenceIds.length){
          await reject(400,'BAD_REQUEST');return;
        }
        const caseId=pair![1]!;
        if(!UUID.test(caseId)){await reject(400,'BAD_REQUEST');return;}
        let report:PrivatePairReport;
        try {
          report=await deps.analyzeOwnerPair({bearerToken,caseId,
            certificateEvidenceIds,identityEvidenceIds});
        } catch(err) {
          await reject(err instanceof PrivatePairAccessDenied?403:503,
            err instanceof PrivatePairAccessDenied?'FORBIDDEN':'UPSTREAM_UNAVAILABLE');
          return;
        }
        if(report?.access!=='AUTHORIZED_CASE_OWNER_ONLY'||report?.ownershipVerified!==false||
           report?.identityVerified!==false||report?.mayPublish!==false||
           report?.comparison?.mayPublish!==false){
          await reject(503,'UPSTREAM_UNAVAILABLE');return;
        }
        json(res,200,{ok:true,report,mayPublish:false,ownershipVerified:false,identityVerified:false});
        return;
      }
      const caseId=(ocrUpload??upload)![1]!;
      if(route==='OCR'&&!deps.analyzeOwnerUpload){await reject(503,'UPSTREAM_UNAVAILABLE');return;}
      const declared=single(req,'x-declared-area-m2');
      if(declared!==null && !/^[0-9]{1,10}(?:\.[0-9]{1,3})?$/.test(declared)){
        await reject(400,'BAD_REQUEST');return;
      }
      const headerSize=single(req,'content-length'),kind=single(req,'x-evidence-kind');
      const idempotencyKey=single(req,'x-idempotency-key');
      const mime=single(req,'x-claimed-mime');
      const page=single(req,'x-page-number');
      const len=(headerSize&&/^[0-9]{1,8}$/.test(headerSize))?Number(headerSize):NaN;
      if(!UUID.test(caseId)||!idempotencyKey||!KEY.test(idempotencyKey)||
         !kind||!KINDS.has(kind as EvidenceKind)||
         !mime||!MIME.has(mime)||page===null||
         !/^(?:[1-8]|null)$/.test(page)||
         !Number.isSafeInteger(len)||len<20){await reject(400,'BAD_REQUEST');return;}
      if(len>MAX_BYTES){await reject(413,'FILE_REJECTED');return;}
      if(single(req,'content-type')!=='application/octet-stream'){await reject(415,'BAD_REQUEST');return;}
      let bytes:Uint8Array;
      try {bytes=await limitedBody(req,len);}
      catch {await reject(400,'FILE_REJECTED');return;}
      try {
        let result:EvidenceUploadResult;
        try {result=await deps.upload({bearerToken,caseId,idempotencyKey,bytes,
          evidenceKind:kind as EvidenceKind,pageNumber:page==='null'?null:Number(page),claimedMime:mime});}
        catch {await reject(503,'UPSTREAM_UNAVAILABLE');return;}
        if(!result.ok){const response=reasonFromUpload(result);await reject(response.code,response.reason);return;}
        if(route==='OCR') {
          // The caller already owns the newly uploaded evidence. The analyzer
          // MUST additionally check current owner membership and consent after
          // OCR, so revoked consent cannot release extracted personal fields.
          try {
            const reading=await deps.analyzeOwnerUpload!({bearerToken,caseId,
              evidenceId:result.evidenceId,bytes,mime:mime as
              'application/pdf'|'image/png'|'image/jpeg'|'image/tiff',declaredArea:declared});
            json(res,200,{ok:true,evidenceId:result.evidenceId,uploaded:result.uploaded,
              ocrStatus:'READY',reading,mayPublish:false,ownershipVerified:false});
          } catch (err) {
            if(err instanceof PrivateOcrAccessDenied){await reject(403,'FORBIDDEN');return;}
            // Document IS stored; OCR can be retried with same idempotency key.
            // Do not accidentally tell the customer their upload was lost.
            json(res,202,{ok:true,evidenceId:result.evidenceId,uploaded:result.uploaded,
              ocrStatus:'UNAVAILABLE',mayPublish:false,ownershipVerified:false});
          }
          return;
        }
        json(res,200,{ok:true,evidenceId:result.evidenceId,uploaded:result.uploaded,
          mayPublish:false,ownershipVerified:false});
      } finally {bytes.fill(0); }
    } catch {
      await reject(503,'UPSTREAM_UNAVAILABLE');
    }
  };
}
