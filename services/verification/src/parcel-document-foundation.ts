import type {PrivateLandFactEvidence} from './document-family-fields-v2.ts';
import type {RecognizedLine} from './smart-fields.ts';

export interface ParcelEdgeMeasurement {
  readonly from:string;
  readonly to:string;
  readonly lengthM:string;
  readonly status:'CANDIDATE'|'LOW_CONFIDENCE'|'CONFLICT';
  readonly evidence:PrivateLandFactEvidence;
}

const VERTEX='([\\p{L}\\p{N}]{1,4})';
const EDGE=new RegExp(
  '^(?:(longueur\\s+(?:du\\s+)?c[oô]t[eé]|c[oô]t[eé]|(?:طول\\s+)?الضلع)\\s+)?'+
  VERTEX+'\\s*[-–—]\\s*'+VERTEX+
  '\\s*[:=]\\s*(\\d{1,7}(?:[.,]\\d{1,3})?)\\s*(m|cm|mm|م|متر|سم|مم)\\.?$','iu'
);

function normalizeMeasurementLine(text:string):string {
  return text.normalize('NFKC')
    .replace(/[٠-٩]/gu,digit=>String(digit.charCodeAt(0)-0x660))
    .replace(/[۰-۹]/gu,digit=>String(digit.charCodeAt(0)-0x6f0))
    .replace(/[\u064b-\u065f\u0670\u200e\u200f\u202a-\u202e\u2066-\u2069]/gu,'')
    .replace(/٫/gu,'.').trim();
}

function metres(value:string,unit:string):string|null {
  const [integer,fraction='']=value.split(/[.,]/u);
  const shift=unit==='cm'||unit==='سم'?2:unit==='mm'||unit==='مم'?3:0;
  const digits=(integer+fraction).padStart(fraction.length+shift+1,'0');
  const split=digits.length-fraction.length-shift;
  const whole=digits.slice(0,split).replace(/^0+(?=\d)/u,'');
  const decimal=digits.slice(split).replace(/0+$/u,'');
  if(whole==='0'&&!decimal)return null;
  return decimal?whole+'.'+decimal:whole;
}

/** A line can suggest a length between explicitly named vertices. It provides no coordinates. */
export function extractParcelEdgeMeasurements(lines:readonly RecognizedLine[]):readonly ParcelEdgeMeasurement[] {
  const candidates:ParcelEdgeMeasurement[]=[];
  const lengths=new Map<string,Set<string>>();
  const key=(edge:ParcelEdgeMeasurement)=>JSON.stringify([
    edge.evidence.page,...[edge.from,edge.to].sort()
  ]);
  for(const [lineIndex,line] of lines.entries()){
    if(line.text.length>120)continue;
    const match=EDGE.exec(normalizeMeasurementLine(line.text));
    if(!match)continue;
    const [,label,from,to,length,unit]=match;
    if(from===to||(!label&&(!/^\p{L}{1,2}$/u.test(from)||!/^\p{L}{1,2}$/u.test(to))))continue;
    const lengthM=metres(length,unit.toLowerCase());
    if(!lengthM)continue;
    const edge:ParcelEdgeMeasurement={
      from,to,lengthM,
      status:(line.confidence!==null&&line.confidence<0.90)||/,\d{3}$/u.test(length)
        ?'LOW_CONFIDENCE':'CANDIDATE',
      evidence:{page:line.page,lineIndex,confidence:line.confidence,
        source:line.source,bbox:line.bbox??null}
    };
    const reference=key(edge);
    const readings=lengths.get(reference)??new Set<string>();
    readings.add(lengthM);
    lengths.set(reference,readings);
    candidates.push(edge);
    if(candidates.length===128)break;
  }
  return candidates.map(edge=>(lengths.get(key(edge))?.size??0)>1
    ?{...edge,status:'CONFLICT'}:edge);
}

type PlanPoint=readonly [number,number];

export interface LocalPlanOutline {
  readonly unit:'PAGE_POINT'|'PIXEL'|'LOCAL_METRE';
  readonly page:number;
  readonly ring:readonly PlanPoint[];
}
export interface PrivatePlanPreview {
  readonly kind:'DOCUMENT_ONLY';
  readonly outline:LocalPlanOutline|null;
  readonly dimensions:readonly ParcelEdgeMeasurement[];
}

function samePoint(a:PlanPoint,b:PlanPoint):boolean {
  return a[0]===b[0]&&a[1]===b[1];
}
function cross(a:PlanPoint,b:PlanPoint,c:PlanPoint):number {
  return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
}
function onSegment(a:PlanPoint,b:PlanPoint,p:PlanPoint):boolean {
  return p[0]>=Math.min(a[0],b[0])&&p[0]<=Math.max(a[0],b[0])&&
    p[1]>=Math.min(a[1],b[1])&&p[1]<=Math.max(a[1],b[1]);
}
function intersects(a:PlanPoint,b:PlanPoint,c:PlanPoint,d:PlanPoint):boolean {
  const abc=cross(a,b,c),abd=cross(a,b,d),cda=cross(c,d,a),cdb=cross(c,d,b);
  return (abc===0&&onSegment(a,b,c))||(abd===0&&onSegment(a,b,d))||
    (cda===0&&onSegment(c,d,a))||(cdb===0&&onSegment(c,d,b))||
    (abc*abd<0&&cda*cdb<0);
}
function isLocalRing(ring:readonly PlanPoint[]):boolean {
  if(!Array.isArray(ring)||ring.length<4||ring.length>129||
    ring.some(point=>!Array.isArray(point)||point.length!==2||
      !point.every(coordinate=>typeof coordinate==='number'&&
        Number.isFinite(coordinate)&&Math.abs(coordinate)<=1e7)))return false;
  const edges=ring.length-1;
  if(!samePoint(ring[0],ring[edges]))return false;
  let twiceArea=0;
  for(let i=0;i<edges;i++){
    const a=ring[i],b=ring[i+1],previous=ring[(i+edges-1)%edges];
    if(samePoint(a,b)||(cross(previous,a,b)===0&&
      (onSegment(previous,a,b)||onSegment(a,b,previous))))return false;
    twiceArea+=a[0]*b[1]-b[0]*a[1];
    for(let j=i+2;j<edges;j++){
      if(i===0&&j===edges-1)continue;
      if(intersects(a,b,ring[j],ring[j+1]))return false;
    }
  }
  return twiceArea!==0;
}

/** Accepts an explicitly traced/extracted outline, never infers shape or placement from lengths. */
export function preparePrivatePlanPreview(input:{
  dimensions:readonly ParcelEdgeMeasurement[];
  localOutline:LocalPlanOutline|null;
}):PrivatePlanPreview {
  const outline=input.localOutline;
  if(outline&&(!['PAGE_POINT','PIXEL','LOCAL_METRE'].includes(outline.unit)||
    !Number.isInteger(outline.page)||outline.page<1||outline.page>8||
    !isLocalRing(outline.ring)))throw new TypeError('INVALID_LOCAL_PLAN_OUTLINE');
  return {kind:'DOCUMENT_ONLY',outline,dimensions:input.dimensions};
}
