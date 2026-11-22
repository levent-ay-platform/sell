import {createHash,timingSafeEqual} from 'node:crypto';
import type {IncomingMessage,ServerResponse} from 'node:http';
import type {Authenticator} from './evidence-upload.ts';
import type {ParcelCandidate,ParcelDecision,ParcelRepository} from './parcel-repository.ts';
import type {readAtlasContributionLayer} from './atlas-parcel-exchange.ts';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const maxBytes = 16 * 1024 * 1024;
const maxBody = 32 * 1024;
type JsonObject = Record<string,unknown>;

export type ReviewedParcelRenditionReader =
  (storageObjectId:string, maxBytes:number) => Promise<Uint8Array|null>;

export interface ParcelHttpOptions {
  repository:ParcelRepository;
  readRendition?:ReviewedParcelRenditionReader;
  atlas?:{readerToken:string; read:()=>ReturnType<typeof readAtlasContributionLayer>};
  pilotParcelToken?:string;
  authenticate:Authenticator['authenticate'];
  allowRequest:(remoteAddress:string)=>Promise<boolean>;
}
class BadRequest extends Error {}
class LargeRequest extends Error {}

function headers(res:ServerResponse) {
  res.setHeader('cache-control','no-store');
  res.setHeader('x-content-type-options','nosniff');
  res.setHeader('content-security-policy',"default-src 'none'; sandbox");
  res.setHeader('referrer-policy','no-referrer');
}
function json(res:ServerResponse,status:number,data:object) {
  headers(res);
  res.setHeader('content-type','application/json; charset=utf-8');
  res.writeHead(status).end(JSON.stringify(data));
}

function record(value:unknown):JsonObject {
  if (!value || typeof value!=='object' || Array.isArray(value)) throw new BadRequest();
  return value as JsonObject;
}

async function readBody(req:IncomingMessage):Promise<JsonObject> {
  if (!/^application\/json(?:;\s*charset=utf-8)?$/i.test(req.headers['content-type']??'')) throw new BadRequest();
  if (Number(req.headers['content-length']??0)>maxBody) throw new LargeRequest();
  const chunks:Buffer[]=[];
  let size=0;
  // Do not destroy the socket before the handler can return 413.
  for await (const incoming of req.iterator({destroyOnReturn:false})) {
    const chunk:Buffer = incoming;
    size+=chunk.length;
    if (size>maxBody) throw new LargeRequest();
    chunks.push(chunk);
  }
  let data:unknown;
  try {data=JSON.parse(Buffer.concat(chunks).toString('utf8'));}
  catch {throw new BadRequest();}
  return record(data);
}

function pick<T extends string>(raw:unknown,allowed:readonly T[]):T {
  const parsed=allowed.find(value=>value===raw);
  if (!parsed) throw new BadRequest();
  return parsed;
}
function optionalId(raw:unknown):string|null {
  if (raw===null || raw===undefined) return null;
  if (typeof raw!=='string' || !uuid.test(raw)) throw new BadRequest();
  return raw;
}
function reference(raw:unknown):string|null {
  if (raw===null || raw===undefined) return null;
  if (typeof raw!=='string' || !raw.trim() || raw.length>100 || /\p{Cc}/u.test(raw)) throw new BadRequest();
  return raw.trim();
}
function positiveInt(raw:unknown,max:number):number {
  if (typeof raw!=='number' || !Number.isInteger(raw) || raw<1 || raw>max) throw new BadRequest();
  return raw;
}
function exactKeys(raw:JsonObject,allowed:readonly string[]) {
  if (Object.keys(raw).some(key=>!allowed.includes(key))) throw new BadRequest();
}
function validNumber(raw:unknown,min:number,max:number):raw is number {
  return typeof raw==='number' && Number.isFinite(raw) && raw>=min && raw<=max;
}
function points(raw:unknown,local:boolean):[number,number][] {
  if (!Array.isArray(raw) || raw.length<3 || raw.length>129) throw new BadRequest();
  const result:[number,number][] = raw.map(point=>{
    const minX=local ? 0 : -180;
    const maxX=local ? 1_000_000 : 180;
    const minY=local ? 0 : -85.051129;
    const maxY=local ? 1_000_000 : 85.051129;
    if (!Array.isArray(point) || point.length!==2 ||
      !validNumber(point[0],minX,maxX) || !validNumber(point[1],minY,maxY)) throw new BadRequest();
    return [point[0],point[1]];
  });
  const first=result[0];
  const last=result.at(-1)!;
  if (first[0]===last[0] && first[1]===last[1]) result.pop();
  if (result.length<3 || result.length>128 || new Set(result.map(point=>point.join(','))).size!==result.length)
    throw new BadRequest();
  return result;
}
function readEdges(raw:unknown,numberOfEdges:number,localPage:number):ParcelCandidate['edges'] {
  if (raw===undefined) return [];
  if (!Array.isArray(raw) || raw.length>numberOfEdges) throw new BadRequest();
  const parsed=raw.map(value=>{
    const edge=record(value);
    exactKeys(edge,['ordinal','lengthM','sourcePage','label']);
    const ordinal=positiveInt(edge.ordinal,numberOfEdges);
    const sourcePage=positiveInt(edge.sourcePage,8);
    const lengthM=typeof edge.lengthM==='number' ? String(edge.lengthM) : edge.lengthM;
    if (sourcePage!==localPage || typeof lengthM!=='string' ||
      !/^(?:0|[1-9]\d{0,6})(?:\.\d{1,6})?$/.test(lengthM) || Number(lengthM)<=0) throw new BadRequest();
    const label=edge.label??null;
    if (label!==null && (typeof label!=='string'|| !/^[A-Za-z0-9]{1,5}(?:[-–][A-Za-z0-9]{1,5})?$/.test(label))) {
      throw new BadRequest();
    }
    return {ordinal,lengthM,sourcePage,label};
  });
  if (new Set(parsed.map(edge=>edge.ordinal)).size!==parsed.length) throw new BadRequest();
  return parsed;
}
function parseCandidate(raw:JsonObject):ParcelCandidate {
  exactKeys(raw,['parcelId','sourceEvidenceId','method','vertices','titleReference','plotReference','localPage','localUnit','edges','statedAreaM2']);
  const method=pick(raw.method,['DOCUMENT_ONLY','OWNER_TRACED_ESTIMATE'] as const);
  const local=method==='DOCUMENT_ONLY';
  const vertices=points(raw.vertices,local);
  const id=optionalId(raw.sourceEvidenceId);
  if ((local&&!id) || (!local&&(raw.localPage!==undefined||raw.localUnit!==undefined||raw.edges!==undefined))) throw new BadRequest();
  const localPage=local ? positiveInt(raw.localPage,8) : null;
  const localUnit=local ? pick(raw.localUnit,['PIXEL','PAGE_POINT','LOCAL_METRE'] as const) : null;
  const edges=localPage===null ? [] : readEdges(raw.edges,vertices.length,localPage);
  const statedAreaM2=raw.statedAreaM2??null;
  if (statedAreaM2!==null && (!local || typeof statedAreaM2!=='string' ||
    !/^(?:0|[1-9]\d{0,6})(?:\.\d{1,6})?$/.test(statedAreaM2) || Number(statedAreaM2)<=0)) throw new BadRequest();
  return {
    parcelId:optionalId(raw.parcelId),sourceEvidenceId:id,method,
    titleReference:reference(raw.titleReference),plotReference:reference(raw.plotReference),
    geometryJson:JSON.stringify({type:'Polygon',coordinates:[[...vertices,vertices[0]]]}),
    localPage,localUnit,edges,statedAreaM2,
  };
}
function parseDecision(role:ParcelDecision['role'],subject:ParcelDecision['subject'],data:JsonObject):ParcelDecision {
  if (role==='owner') {
    exactKeys(data,['decision']);
    return {role,subject,decision:pick(data.decision,['GRANTED','REVOKED'] as const)};
  }
  if (subject==='geometry') {
    exactKeys(data,['decision','includeReferences']);
    if (data.includeReferences!==undefined && typeof data.includeReferences!=='boolean') throw new BadRequest();
    return {role,subject,decision:pick(data.decision,['APPROVED','CHANGES_REQUESTED','REJECTED','REVOKED'] as const),
      includeReferences:data.includeReferences===true};
  }
  exactKeys(data,['decision','redactionChecked','rightsCleared']);
  const decision=pick(data.decision,['APPROVED','REDACTION_REQUIRED','PRIVATE_ONLY','REVOKED'] as const);
  if (decision==='APPROVED' && !(data.redactionChecked===true && data.rightsCleared===true)) throw new BadRequest();
  return {role,subject,decision,redactionChecked:data.redactionChecked===true,rightsCleared:data.rightsCleared===true};
}
function validRendition(mime:string,buffer:Buffer):boolean {
  switch (mime) {
    case 'application/pdf': return buffer.subarray(0,5).toString('ascii')==='%PDF-';
    case 'image/png': return buffer.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex'));
    case 'image/jpeg': return buffer.subarray(0,3).equals(Buffer.from('ffd8ff','hex'));
    default: return false;
  }
}

function serviceBearer(req:IncomingMessage,expected:string):boolean {
  const bearer=/^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization??'')?.[1];
  return bearer!==undefined && timingSafeEqual(Buffer.from(bearer),Buffer.from(expected));
}

function documentHashes(value:unknown):string[] {
  if (!Array.isArray(value) || value.length<1 || value.length>8 ||
      !value.every(hash=>typeof hash==='string' && /^[a-f0-9]{64}$/.test(hash))) throw new BadRequest();
  return value;
}

function projectListing(projection:NonNullable<Awaited<ReturnType<ParcelRepository['listing']>>>,
  listingId:string,includeDocuments:boolean) {
  return {
    boundaries:projection.boundaries,
    verifiedParcel:projection.verifiedParcel===true,
    documents:includeDocuments ? projection.documents.map(({id,kind,mime})=>({
      id,kind,mime,url:`/api/v1/listings/${listingId}/documents/${id}`,
    })) : [],
  };
}

const listingRoute=/^\/api\/v1\/listings\/([^/]+)\/(parcel|documents\/([^/]+))$/;
const candidateRoute=/^\/api\/v1\/(owner|reviewer)\/cases\/([^/]+)\/parcel-geometries$/;
const decisionRoute=/^\/api\/v1\/(owner|reviewer)\/cases\/([^/]+)\/(parcel-geometries|parcel-documents)\/([^/]+)\/(consent|review)$/;

export function createParcelHttpHandler(options:ParcelHttpOptions) {
  const {repository}=options;
  const atlas=options.atlas;
  const pilotToken=options.pilotParcelToken;
  const tokenPattern=/^[A-Za-z0-9_-]{43}$/;
  if ((atlas && !tokenPattern.test(atlas.readerToken)) ||
      (pilotToken!==undefined && (!tokenPattern.test(pilotToken) || pilotToken===atlas?.readerToken)))
    throw new Error('INVALID_ATLAS_READER_CONFIGURATION');
  return async (req:IncomingMessage,res:ServerResponse):Promise<boolean> => {
    const path=req.url?.split('?',1)[0]??'';
    if (path==='/api/v1/atlas/contributions' && atlas) {
      try {
        if (req.method!=='GET') {
          res.setHeader('allow','GET');
          json(res,405,{code:'METHOD_NOT_ALLOWED'});
        } else if (req.url!==path) json(res,400,{code:'INVALID_ATLAS_QUERY'});
        else if (await options.allowRequest(req.socket.remoteAddress??'UNKNOWN')!==true)
          json(res,429,{code:'RATE_LIMITED'});
        else {
          if (!serviceBearer(req,atlas.readerToken))
            json(res,401,{code:'ATLAS_READER_REQUIRED'});
          else json(res,200,await atlas.read());
        }
      } catch {
        json(res,503,{code:'ATLAS_LAYER_UNAVAILABLE'});
      }
      return true;
    }
    if (path==='/api/v1/atlas/pilot-parcel' && pilotToken!==undefined) {
      try {
        if (req.method!=='POST') {
          res.setHeader('allow','POST');
          json(res,405,{code:'METHOD_NOT_ALLOWED'});
        } else if (req.url!==path) json(res,400,{code:'INVALID_ATLAS_QUERY'});
        else if (await options.allowRequest(req.socket.remoteAddress??'UNKNOWN')!==true)
          json(res,429,{code:'RATE_LIMITED'});
        else if (!serviceBearer(req,pilotToken)) json(res,401,{code:'PILOT_PARCEL_READER_REQUIRED'});
        else {
          const data=await readBody(req);
          exactKeys(data,['listingId','identityHashes','documentHashes','latitude','longitude','titleReference','plotReference']);
          const listingId=optionalId(data.listingId),titleReference=reference(data.titleReference),plotReference=reference(data.plotReference);
          if (!listingId || !titleReference || !plotReference ||
            !validNumber(data.latitude,-85,85) || !validNumber(data.longitude,-180,180)) throw new BadRequest();
          const projection=await repository.listing(listingId,{
            identityHashes:documentHashes(data.identityHashes),documentHashes:documentHashes(data.documentHashes),
            latitude:data.latitude,longitude:data.longitude,titleReference:titleReference.toUpperCase(),
            plotReference:plotReference.toUpperCase(),
          });
          if (!projection?.verifiedParcel || !projection.boundaries.length) json(res,404,{code:'PARCEL_MATCH_UNAVAILABLE'});
          else json(res,200,projectListing(projection,listingId,options.readRendition!==undefined));
        }
      } catch (error) {
        if (error instanceof LargeRequest) json(res,413,{code:'REQUEST_TOO_LARGE'});
        else if (error instanceof BadRequest) json(res,400,{code:'INVALID_PARCEL_MATCH'});
        else json(res,503,{code:'PARCEL_MATCH_UNAVAILABLE'});
      }
      return true;
    }
    const listing=listingRoute.exec(path);
    const candidateRouteMatch=candidateRoute.exec(path);
    const decision=decisionRoute.exec(path);
    if (!listing && !candidateRouteMatch && !decision) return false;
    const allow=listing || candidateRouteMatch?.[1]==='reviewer' ? ['GET'] : candidateRouteMatch ? ['GET','POST'] : ['POST'];
    const miss={code:'PARCEL_NOT_AVAILABLE'};

    try {
      if (!allow.includes(req.method??'')) {
        res.setHeader('allow',allow.join(', '));
        json(res,405,{code:'METHOD_NOT_ALLOWED'});
        return true;
      }
      if (await options.allowRequest(req.socket.remoteAddress??'UNKNOWN')!==true) {
        json(res,429,{code:'RATE_LIMITED'});
        return true;
      }
      if (req.url!==path || path.length>512) throw new BadRequest();

      if (listing) {
        const [,listingId,,documentId]=listing;
        if (!uuid.test(listingId) || (documentId && !uuid.test(documentId))) {
          json(res,404,miss);
        } else if (documentId) {
          const candidate=await repository.approvedDocument(listingId,documentId);
          if (!candidate || !options.readRendition) {
            json(res,404,miss);
            return true;
          }
          const bytes=await options.readRendition(candidate.storageObjectId,maxBytes);
          if (!bytes || !bytes.length || bytes.length>maxBytes) throw new Error('RENDITION_UNAVAILABLE');
          // Detach from caller-owned memory before checking and writing bytes.
          const file=Buffer.from(bytes);
          const sha256=createHash('sha256').update(file).digest('hex');
          if (sha256!==candidate.sha256 || !validRendition(candidate.mime,file)) throw new Error('RENDITION_MISMATCH');
          // A withdrawal while object storage was loading must not return a file.
          const current=await repository.approvedDocument(listingId,documentId);
          if (!current || current.storageObjectId!==candidate.storageObjectId ||
            current.sha256!==candidate.sha256 || current.mime!==candidate.mime) {
            json(res,404,miss);
            return true;
          }
          headers(res);
          res.setHeader('content-type',current.mime);
          res.setHeader('content-disposition','inline');
          res.setHeader('content-length',file.length);
          res.writeHead(200).end(file);
        } else {
          const projection=await repository.listing(listingId);
          if (!projection) json(res,404,miss);
          else json(res,200,projectListing(projection,listingId,options.readRendition!==undefined));
        }
        return true;
      }

      const bearer=/^Bearer ([A-Za-z0-9._~+\/-]{20,4096})$/.exec(req.headers.authorization??'');
      const actor=bearer ? await options.authenticate(bearer[1]) : null;
      if (!actor || actor.pilotUnverified || !uuid.test(actor.userId)) {
        json(res,401,{code:'AUTHENTICATION_REQUIRED'});
        return true;
      }
      if (candidateRouteMatch) {
        const role=candidateRouteMatch[1]==='reviewer'?'reviewer':'owner';
        const caseId=optionalId(candidateRouteMatch[2])!;
        if (req.method==='GET') {
          const data=await repository.ownerCase(caseId,actor.userId,role);
          if (data) json(res,200,data);
          else json(res,404,miss);
        } else {
          const candidate=parseCandidate(await readBody(req));
          const id=await repository.saveOwnerGeometry(caseId,actor.userId,candidate);
          if (id) json(res,201,{id});
          else json(res,404,miss);
        }
      } else if (decision) {
        const [,role,rawCaseId,type,rawId,action]=decision;
        if ((role==='owner')!==(action==='consent')) {
          json(res,404,miss);
          return true;
        }
        const caseId=optionalId(rawCaseId)!;
        const id=optionalId(rawId)!;
        const data=parseDecision(role==='owner'?'owner':'reviewer',
          type==='parcel-geometries'?'geometry':'document',await readBody(req));
        const recorded=await repository.recordDecision(caseId,actor.userId,id,data);
        if (recorded) json(res,201,{ok:true});
        else json(res,404,miss);
      }
    } catch (error) {
      const sqlstate=(error as {code?:string}|null)?.code;
      if (error instanceof LargeRequest) json(res,413,{code:'PARCEL_BODY_TOO_LARGE'});
      else if (error instanceof BadRequest || sqlstate==='22023' || sqlstate==='23514' || sqlstate==='23503') {
        json(res,400,{code:'INVALID_PARCEL_REQUEST'});
      } else if (sqlstate==='42501') json(res,404,miss);
      else json(res,503,{code:'PARCEL_SERVICE_UNAVAILABLE'});
    }
    return true;
  };
}
