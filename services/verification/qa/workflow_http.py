"""Single-service workflow routes; opaque bearer for APK, separate admin session."""
import json
import hashlib
import hmac
import os
from pathlib import Path
import re
import secrets
import time
from datetime import datetime, timezone
from pilot_workflow import WorkflowError
from analysis_revision import analysis_cache_revision, document_engine_revision
from pilot_parcel_bridge import PilotParcelBridge, UUID

ID=r'[0-9a-f-]{36}'
ADMIN_ROOT=Path(__file__).resolve().parents[3]/'apps/admin'

class WorkflowRoutes:
    def __init__(self,store,pair):
        self.store=store;self.pair=pair;self.attempts={}
        self.direct_publishing=os.environ.get('MALAK_DIRECT_PUBLISHING')=='1'
        if self.direct_publishing and (store.open_pilot or store.enrolled_devices is None):
            raise ValueError('Connected Pilot publishing requires device enrollment')
        self.atlas_reader_token=os.environ.get('MALAK_ATLAS_READER_TOKEN','')
        self.parcel_bridge=PilotParcelBridge.configured() if self.direct_publishing else None
    def binary(self,h,status,data,mime):
        h.send_response(status)
        h.send_header("X-Request-Id",secrets.token_hex(12))
        h.send_header("X-Malak-Source-Revision",os.environ.get("MALAK_SOURCE_SHA","development"))
        for k,v in [('Content-Type',mime),('Content-Length',str(len(data))),('Cache-Control','no-store'),('X-Content-Type-Options','nosniff'),('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; frame-src 'self' blob:; object-src 'none'; frame-ancestors 'none'"),('Connection','close')]:h.send_header(k,v)
        h.end_headers();h.wfile.write(data);h.close_connection=True
    def body(self,h,limit=64000,binary=False):
        if h.headers.get('Transfer-Encoding') or len(h.headers.get_all('Content-Length',[]))!=1:raise WorkflowError(400,'BAD_FRAMING')
        try:size=int(h.headers['Content-Length'])
        except (ValueError,TypeError):raise WorkflowError(400,'BAD_FRAMING')
        if not 1<=size<=limit:raise WorkflowError(413,'TOO_LARGE')
        if h.headers.get('Content-Type')!=('application/octet-stream' if binary else 'application/json'):raise WorkflowError(400,'BAD_CONTENT_TYPE')
        h.connection.settimeout(30)
        data=h.rfile.read(size)
        if len(data)!=size:raise WorkflowError(400,'TRUNCATED')
        if binary:return data
        try:return json.loads(data)
        except (ValueError,UnicodeError):raise WorkflowError(400,'BAD_JSON')
    def token(self,h):
        if len(h.headers.get_all('Authorization',[]))!=1:raise WorkflowError(401,'SESSION_REQUIRED')
        value=h.headers.get('Authorization','')
        if not re.fullmatch(r'Bearer [A-Za-z0-9_-]{43}',value):raise WorkflowError(401,'SESSION_REQUIRED')
        return value[7:]
    def actor(self,h,admin=False):
        token=self.token(h)
        user=self.store.admin_authenticate(token) if admin else self.store.authenticate(token)
        if not user:raise WorkflowError(401,'SESSION_REQUIRED')
        return user if admin else user['id']
    def linked_projection(self, listing_id):
        link = self.store.public_parcel_link(listing_id)
        if link is None:
            raise WorkflowError(404,'NOT_FOUND')
        if not link or not self.parcel_bridge:
            return None,None
        projection=self.parcel_bridge.projection(link['v2_listing_id'],link['match_request'])
        if not projection or link!=self.store.public_parcel_link(listing_id):
            return None,None
        return link,projection

    def public_parcel(self,h,listing_id,document_id=None):
        link,projection=self.linked_projection(listing_id)
        documents = [] if projection is None else [
            {'id':doc['id'],'kind':doc['kind'],'mime':doc['mime'],
             'url':f'/api/v1/listings/{listing_id}/documents/{doc["id"]}'}
            for doc in projection.get('documents',[]) if UUID.fullmatch(doc.get('id',''))
        ]
        if document_id:
            approved=next((doc for doc in documents if doc['id']==document_id),None)
            if approved and link:
                rendered=self.parcel_bridge.document(link['v2_listing_id'],document_id)
                current_link,current=self.linked_projection(listing_id)
                if (rendered and rendered[1]==approved['mime'] and current_link==link and current
                    and any(doc['id']==document_id and doc['mime']==approved['mime'] for doc in current.get('documents',[]))):
                    return self.binary(h,200,rendered[0],rendered[1])
            raise WorkflowError(404,'NOT_FOUND')
        h.result(200,{'ok':True,'verifiedParcel':projection is not None,
                      'boundaries':projection['boundaries'] if projection else [],'documents':documents})

    @staticmethod
    def nozha_card(include_plan=False):
        # The configured file is written atomically by the scoped CRM exporter.
        path = os.environ.get('MALAK_NOZHA_FEED_FILE', '')
        if not path or not Path(path).is_absolute():
            raise WorkflowError(404, 'NOT_FOUND')
        try:
            with open(path, 'rb') as stream:
                raw = stream.read(128 * 1024 + 1)
            if len(raw) > 128 * 1024:
                raise WorkflowError(503, 'INVENTORY_REFRESHING')
            envelope = json.loads(raw)
            card = envelope['card']
            now = datetime.now(timezone.utc)
            expires = datetime.fromisoformat(envelope['approvedUntil'].replace('Z', '+00:00'))
            if expires <= now:
                raise WorkflowError(404, 'NOT_FOUND')
            read_at = datetime.fromisoformat(card['refreshedAt'].replace('Z', '+00:00'))
            if not (-5 <= (now - read_at).total_seconds() <= 20) or card['inventoryCurrent'] is not True:
                raise WorkflowError(503, 'INVENTORY_REFRESHING')
            if (card['schemaVersion'] != 1 or card['source'] != 'CASAIMED_CRM' or
                    card['project'] != {'id': '3', 'code': '561663', 'title': 'Nozha'}):
                raise WorkflowError(503, 'INVENTORY_REFRESHING')
            response = {'card': card}
            if include_plan or envelope.get('publicDerivedPlan') is True:
                response['pilotPlan'] = envelope.get('pilotPlan', [])
            return response
        except FileNotFoundError:
            raise WorkflowError(404, 'NOT_FOUND')
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            raise WorkflowError(503, 'INVENTORY_REFRESHING')

    def dispatch(self,h,method):
        path=h.path
        if not (path.startswith('/api/') or path.startswith('/admin') or path=='/internal/v1/pilot/compare-pair'):return False
        try:
            origin=h.headers.get('Origin')
            if (origin and origin!=os.environ.get('MALAK_PUBLIC_ORIGIN','https://malak-api.ayasofia.shop')) or h.headers.get('Cookie') or '?' in path:raise WorkflowError(400,'INVALID_REQUEST')
            if method=='GET' and path in ('/admin','/admin/','/admin/app.js','/admin/style.css'):
                name='index.html' if path in ('/admin','/admin/') else path.rsplit('/',1)[1]
                mime={'index.html':'text/html; charset=utf-8','app.js':'text/javascript; charset=utf-8','style.css':'text/css; charset=utf-8'}[name]
                self.binary(h,200,(ADMIN_ROOT/name).read_bytes(),mime);return True
            if method == 'GET' and path == '/api/pilot/projects/nozha':
                if self.store.enrolled_devices is None:
                    raise WorkflowError(404, 'NOT_FOUND')
                self.actor(h)
                h.result(200, {'ok': True, **self.nozha_card(include_plan=True)})
                return True
            if method == 'GET' and path == '/api/public/projects/nozha':
                h.result(200, {'ok': True, **self.nozha_card()})
                return True
            if method=='GET' and path=='/api/public/listings':h.result(200,{'ok':True,'listings':self.store.public_listings()});return True
            if method=='GET' and path=='/api/public/capabilities':
                h.result(200,{'ok':True,'directPublishing':self.direct_publishing,
                              'draftDocumentReview':self.direct_publishing,
                              'privateReviewAttachments':os.environ.get('MALAK_PRIVATE_REVIEW_ATTACHMENTS')=='1' and self.store.private_review_scope_enabled});return True
            match=re.fullmatch('/api/public/photos/('+ID+')',path)
            if match and method=='GET':
                self.binary(h,200,self.store.public_photo(match[1]),'image/jpeg');return True
            boundary=re.fullmatch('/api/pilot/reviews/('+ID+')/boundary',path)
            if boundary:
                if os.environ.get('MALAK_PRIVATE_REVIEW_ATTACHMENTS')!='1' or not self.store.private_review_scope_enabled:raise WorkflowError(404,'NOT_FOUND')
                user=self.actor(h)
                if method=='GET':
                    h.result(200,{'ok':True,'boundary':self.store.review_boundary(user,boundary[1])});return True
                if method=='POST':
                    if h.headers.get('X-Pilot-Ocr-Consent')!='1':raise WorkflowError(400,'CONSENT_REQUIRED')
                    data=self.body(h,8192)
                    if not isinstance(data,dict) or set(data)!={'vertices','replaceId'}:raise WorkflowError()
                    replace=data['replaceId']
                    if replace is not None and (not isinstance(replace,str) or not re.fullmatch(ID,replace)):raise WorkflowError()
                    if self.actor(h)!=user:raise WorkflowError(401,'SESSION_REQUIRED')
                    value=self.store.save_review_boundary(user,boundary[1],data['vertices'],replace)
                    h.result(200,{'ok':True,'boundary':value,'ownershipVerified':False,'mayPublish':False});return True
                raise WorkflowError(405,'METHOD_NOT_ALLOWED')
            attachment=re.fullmatch('/api/pilot/reviews/('+ID+')/attachments(?:/('+ID+'))?',path)
            if attachment:
                if os.environ.get('MALAK_PRIVATE_REVIEW_ATTACHMENTS')!='1' or not self.store.private_review_scope_enabled:raise WorkflowError(404,'NOT_FOUND')
                user=self.actor(h);review,eid=attachment.groups()
                if method=='GET' and eid is None:
                    h.result(200,{'ok':True,'attachments':self.store.review_attachments(user,review)});return True
                if method=='GET' and eid is not None:
                    _,raw=self.store.read_review_attachment(user,review,eid)
                    self.binary(h,200,raw,'application/octet-stream');return True
                if method=='POST' and eid is not None:
                    if h.headers.get('X-Pilot-Ocr-Consent')!='1' or self.body(h)!={}:raise WorkflowError(400,'CONSENT_REQUIRED')
                    if self.actor(h)!=user:raise WorkflowError(401,'SESSION_REQUIRED')
                    self.store.remove_review_attachment(user,review,eid)
                    h.result(200,{'ok':True});return True
                if method=='POST' and eid is None:
                    if h.headers.get('X-Pilot-Ocr-Consent')!='1':raise WorkflowError(400,'CONSENT_REQUIRED')
                    replace=h.headers.get('X-Replace-Attachment')
                    if replace is not None and not re.fullmatch(ID,replace):raise WorkflowError(422,'INVALID_REQUEST')
                    raw=self.body(h,6*1024*1024,True)
                    if self.actor(h)!=user:raise WorkflowError(401,'SESSION_REQUIRED')
                    item=self.store.add_review_attachment(user,review,h.headers.get('X-Attachment-Kind'),h.headers.get('X-Claimed-Mime'),raw,replace)
                    h.result(200,{'ok':True,'attachment':item,'ownershipVerified':False,'mayPublish':False});return True
                raise WorkflowError(405,'METHOD_NOT_ALLOWED')
            match=re.fullmatch('/api/pilot/reviews/('+ID+')/photos',path)
            if match and method=='POST':
                user=self.actor(h);eid=self.store.add_photo(user,match[1],self.body(h,6*1024*1024,True))
                h.result(200,{'ok':True,'photoId':eid});return True
            match=re.fullmatch('/api/pilot/reviews/('+ID+')',path)
            if match and method=='GET':
                restored=self.store.restore_review(self.actor(h),match[1])
                h.result(200,{'ok':True,'pilotOcr':True,**restored,
                    'identityVerified':False,'ownershipVerified':False,'mayPublish':False});return True
            match=re.fullmatch('/api/pilot/reviews/('+ID+')/identity-date',path)
            if match and method=='POST':
                user=self.actor(h)
                if h.headers.get('X-Pilot-Ocr-Consent')!='1':raise WorkflowError(400,'CONSENT_REQUIRED')
                if self.body(h)!={}:raise WorkflowError(400,'INVALID_REQUEST')
                mime,raw=self.store.identity_for_review(user,match[1])
                try:fields=self.pair.pilot.read(raw,mime,cache_scope=user)
                finally:raw[:]=b'\x00'*len(raw)
                if self.actor(h)!=user:raise WorkflowError(401,'SESSION_REQUIRED')
                refined=self.store.refine_identity_date(user,match[1],fields)
                h.result(200,{'ok':True,'pilotOcr':True,**refined,
                    'identityVerified':False,'ownershipVerified':False,'mayPublish':False});return True
            if method=='POST' and path=='/api/admin/login':
                ip=h.client_address[0];t=time.monotonic();recent=[x for x in self.attempts.get(ip,[]) if t-x<900]
                if len(recent)>=8:raise WorkflowError(429,'TRY_LATER')
                self.attempts[ip]=recent+[t]
                data=self.body(h)
                login=self.store.admin_login(data.get('email'),data.get('password'))
                h.result(200,{'ok':True,**login});return True
            if method=='POST' and path=='/internal/v1/pilot/compare-pair':
                user=self.actor(h)
                if h.headers.get('X-Pilot-Ocr-Consent')!='1':raise WorkflowError(400,'CONSENT_REQUIRED')
                data=self.body(h,12*1024*1024,True)
                fingerprint=hashlib.sha256(data).hexdigest()
                intent=h.headers.get('X-Pilot-Analysis-Key',secrets.token_hex(32))
                if not re.fullmatch(r'[0-9a-f]{64}',intent):raise WorkflowError(400,'INVALID_ANALYSIS_KEY')
                revision=analysis_cache_revision()
                cached=self.store.cached_analysis(user,intent,fingerprint,revision)
                if cached:
                    review,report,same_intent,_=cached
                    if not same_intent:
                        from mac_pair_runtime import frame
                        files=frame(data)
                else:
                    files,report=self.pair.compare(data,cache_scope=user)
                # Recheck session after inference; expired session cannot save/result.
                if self.actor(h)!=user:raise WorkflowError(401,'SESSION_REQUIRED')
                if not cached or not cached[2]:review=self.store.record_review(user,files,report,(intent,fingerprint,revision,cached[3] if cached else int(time.time())))
                h.result(200,{'ok':True,'pilotOcr':True,'comparison':{**report,'reviewId':review},'documentEngineRevision':document_engine_revision(),'identityVerified':False,'ownershipVerified':False,'mayPublish':False});return True
            if path=='/api/pilot/submissions':
                user=self.actor(h)
                if method=='GET':h.result(200,{'ok':True,'submissions':self.store.own_submissions(user)});return True
                if method=='POST':h.result(200,{'ok':True,'submission':self.store.submit(user,self.body(h))});return True
            if self.direct_publishing and method=='POST' and path=='/api/pilot/direct-listings':
                listing=self.store.prepare_direct_listing(self.actor(h),self.body(h))
                h.result(200,{'ok':True,'listing':listing});return True
            direct=re.fullmatch('/api/pilot/direct-listings/('+ID+')/(publish|pause|edit|verification)',path)
            if self.direct_publishing and method=='POST' and direct:
                listing=self.store.change_direct_listing(self.actor(h),direct[1],direct[2],self.body(h))
                h.result(200,{'ok':True,'listing':listing});return True
            if self.direct_publishing and method=='GET' and path=='/api/admin/atlas-listings':
                self.actor(h,True)
                h.result(200,self.store.atlas_listings());return True
            if self.direct_publishing and method=='GET' and path=='/api/atlas/listings':
                if not re.fullmatch(r'[A-Za-z0-9_-]{43}',self.atlas_reader_token):
                    raise WorkflowError(404,'NOT_FOUND')
                if not hmac.compare_digest(self.token(h),self.atlas_reader_token):
                    raise WorkflowError(401,'ATLAS_READER_REQUIRED')
                h.result(200,self.store.atlas_listings());return True
            parcel=re.fullmatch('/api/v1/listings/('+UUID.pattern+')/(parcel|documents/('+UUID.pattern+'))',path)
            if self.direct_publishing and method=='GET' and parcel:
                self.public_parcel(h,parcel[1],parcel[3]);return True
            attachment=re.fullmatch('/api/admin/submissions/('+UUID.pattern+')/parcel-link',path)
            if self.direct_publishing and self.parcel_bridge and method=='POST' and attachment:
                admin=self.actor(h,True)
                data=self.body(h)
                if not isinstance(data,dict) or set(data)!={'version','v2ListingId'} or type(data['version']) is not int:
                    raise WorkflowError(422,'INVALID_PARCEL_LINK')
                v2_id,version=data['v2ListingId'],data['version']
                candidate=None
                if v2_id is not None:
                    if not isinstance(v2_id,str) or not UUID.fullmatch(v2_id):
                        raise WorkflowError(422,'INVALID_PARCEL_LINK')
                    candidate=self.store.parcel_link_candidate(attachment[1],version)
                    if not self.parcel_bridge.projection(v2_id,candidate):
                        raise WorkflowError(422,'PARCEL_MATCH_UNAVAILABLE')
                if self.actor(h,True)!=admin:
                    raise WorkflowError(401,'SESSION_REQUIRED')
                self.store.store_parcel_link(admin,attachment[1],version,v2_id,candidate)
                h.result(200,{'ok':True});return True
            if path=='/api/admin/submissions' and method=='GET':
                h.result(200,{'ok':True,'submissions':self.store.admin_submissions(self.actor(h,True)),
                             'parcelBridgeAvailable':self.parcel_bridge is not None});return True
            match=re.fullmatch('/api/admin/evidence/('+ID+')',path)
            if match and method=='GET':
                mime,data=self.store.evidence(self.actor(h,True),match[1]);self.binary(h,200,data,mime);return True
            match=re.fullmatch('/api/admin/submissions/('+ID+')/decision',path)
            if match and method=='POST':
                admin=self.actor(h,True);data=self.body(h)
                sub=self.store.decide(admin,match[1],data.get('status'),data.get('listing',{}),data.get('note',''),data.get('version'))
                h.result(200,{'ok':True,'submission':sub});return True
            h.result(404,{'ok':False,'error':'NOT_FOUND'})
        except WorkflowError as e:h.result(e.status,{'ok':False,'error':e.code})
        except (ValueError,TypeError,KeyError):h.result(422,{'ok':False,'error':'FILE_OR_DATA_REJECTED'})
        except Exception:h.result(503,{'ok':False,'error':'PROCESSING_UNAVAILABLE'})
        return True
