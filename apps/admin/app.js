'use strict';
let token='',rows=[],selected=null,blobUrl=null,busy=false,parcelBridgeAvailable=false;
const listingParam = new URLSearchParams(window.location.hash.slice(1)).get('listing');
let linkedListing = /^[0-9a-f-]{36}$/.test(listingParam ?? '') ? listingParam : null;
const $=id=>document.getElementById(id);
function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function message(text){$('message').textContent=text;}
async function api(path,body){
 const res=await fetch(path,{method:body?'POST':'GET',cache:'no-store',credentials:'omit',headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
 if(!res.ok){if(res.status===401&&token)logout();let code='';try{code=(await res.json()).error||'';}catch{}throw Error(res.status===409?'This submission changed. Refresh before deciding.':res.status===503?'Service unavailable. Please retry.':code==='REVIEW_NOTE_REQUIRED'?'Add a review note explaining the publication decision.':'Request failed ('+res.status+'). '+code);}
 return res.json();
}
function logout(){token='';rows=[];selected=null;if(blobUrl)URL.revokeObjectURL(blobUrl);$('workspace').hidden=true;$('login').hidden=false;$('logout').hidden=true;$('loginForm').reset();$('detail').replaceChildren();}
$('logout').onclick=logout;
$('loginForm').onsubmit=async e=>{e.preventDefault();message('');const form=new FormData(e.target);const btn=e.target.querySelector('button');btn.disabled=true;try{const result=await api('/api/admin/login',{email:form.get('email'),password:form.get('password')});token=result.bearer;e.target.reset();$('login').hidden=true;$('workspace').hidden=false;$('logout').hidden=false;await refresh();}catch(e){message(e.message);}finally{btn.disabled=false;}};
async function refresh(){message('');try{const result=await api('/api/admin/submissions');rows=result.submissions;parcelBridgeAvailable=result.parcelBridgeAvailable===true;
 const target=selected?.id ?? linkedListing;
 selected=target?rows.find(item=>item.id===target)||null:null;
 if(linkedListing&&!selected)message('The linked property is no longer available.');
 linkedListing=null;
 queue();detail();}catch(e){message(e.message);}}
$('refresh').onclick=refresh;$('filter').onchange=queue;
function queue(){const q=$('queue');q.replaceChildren();const filtered=rows.filter(x=>!$('filter').value||x.status===$('filter').value);if(!filtered.length)q.append(el('p','No submissions in this view.','muted'));for(const item of filtered){const b=el('button',undefined,'queue-item'+(selected?.id===item.id?' active':''));b.append(el('strong',item.title),el('small',item.customerName+' · '+item.phone),el('span',item.status.replaceAll('_',' '),'badge'));b.onclick=()=>{selected=item;queue();detail();message('');};q.append(b);}}
function fact(parent,label,value){const n=el('div',undefined,'fact');n.append(el('small',label),el('span',value===null||value===undefined||value===''?'Not extracted':String(value)));parent.append(n);}
const kinds={OWNERSHIP_CERTIFICATE:['Strong','Registered ownership certificate'],SHARED_OWNERSHIP_CERTIFICATE:['Strong, shared rights','Shared title; personal share must be reviewed'],TRANSFER_CARD:['Intermediate','Transfer record; role and scope require review'],SALE_DEED:['Intermediate','Sale agreement; registration is separate'],POSSESSION_REPORT:['Supporting','Possession evidence'],FINANCIAL_RECEIPT:['Supporting only','Payment receipt does not establish ownership'],PROPERTY_SERVICE_RECEIPT:['Supporting only','Service request does not establish ownership'],UNKNOWN:['Unclassified','Review the original document']};
function parcelLink(panel,item){
 if(!parcelBridgeAvailable||!item.direct||item.kind!=='LAND'||(item.status!=='PUBLISHED'&&!item.v2ListingId))return;
 const form=el('form',undefined,'section'),label=el('label','Approved V2 listing ID');
 const input=el('input');input.value=item.v2ListingId||'';input.required=true;input.maxLength=36;
 input.pattern='[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
 label.append(input);
 form.append(el('h3','Link approved parcel'),el('p',
  'Amlak checks the private evidence fingerprints, title, plot and approximate location against the approved case.',
  'muted'),label);
 const save=el('button',item.v2ListingId?'Update parcel link':'Link parcel','primary');
 save.type='submit';
 const remove=el('button','Remove parcel link');remove.type='button';
 async function update(id){
  if(busy)return;
  busy=true;save.disabled=true;remove.disabled=true;message('');
  try{
   await api('/api/admin/submissions/'+item.id+'/parcel-link',{version:item.version,v2ListingId:id});
   await refresh();
   message(id?'Approved parcel linked to this listing.':'Parcel link removed.');
  }catch(e){message(e.message);}
  finally{busy=false;save.disabled=false;remove.disabled=false;}
 }
 form.onsubmit=e=>{e.preventDefault();if(form.reportValidity())void update(input.value.trim());};
 remove.onclick=()=>void update(null);
 if(item.status==='PUBLISHED')form.append(save);
 if(item.v2ListingId)form.append(remove);
 panel.append(form);
}

function privatePlanPreview(parent,report){
 const section=el('section',undefined,'section');
 section.append(el('h3','Document outline · approximate'),el('p','Geometry extracted from private plan pixels. Not georeferenced or legally verified.','notice'));
 const scans=Array.isArray(report.parcelPlanScans)?report.parcelPlanScans:[];let shown=0;
 for(const scan of scans.slice(0,8)){
  const shape=scan?.outline,coords=shape?.ring;
  const w=scan?.width,h=scan?.height,page=scan?.page;
  if(scan?.status!=='CANDIDATE'||shape?.unit!=='PIXEL'||shape.page!==page||
     !Number.isInteger(w)||!Number.isInteger(h)||w<64||w>10000||h<64||h>10000||
     !Number.isInteger(page)||page<1||page>8||!Array.isArray(coords)||coords.length<3||coords.length>129)continue;
  if(!coords.every(p=>Array.isArray(p)&&p.length===2&&p.every(Number.isFinite)&&p[0]>=0&&p[1]>=0&&p[0]<=w&&p[1]<=h))continue;
  if(new Set(coords.map(p=>p.join(','))).size<3)continue;
  const area=coords.reduce((sum,p,i)=>{const q=coords[(i+1)%coords.length];return sum+p[0]*q[1]-p[1]*q[0];},0);
  if(Math.abs(area)<1)continue;
  const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
  svg.setAttribute('viewBox','0 0 '+w+' '+h);svg.setAttribute('role','img');
  svg.setAttribute('aria-label','Estimated outline from page '+page);
  svg.classList.add('private-plan-svg');
  const polygon=document.createElementNS('http://www.w3.org/2000/svg','polygon');
  polygon.setAttribute('points',coords.map(p=>p.join(',')).join(' '));
  polygon.setAttribute('stroke','#a42f48');polygon.setAttribute('fill','rgba(164,47,72,.14)');
  polygon.setAttribute('stroke-width',String(Math.max(2,w/220)));
  polygon.setAttribute('stroke-dasharray',Math.max(5,w/100)+' '+Math.max(3,w/175));
  svg.append(polygon);
  const card=el('div',undefined,'private-plan-card');
  card.append(el('strong','Page '+page+' · machine-detected candidate'),svg,el('small','Compare this sketch with the original document below. The outline has no latitude or longitude.'));
  section.append(card);shown++;
 }
 if(!shown)section.append(el('p','No reliable plan outline was extracted; review the original document without inventing a location.','muted'));
 parent.append(section);
}
function privateReviewSteps(parent,report){
 const identity=report.identityFields||{};
 const fields=['surname','givenName','cinFull','birthDate'].filter(k=>typeof identity[k]==='string'&&identity[k]!=='').length;
 const steps=[
  ['Document','Private source received for review'],
  ['CIN reading',fields+'/4 fields extracted (preliminary)'],
  ['Document matching',report.assessment||'Not assessed'],
  ['Plan sketch','Candidate only; verify location separately'],
  ['Manager review','Human decision required']
 ];
 const section=el('section',undefined,'section');section.append(el('h3','Review stages'));
 const list=el('ol',undefined,'review-steps');
 for(const [label,value] of steps){const li=el('li');li.append(el('strong',label),el('span',value));list.append(li);}
 section.append(list);parent.append(section);
}

function detail(){const d=$('detail');d.replaceChildren();if(blobUrl){URL.revokeObjectURL(blobUrl);blobUrl=null;}if(!selected)return;const item=selected,r=item.report||{};d.append(el('h2',item.title));const info=el('div',undefined,'facts');fact(info,'Customer',item.customerName);fact(info,'Mobile',item.phone);fact(info,'Submitted',new Date(item.created*1000).toLocaleString());fact(info,'Status',item.status);d.append(info);parcelLink(d,item);
 d.append(el('h3','Document analysis'));const strength=kinds[r.detectedKind]||kinds.UNKNOWN;d.append(el('p',strength[0]+' · '+strength[1],'notice'));const facts=el('div',undefined,'facts');fact(facts,'Document type',r.detectedKind);fact(facts,'Identity / document comparison',r.assessment);fact(facts,'Matched role',r.matchedRole);fact(facts,'Printed identity number comparison',r.documentCinStatus);const identity=r.identityFields||{};fact(facts,'Extracted surname',identity.surname);fact(facts,'Extracted given name',identity.givenName);fact(facts,'Extracted CIN',identity.cinFull);fact(facts,'Extracted birth date',identity.birthDate);if(r.matchedRightsHolder){fact(facts,'Personal abstract share',r.matchedRightsHolder.rightsParts);fact(facts,'Owner row / page',r.matchedRightsHolder.printedRowOrdinal+' / '+r.matchedRightsHolder.documentPage);}for(const f of r.propertyFacts||[])fact(facts,f.key+' · page '+f.page+' · '+(f.status||'CANDIDATE'),f.value);d.append(facts);privateReviewSteps(d,r);privatePlanPreview(d,r);
 d.append(el('h3','Private original evidence'));const files=el('div',undefined,'files'),preview=el('div');for(const [i,file] of (item.evidence||[]).entries()){const b=el('button',file.kind+' '+(i+1));b.onclick=async()=>{message('');try{const res=await fetch('/api/admin/evidence/'+file.id,{headers:{Authorization:'Bearer '+token},credentials:'omit',cache:'no-store'});if(!res.ok)throw Error('Evidence unavailable ('+res.status+').');const blob=await res.blob();if(blobUrl)URL.revokeObjectURL(blobUrl);blobUrl=URL.createObjectURL(blob);const view=el(file.mime==='application/pdf'?'iframe':'img');view.className='preview';view.src=blobUrl;if(view.tagName==='IMG')view.alt='Private submitted evidence';else view.title='Private submitted PDF';preview.replaceChildren(view);}catch(e){message(e.message);}};files.append(b);}d.append(files,preview);
 const form=el('form',undefined,'section');form.append(el('h3','Listing to publish'));const fields=el('div',undefined,'fields');for(const [key,label] of [['title','Title'],['region','Region'],['area','Area (m²)'],['price','Price (TND)'],['latitude','Latitude'],['longitude','Longitude']]){const l=el('label',label),input=el('input');input.name=key;input.value=item[key]||'';input.required=!['latitude','longitude'].includes(key);input.maxLength=['title','region'].includes(key)?100:24;l.append(input);fields.append(l);}form.append(fields);for(const [key,label] of [['description','Public description'],['note','Review note / message to customer']]){const l=el('label',label),input=el('textarea');input.name=key;input.maxLength=key==='note'?1000:5000;input.value=item[key]||'';l.append(input);form.append(l);}form.append(el('p','Publishing is an administrative decision. It does not mark identity or ownership as legally verified.','source'));const actions=el('div',undefined,'actions');for(const [status,label] of [['PUBLISHED','Publish'],['CHANGES_REQUESTED','Request changes'],['REJECTED','Reject'],...(item.status==='PUBLISHED'?[['PAUSED','Pause listing']]:[])]){const button=el('button',label,status==='PUBLISHED'?'primary':'');button.type='button';button.onclick=async()=>{if(busy||!form.reportValidity())return;const data=Object.fromEntries(new FormData(form));busy=true;actions.querySelectorAll('button').forEach(b=>b.disabled=true);message('Saving decision…');try{await api('/api/admin/submissions/'+item.id+'/decision',{status,version:item.version,note:data.note,listing:data});await refresh();message(status==='PUBLISHED'?'Published. The listing is now visible in Malak.':'Decision saved.');}catch(e){message(e.message);}finally{busy=false;actions.querySelectorAll('button').forEach(b=>b.disabled=false);}};actions.append(button);}form.onsubmit=e=>e.preventDefault();form.append(actions);d.append(form);}
