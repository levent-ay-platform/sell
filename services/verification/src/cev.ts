/**
 * TunTrust CEV 2D-doc adapter: accepts signed QR text, NEVER a PDF or identity scan.
 * Keep server-side and do not log payloads. Valid signature != current ownership.
 */
export const TUNTRUST_CEV_HTTPS_URL =
  'https://checkcev.tuntrust.tn/TuntrustCev/rest/validService/validateDoc';

export interface CevResponseField { id?: string; label?: string; value?: string; type?: string; }
export interface CevServiceResponse {
  result?: boolean; verifCertif?: boolean; verifCertifOr?: boolean;
  errorId?: string | number; error?: string;
  signer?: CevResponseField; dateSign?: CevResponseField;
  dateCert?: CevResponseField; document?: CevResponseField;
  docstyle?: CevResponseField; titre1?: CevResponseField;
  titre2?: CevResponseField; details?: CevResponseField[];
}
export type CevOutcome =
  | {status:'SIGNATURE_VALID_CONTENT_NOT_YET_MATCHED';issuer:string;signedFields:readonly CevResponseField[];documentType?:string;signedAt?:string}
  | {status:'INVALID_OR_UNTRUSTED';code:string}
  | {status:'SERVICE_UNAVAILABLE' | 'RESPONSE_UNRECOGNIZED'};
export interface CevVerifierOptions {
  fetchImpl?: typeof fetch;
  /** Exact official CPF signer values confirmed by integration partner; EMPTY fails closed. */
  trustedIssuerNames: readonly string[];
  timeoutMs?: number;
}
function isField(v:unknown):v is CevResponseField {
  return typeof v === 'object' && v !== null && !Array.isArray(v) &&
    typeof (v as CevResponseField).value === 'string';
}
export async function verifyTunTrustCev(cevValue:string,options:CevVerifierOptions):Promise<CevOutcome> {
  if (typeof cevValue !== 'string' || cevValue.length < 24 ||
      cevValue.length > 16000 || !cevValue.startsWith('DC'))
    return {status:'INVALID_OR_UNTRUSTED',code:'INVALID_INPUT'};
  const timeout=Math.max(1000,Math.min(options.timeoutMs ?? 6000,15000));
  let response:Response;
  try {
    response=await (options.fetchImpl ?? fetch)(TUNTRUST_CEV_HTTPS_URL,{
      method:'POST',headers:{'content-type':'application/json',accept:'application/json'},
      body:JSON.stringify({value:cevValue}),signal:AbortSignal.timeout(timeout),redirect:'error'
    });
  } catch { return {status:'SERVICE_UNAVAILABLE'}; }
  if (!response.ok || !(response.headers.get('content-type')??'').toLowerCase().includes('application/json'))
    return {status:'SERVICE_UNAVAILABLE'};
  const len=Number(response.headers.get('content-length')??'0');
  if (len>65536) return {status:'RESPONSE_UNRECOGNIZED'};
  let raw:unknown;
  try {
    const content=await response.text();
    if (content.length>65536) return {status:'RESPONSE_UNRECOGNIZED'};
    raw=JSON.parse(content);
  } catch { return {status:'RESPONSE_UNRECOGNIZED'}; }
  if (typeof raw!=='object' || raw===null || Array.isArray(raw)) return {status:'RESPONSE_UNRECOGNIZED'};
  const data=raw as CevServiceResponse;
  if (data.result!==true || data.verifCertif!==true) {
    if (data.result===false || data.verifCertif===false)
      return {status:'INVALID_OR_UNTRUSTED',code:String(data.errorId??'CEV_REJECTED').slice(0,16)};
    return {status:'RESPONSE_UNRECOGNIZED'};
  }
  const issuer=isField(data.signer)?data.signer.value!:'';
  if (!options.trustedIssuerNames.includes(issuer))
    return {status:'INVALID_OR_UNTRUSTED',code:'UNRECOGNIZED_ISSUER'};
  if (!Array.isArray(data.details) || !data.details.every(isField))
    return {status:'RESPONSE_UNRECOGNIZED'};
  return {status:'SIGNATURE_VALID_CONTENT_NOT_YET_MATCHED',issuer,
    signedFields:data.details.map(x=>({id:x.id,label:x.label,value:x.value,type:x.type})),
    ...(isField(data.document)?{documentType:data.document.value}:{}),
    ...(isField(data.dateSign)?{signedAt:data.dateSign.value}:{})};
}
