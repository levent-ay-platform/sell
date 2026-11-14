"""Single-service workflow routes; opaque bearer for APK, separate admin session."""
import json
import hashlib
import os
from pathlib import Path
import re
import secrets
import time
from pilot_workflow import WorkflowError

ID=r'[0-9a-f-]{36}'
ADMIN_ROOT=Path(__file__).resolve().parents[3]/'apps/admin'

class WorkflowRoutes:
    def __init__(self,store,pair):self.store=store;self.pair=pair;self.attempts={}
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
            if method=='GET' and path=='/api/public/listings':h.result(200,{'ok':True,'listings':self.store.public_listings()});return True
            match=re.fullmatch('/api/public/photos/('+ID+')',path)
            if match and method=='GET':
                self.binary(h,200,self.store.public_photo(match[1]),'image/jpeg');return True
            match=re.fullmatch('/api/pilot/reviews/('+ID+')/photos',path)
            if match and method=='POST':
                user=self.actor(h);eid=self.store.add_photo(user,match[1],self.body(h,6*1024*1024,True))
                h.result(200,{'ok':True,'photoId':eid});return True
            match=re.fullmatch('/api/pilot/reviews/('+ID+')',path)
            if match and method=='GET':
                restored=self.store.restore_review(self.actor(h),match[1])
                h.result(200,{'ok':True,'pilotOcr':True,**restored,
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
                revision=os.environ.get('MALAK_SOURCE_SHA','development')
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
                h.result(200,{'ok':True,'pilotOcr':True,'comparison':{**report,'reviewId':review},'identityVerified':False,'ownershipVerified':False,'mayPublish':False});return True
            if path=='/api/pilot/submissions':
                user=self.actor(h)
                if method=='GET':h.result(200,{'ok':True,'submissions':self.store.own_submissions(user)});return True
                if method=='POST':h.result(200,{'ok':True,'submission':self.store.submit(user,self.body(h))});return True
            if path=='/api/admin/submissions' and method=='GET':
                h.result(200,{'ok':True,'submissions':self.store.admin_submissions(self.actor(h,True))});return True
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
