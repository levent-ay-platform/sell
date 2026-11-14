/**
 * Server-only HashiCorp Vault Transit envelope keys for Malak individual evidence.
 * IMPORTANT: This is NOT an HTTP endpoint and never permits user-supplied Vault
 * URLs or tokens. Configure a derived, non-exportable key with operator ACLs.
 * The plaintext DEK exists briefly in the trusted server process; never log it.
 */
import {Buffer} from 'node:buffer';

export interface EvidenceKeyScope {readonly caseId:string;readonly evidenceId:string;}
export interface TransitKey {readonly plaintextKey:Uint8Array;readonly wrappedKey:string;readonly keyVersion:string;}
export interface WrappedEvidenceKey extends EvidenceKeyScope {readonly wrappedKey:string;readonly keyVersion:string;}
export interface VaultTransitConfig {
  readonly baseUrl:string;
  readonly keyName:string;
  readonly token:()=>Promise<string>;
  readonly timeoutMs?:number;
}
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const KEY_NAME=/^[a-zA-Z][a-zA-Z0-9_-]{2,90}$/;
const WRAPPED=/^vault:v([1-9][0-9]*):([a-zA-Z0-9+/]+={0,2})$/;
const BASE64=/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
const MAX_REPLY=24*1024;

function scopeContext(scope:EvidenceKeyScope):string {
  if(!scope || !UUID.test(scope.caseId)||!UUID.test(scope.evidenceId))throw new Error('VAULT_SCOPE_INVALID');
  return Buffer.from(`malak-evidence-v1:${scope.caseId.toLowerCase()}:${scope.evidenceId.toLowerCase()}`).toString('base64');
}
function decode32(s:unknown):Uint8Array {
  if(typeof s!=='string'||s.length!==44||!BASE64.test(s))throw new Error('VAULT_KEY_RESPONSE_INVALID');
  const buf=Buffer.from(s,'base64');
  if(buf.length!==32||buf.toString('base64')!==s) {buf.fill(0);throw new Error('VAULT_KEY_RESPONSE_INVALID');}
  try{return Uint8Array.from(buf);}finally{buf.fill(0);}
}
function versionOf(cipher:unknown):string {
  if(typeof cipher!=='string'||cipher.length>4096)throw new Error('VAULT_KEY_RESPONSE_INVALID');
  const match=WRAPPED.exec(cipher);
  if(!match || match[2]!.length<20 || match[2]!.length>4000)
    throw new Error('VAULT_KEY_RESPONSE_INVALID');
  return `v${match[1]}`;
}

export class VaultTransitDataKeys {
  private readonly url:URL;
  private readonly keyName:string;
  private readonly tokenProvider:()=>Promise<string>;
  private readonly timeoutMs:number;
  constructor(config:VaultTransitConfig) {
    const u=new URL(config.baseUrl);
    if(u.username||u.password||u.search||u.hash||u.pathname!=='/' ||
       !((u.protocol==='https:') ||
         (u.protocol==='http:' && (u.hostname==='127.0.0.1'||u.hostname==='[::1]'))))
      throw new Error('VAULT_PRIVATE_TLS_OR_LOOPBACK_REQUIRED');
    if(!KEY_NAME.test(config.keyName)||typeof config.token!=='function')throw new Error('VAULT_CONFIG_INVALID');
    const timeout=config.timeoutMs??5000;
    if(!Number.isInteger(timeout)||timeout<1000||timeout>15000)throw new Error('VAULT_TIMEOUT_INVALID');
    this.url=u;this.keyName=config.keyName;this.tokenProvider=config.token;this.timeoutMs=timeout;
  }
  private async post(path:string,request:Record<string,unknown>):Promise<any> {
    const token=await this.tokenProvider();
    if(typeof token!=='string'||token.length<12||token.length>4096||/[\r\n\x00-\x1f]/.test(token))
      throw new Error('VAULT_CREDENTIAL_UNAVAILABLE');
    let response:Response;
    try {
      response=await fetch(new URL(path,this.url),{
        method:'POST',headers:{'X-Vault-Token':token,'Content-Type':'application/json','Accept':'application/json'},
        body:JSON.stringify(request),redirect:'error',cache:'no-store',signal:AbortSignal.timeout(this.timeoutMs)
      });
    }catch{throw new Error('VAULT_TRANSIT_UNAVAILABLE');}
    if(response.status!==200) {try{await response.body?.cancel();}catch{}throw new Error('VAULT_TRANSIT_DENIED');}
    const reader=response.body?.getReader();
    if(!reader)throw new Error('VAULT_TRANSIT_UNAVAILABLE');
    const parts:Uint8Array[]=[];let count=0;
    try {
      while(true){
        const v=await reader.read();if(v.done)break;
        count+=v.value.byteLength;
        if(count>MAX_REPLY)throw new Error('VAULT_RESPONSE_TOO_LARGE');
        parts.push(v.value);
      }
    }catch{try{await reader.cancel();}catch{}throw new Error('VAULT_TRANSIT_UNAVAILABLE');}
    const body=Buffer.concat(parts);
    try{
      const json=JSON.parse(body.toString('utf8'));
      if(!json||typeof json!=='object'||!json.data||typeof json.data!=='object')
        throw new Error('VAULT_KEY_RESPONSE_INVALID');
      return json.data;
    }catch{throw new Error('VAULT_KEY_RESPONSE_INVALID');}finally{body.fill(0);}
  }
  async generateDataKey(scope:EvidenceKeyScope):Promise<TransitKey> {
    const context=scopeContext(scope);
    const data=await this.post(`/v1/transit/datakey/plaintext/${this.keyName}`,{context,bits:256});
    const keyVersion=versionOf(data.ciphertext);
    if(data.key_version!==undefined && Number(data.key_version)!==Number(keyVersion.slice(1)))
      throw new Error('VAULT_KEY_RESPONSE_INVALID');
    const plaintextKey=decode32(data.plaintext);
    return {plaintextKey,wrappedKey:data.ciphertext,keyVersion};
  }
  async unwrapDataKey(key:WrappedEvidenceKey):Promise<Uint8Array> {
    const context=scopeContext(key);
    const version=versionOf(key.wrappedKey);
    if(version!==key.keyVersion)throw new Error('VAULT_VERSION_MISMATCH');
    const data=await this.post(`/v1/transit/decrypt/${this.keyName}`,{ciphertext:key.wrappedKey,context});
    return decode32(data.plaintext);
  }
}
