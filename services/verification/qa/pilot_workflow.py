"""Durable pilot accounts, encrypted review evidence and admin publication.

Separate from legal verification: publishing never verifies a person or title.
"""
import hashlib
import math
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from datetime import date
from contextlib import contextmanager
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

MAX_ADMIN_PASSWORD_LENGTH = 200

class WorkflowError(Exception):
    def __init__(self,status=422,code='INVALID_REQUEST'):
        self.status=status;self.code=code
        super().__init__(code)

def ident():return str(uuid.uuid4())
def digest(value):return hashlib.sha256(value.encode()).hexdigest()
def now():return int(time.time())

def admin_password_digest(password, salt):
    """Stable RFC 7914 scrypt (64 bytes) even on Python builds without hashlib.scrypt."""
    data = password.encode()
    native = getattr(hashlib, 'scrypt', None)
    if callable(native):
        return native(data, salt=salt, n=16384, r=8, p=1, dklen=64).hex()
    return Scrypt(salt=salt, length=64, n=16384, r=8, p=1).derive(data).hex()

def listing_fields(data):
    result={}
    for key,minimum,maximum in [('title',2,100),('region',2,100),('description',0,5000)]:
        v=data.get(key,'')
        if not isinstance(v,str) or not minimum<=len(v.strip())<=maximum:raise WorkflowError()
        result[key]=v.strip()
    for key,maximum in [('area',1_000_000_000),('price',1_000_000_000_000)]:
        v=data.get(key)
        if not isinstance(v,str) or not re.fullmatch(r'[0-9]{1,12}(?:[.,][0-9]{1,3})?',v):raise WorkflowError()
        v=v.replace(',','.')
        if not 0<float(v)<=maximum:raise WorkflowError()
        result[key]=v
    for key,bound in [('latitude',90),('longitude',180)]:
        v=data.get(key,'')
        if not isinstance(v,str) or len(v)>24:raise WorkflowError()
        if v:
            try:
                if not -bound<=float(v)<=bound:raise ValueError()
            except ValueError:raise WorkflowError()
        result[key]=v
    if bool(result['latitude'])!=bool(result['longitude']):raise WorkflowError()
    return result

class PilotStore:
    def __init__(self,root):
        enrollment = os.environ.get('MALAK_PILOT_DEVICE_ENROLLMENT')
        self.enrolled_devices = None
        if enrollment:
            devices = json.loads(Path(enrollment).read_text())
            if not isinstance(devices,dict) or not devices or any(
                    not re.fullmatch(r'\+216[2-9][0-9]{7}',phone)
                    or not isinstance(device,str) or not re.fullmatch(r'[0-9a-f]{64}',device)
                    for phone,device in devices.items()):
                raise ValueError('Invalid Pilot enrollment')
            self.enrolled_devices = devices
        self.root=Path(root);self.root.mkdir(mode=0o700,parents=True,exist_ok=True)
        os.chmod(self.root,0o700)
        keyfile=self.root/'evidence.key'
        try:
            fd=os.open(keyfile,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(fd,'wb') as f:f.write(AESGCM.generate_key(bit_length=256))
        except FileExistsError:pass
        self.cipher=AESGCM(keyfile.read_bytes())
        self.path=self.root/'pilot.sqlite3'
        with self.db() as db:
            db.executescript('''
CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY,phone TEXT UNIQUE NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(digest TEXT PRIMARY KEY,account_id TEXT NOT NULL,expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS reviews(id TEXT PRIMARY KEY,account_id TEXT NOT NULL,report TEXT NOT NULL,created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS evidence(id TEXT PRIMARY KEY,review_id TEXT NOT NULL,kind TEXT NOT NULL,mime TEXT NOT NULL,sealed BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS submissions(id TEXT PRIMARY KEY,account_id TEXT NOT NULL,review_id TEXT UNIQUE NOT NULL,request_key TEXT NOT NULL,customer_name TEXT NOT NULL,listing TEXT NOT NULL,status TEXT NOT NULL,note TEXT NOT NULL DEFAULT '',version INTEGER NOT NULL DEFAULT 1,created INTEGER NOT NULL,updated INTEGER NOT NULL,UNIQUE(account_id,request_key));
CREATE TABLE IF NOT EXISTS admins(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,salt TEXT NOT NULL,password_hash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS admin_sessions(digest TEXT PRIMARY KEY,admin_id TEXT NOT NULL,expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS audit(id TEXT PRIMARY KEY,actor TEXT NOT NULL,submission TEXT,action TEXT NOT NULL,created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS analysis_cache(account_id TEXT NOT NULL,intent TEXT NOT NULL,fingerprint TEXT NOT NULL,revision TEXT NOT NULL,review_id TEXT NOT NULL,analyzed_at INTEGER NOT NULL,PRIMARY KEY(account_id,intent,revision));
CREATE INDEX IF NOT EXISTS analysis_cache_inputs ON analysis_cache(account_id,fingerprint,revision);
CREATE TABLE IF NOT EXISTS direct_listing_metadata(
 submission_id TEXT PRIMARY KEY REFERENCES submissions(id),
 verification_review_id TEXT UNIQUE REFERENCES reviews(id),
 verification_status TEXT NOT NULL DEFAULT 'INCOMPLETE',
 owner_paused INTEGER NOT NULL DEFAULT 0,
 draft_fingerprint TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS direct_listing_review_history(
 review_id TEXT PRIMARY KEY REFERENCES reviews(id),
 submission_id TEXT NOT NULL REFERENCES direct_listing_metadata(submission_id),
 attached INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS pilot_parcel_links(
 submission_id TEXT PRIMARY KEY REFERENCES direct_listing_metadata(submission_id),
 v2_listing_id TEXT NOT NULL UNIQUE,
 review_id TEXT NOT NULL REFERENCES reviews(id),
 scope_fingerprint TEXT NOT NULL,
 match_request TEXT NOT NULL,
 linked_at INTEGER NOT NULL
);
''')
            if not any(row['name']=='device_digest' for row in db.execute('PRAGMA table_info(sessions)')):
                db.execute('ALTER TABLE sessions ADD COLUMN device_digest TEXT')
        os.chmod(self.path,0o600)
    @contextmanager
    def db(self):
        db=sqlite3.connect(self.path,timeout=10);db.row_factory=sqlite3.Row
        try:
            db.execute('PRAGMA foreign_keys=ON');yield db;db.commit()
        except Exception:db.rollback();raise
        finally:db.close()
    def login(self,phone,code,device):
        if not isinstance(phone,str) or not re.fullmatch(r'\+216[2-9][0-9]{7}',phone) or not isinstance(code,str) or not hmac.compare_digest(code,'1234') or not isinstance(device,str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}',device):raise WorkflowError(401,'LOGIN_DENIED')
        device_digest=digest(device)
        if not self._device_allowed(phone,device_digest):raise WorkflowError(401,'LOGIN_DENIED')
        token=secrets.token_urlsafe(32)
        with self.db() as db:
            db.execute('INSERT OR IGNORE INTO accounts VALUES(?,?)',(ident(),phone))
            user=db.execute('SELECT id FROM accounts WHERE phone=?',(phone,)).fetchone()['id']
            db.execute('DELETE FROM sessions WHERE expires<=?',(now(),))
            db.execute('INSERT INTO sessions(digest,account_id,expires,device_digest) VALUES(?,?,?,?)',
                       (digest(token),user,now()+43200,device_digest))
        return {'ok':True,'pilotConfirmed':True,'bearer':token,'expiresInSeconds':43200,'identityVerified':False,'ownershipVerified':False,'mayPublish':False}
    def _device_allowed(self,phone,device_digest):
        if self.enrolled_devices is None:return True
        expected=self.enrolled_devices.get(phone)
        return bool(expected and device_digest and hmac.compare_digest(expected,device_digest))
    def authenticate(self,token):
        if not isinstance(token,str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}',token):return None
        with self.db() as db:
            row=db.execute('SELECT a.*,s.device_digest FROM accounts a JOIN sessions s ON s.account_id=a.id WHERE s.digest=? AND s.expires>?',(digest(token),now())).fetchone()
            return {'id':row['id'],'phone':row['phone']} if row and self._device_allowed(row['phone'],row['device_digest']) else None
    def cached_analysis(self,user,intent,fingerprint,revision):
        with self.db() as db:
            row=db.execute('SELECT c.*,r.report,r.created FROM analysis_cache c JOIN reviews r ON r.id=c.review_id AND r.account_id=c.account_id WHERE c.account_id=? AND c.intent=? AND c.revision=?',(user,intent,revision)).fetchone()
            if row and row['fingerprint']!=fingerprint:raise WorkflowError(409,'ANALYSIS_INPUT_CHANGED')
            if row and row['analyzed_at']>now()-43200:return row['review_id'],json.loads(row['report']),True,row['analyzed_at']
            row=db.execute('SELECT c.review_id,r.report,c.analyzed_at FROM analysis_cache c JOIN reviews r ON r.id=c.review_id AND r.account_id=c.account_id WHERE c.account_id=? AND c.fingerprint=? AND c.revision=? AND c.analyzed_at>? ORDER BY c.analyzed_at DESC LIMIT 1',(user,fingerprint,revision,now()-43200)).fetchone()
            return (row['review_id'],json.loads(row['report']),False,row['analyzed_at']) if row else None
    def restore_review(self,user,review):
        with self.db() as db:
            row=db.execute('SELECT report FROM reviews WHERE id=? AND account_id=?',(review,user)).fetchone()
            if not row:raise WorkflowError(404,'NOT_FOUND')
            submission=db.execute('SELECT id,status FROM submissions WHERE review_id=? AND account_id=?',(review,user)).fetchone()
            return {'comparison':{**json.loads(row['report']),'reviewId':review},
                    'submission':dict(submission) if submission else None}
    def identity_for_review(self,user,review):
        with self.db() as db:
            if not db.execute('SELECT 1 FROM reviews WHERE id=? AND account_id=?',(review,user)).fetchone():raise WorkflowError(404,'NOT_FOUND')
            closed=db.execute("SELECT 1 FROM submissions WHERE review_id=? AND status NOT IN ('PENDING_REVIEW','CHANGES_REQUESTED')",(review,)).fetchone()
            if closed:raise WorkflowError(409,'REVIEW_CLOSED')
            rows=db.execute("SELECT * FROM evidence WHERE review_id=? AND kind='IDENTITY'",(review,)).fetchall()
            if len(rows)!=1:raise WorkflowError(422,'IDENTITY_UNAVAILABLE')
            row=rows[0];sealed=row['sealed']
            raw=self.cipher.decrypt(sealed[:12],sealed[12:],(review+':'+row['id']).encode())
            return row['mime'],bytearray(raw)
    def refine_identity_date(self,user,review,fields):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT report FROM reviews WHERE id=? AND account_id=?',(review,user)).fetchone()
            if not row:raise WorkflowError(404,'NOT_FOUND')
            sub=db.execute('SELECT id,status FROM submissions WHERE review_id=?',(review,)).fetchone()
            if sub and sub['status'] not in ('PENDING_REVIEW','CHANGES_REQUESTED'):raise WorkflowError(409,'REVIEW_CLOSED')
            report=json.loads(row['report']);old=report.get('identityFields',{})
            if any(old.get(k)!=fields.get(k) for k in ('surname','givenName','cinFull')):raise WorkflowError(409,'IDENTITY_TEXT_CHANGED')
            value=fields.get('birthDate');updated=False
            if value is not None:
                if not isinstance(value,str) or not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}',value):raise WorkflowError()
                try:parsed=date.fromisoformat(value)
                except ValueError:raise WorkflowError()
                if not date(1900,1,1)<=parsed<=date.today():raise WorkflowError()
                if old.get('birthDate') is not None and old['birthDate']!=value:raise WorkflowError(409,'BIRTH_DATE_CONFLICT')
                if old.get('birthDate') is None:
                    report['identityFields']={**old,'birthDate':value}
                    report['identityDateRevision']=os.environ.get('MALAK_SOURCE_SHA','development')
                    db.execute('UPDATE reviews SET report=? WHERE id=?',(json.dumps(report,ensure_ascii=False),review))
                    if sub:db.execute('UPDATE submissions SET version=version+1,updated=? WHERE id=?',(now(),sub['id']))
                    db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),user,sub['id'] if sub else None,'OCR_BIRTH_DATE_REFINED:'+review,now()))
                    updated=True
            return {'comparison':{**report,'reviewId':review},'identityDateUpdated':updated}
    def record_review(self,user,files,report,analysis=None):
        review=ident()
        with self.db() as db:
            if analysis:
                intent,fingerprint,revision=analysis[:3]
                analyzed_at=analysis[3] if len(analysis)>3 else now()
                db.execute('BEGIN IMMEDIATE')
                prior=db.execute('SELECT fingerprint,review_id,analyzed_at FROM analysis_cache WHERE account_id=? AND intent=? AND revision=?',(user,intent,revision)).fetchone()
                if prior and prior['fingerprint']!=fingerprint:raise WorkflowError(409,'ANALYSIS_INPUT_CHANGED')
                if prior and prior['analyzed_at']>now()-43200:return prior['review_id']
            db.execute('INSERT INTO reviews VALUES(?,?,?,?)',(review,user,json.dumps(report,ensure_ascii=False),now()))
            for kind,mime,data in files:
                eid=ident();nonce=os.urandom(12)
                sealed=nonce+self.cipher.encrypt(nonce,bytes(data),(review+':'+eid).encode())
                db.execute('INSERT INTO evidence VALUES(?,?,?,?,?)',(eid,review,kind,mime,sealed))
            if analysis:
                db.execute('INSERT OR REPLACE INTO analysis_cache VALUES(?,?,?,?,?,?)',(user,intent,fingerprint,revision,review,analyzed_at))
        return review
    def submit(self,user,data):
        if not isinstance(data,dict):raise WorkflowError()
        fields=listing_fields(data)
        name=data.get('customerName');key=data.get('requestKey');review=data.get('reviewId')
        if not isinstance(name,str) or not 2<=len(name.strip())<=120 or not isinstance(key,str) or not re.fullmatch(r'[A-Za-z0-9_-]{20,80}',key):raise WorkflowError()
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT * FROM submissions WHERE account_id=? AND request_key=?',(user,key)).fetchone()
            if row:
                if data.get('submissionId')==row['id'] and not fields['latitude']:
                    saved=json.loads(row['listing'])
                    fields['latitude']=saved.get('latitude','');fields['longitude']=saved.get('longitude','')
                if row['review_id']!=review or json.loads(row['listing'])!=fields or row['customer_name']!=name.strip():raise WorkflowError(409,'IDEMPOTENCY_CONFLICT')
                return self._submission(db,row)
            if not db.execute('SELECT 1 FROM reviews WHERE id=? AND account_id=?',(review,user)).fetchone():raise WorkflowError(403,'REVIEW_DENIED')
            if db.execute('SELECT 1 FROM direct_listing_review_history WHERE review_id=?',(review,)).fetchone():
                raise WorkflowError(409,'REVIEW_ALREADY_LINKED')
            target=data.get('submissionId')
            if target is not None and (not isinstance(target,str) or not re.fullmatch(r'[0-9a-f-]{36}',target)):raise WorkflowError()
            prior=db.execute('SELECT * FROM submissions WHERE id=?',(target,)).fetchone() if target else db.execute('SELECT * FROM submissions WHERE review_id=?',(review,)).fetchone()
            if target and not prior:raise WorkflowError(404,'NOT_FOUND')
            if prior:
                if prior['account_id']!=user or prior['status']!='CHANGES_REQUESTED':raise WorkflowError(409,'ALREADY_SUBMITTED')
                sid=prior['id']
                if review!=prior['review_id']:
                    saved=json.loads(prior['listing'])
                    if not fields['latitude']:
                        fields['latitude']=saved.get('latitude','');fields['longitude']=saved.get('longitude','')
                    # Property media belongs to the listing, not the replaced title/ID.
                    oldphotos=db.execute("SELECT * FROM evidence WHERE review_id=? AND kind='PHOTO'",(prior['review_id'],)).fetchall()
                    newphotos=db.execute("SELECT * FROM evidence WHERE review_id=? AND kind='PHOTO'",(review,)).fetchall()
                    seen=[]
                    for photo in newphotos:
                        raw=photo['sealed'];seen.append(self.cipher.decrypt(raw[:12],raw[12:],(review+':'+photo['id']).encode()))
                    for photo in oldphotos:
                        raw=photo['sealed'];clear=self.cipher.decrypt(raw[:12],raw[12:],(prior['review_id']+':'+photo['id']).encode())
                        if clear in seen:continue
                        if len(seen)>=8:raise WorkflowError(422,'TOO_MANY_PHOTOS')
                        eid=ident();nonce=os.urandom(12)
                        sealed=nonce+self.cipher.encrypt(nonce,clear,(review+':'+eid).encode())
                        db.execute('INSERT INTO evidence VALUES(?,?,?,?,?)',(eid,review,'PHOTO','image/jpeg',sealed));seen.append(clear)
                db.execute("UPDATE submissions SET review_id=?,listing=?,customer_name=?,status='PENDING_REVIEW',request_key=?,version=version+1,updated=? WHERE id=?",(review,json.dumps(fields),name.strip(),key,now(),sid))
            else:
                sid=ident()
                db.execute('INSERT INTO submissions(id,account_id,review_id,request_key,customer_name,listing,status,created,updated) VALUES(?,?,?,?,?,?,?,?,?)',(sid,user,review,key,name.strip(),json.dumps(fields),'PENDING_REVIEW',now(),now()))
            db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),user,sid,'SUBMITTED',now()))
            return self._submission(db,db.execute('SELECT * FROM submissions WHERE id=?',(sid,)).fetchone())
    def prepare_direct_listing(self, user, data):
        values = self._direct_fields(data)
        key = data.get('requestKey')
        if not isinstance(key,str) or not re.fullmatch(r'[A-Za-z0-9_-]{20,80}',key):
            raise WorkflowError()
        payload = json.dumps(values, sort_keys=True)
        fingerprint = digest(payload)
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            result = db.execute('''SELECT s.*,d.draft_fingerprint FROM submissions s
                LEFT JOIN direct_listing_metadata d ON d.submission_id=s.id
                WHERE account_id=? AND request_key=?''', (user,key)).fetchone()
            if result:
                if result['draft_fingerprint'] != fingerprint:
                    raise WorkflowError(409,'IDEMPOTENCY_CONFLICT')
                return self._submission(db,result)
            sid, media = ident(), ident()
            timestamp = now()
            db.execute('INSERT INTO reviews VALUES(?,?,?,?)', (media,user,'{}',timestamp))
            db.execute('''INSERT INTO submissions
                (id,account_id,review_id,request_key,customer_name,listing,status,created,updated)
                VALUES(?,?,?,?,?,?,'DRAFT',?,?)''', (sid,user,media,key,'',payload,timestamp,timestamp))
            db.execute('INSERT INTO direct_listing_metadata(submission_id,draft_fingerprint) VALUES(?,?)',
                       (sid,fingerprint))
            return self._submission(db,db.execute('SELECT * FROM submissions WHERE id=?',(sid,)).fetchone())

    @staticmethod
    def _direct_fields(data):
        if not isinstance(data,dict):
            raise WorkflowError()
        fields = listing_fields(data)
        kind = data.get('kind')
        if kind not in ('LAND','APARTMENT','HOUSE','VILLA','FACTORY') or not fields['latitude']:
            raise WorkflowError()
        latitude = float(fields['latitude'])
        longitude = float(fields['longitude'])
        # A public Tunisia property must not be created at 0,0 or abroad.
        # The coarse bounding-box check is not a title/parcel boundary proof.
        if not (math.isfinite(latitude) and math.isfinite(longitude)
                and 29 <= latitude <= 39 and 7 <= longitude <= 13):
            raise WorkflowError(422, 'PUBLIC_LOCATION_OUT_OF_SCOPE')
        for coordinate in ('latitude','longitude'):
            fields[coordinate] = format(float(fields[coordinate]), '.3f')
        fields['kind'] = kind
        return fields

    @staticmethod
    def _attach_direct_review(db, user, row, review):
        if not isinstance(review, str) or not review.strip():
            raise WorkflowError()
        sid = row['id']
        evidence = db.execute('''SELECT r.report FROM reviews r
            WHERE r.id=? AND r.account_id=?
            AND NOT EXISTS(SELECT 1 FROM submissions WHERE review_id=r.id)
            AND NOT EXISTS(SELECT 1 FROM direct_listing_review_history
                    WHERE review_id=r.id AND submission_id<>?)''',(review,user,sid)).fetchone()
        if not evidence:
            raise WorkflowError(404,'REVIEW_NOT_FOUND')
        kinds = {row['kind'] for row in db.execute('SELECT kind FROM evidence WHERE review_id=?',(review,))}
        if not {'IDENTITY','DOCUMENT'}.issubset(kinds):
            raise WorkflowError(422,'INCOMPLETE_DOCUMENTS')
        report = json.loads(evidence['report'])
        plans = report.get('parcelPlanScans') or []
        is_land = json.loads(row['listing'])['kind'] == 'LAND'
        verification = 'PLAN_REQUIRED' if is_land and not plans else 'UNDER_REVIEW'
        db.execute('INSERT OR IGNORE INTO direct_listing_review_history VALUES(?,?,?)',(review,sid,now()))
        db.execute('''UPDATE direct_listing_metadata
            SET verification_review_id=?,verification_status=? WHERE submission_id=?''',(review,verification,sid))

    def change_direct_listing(self, user, sid, action, data):
        if not isinstance(data,dict):
            raise WorkflowError()
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('''SELECT s.*,d.verification_review_id,d.verification_status,d.owner_paused
                FROM submissions s JOIN direct_listing_metadata d ON d.submission_id=s.id
                WHERE s.id=? AND s.account_id=?''', (sid,user)).fetchone()
            if not row:
                raise WorkflowError(404,'NOT_FOUND')
            status = row['status']
            if action in ('publish','pause'):
                desired = 'PUBLISHED' if action == 'publish' else 'PAUSED'
                attach = action == 'publish' and 'reviewId' in data
                if status == desired:
                    if attach and data['reviewId'] != row['verification_review_id']:
                        raise WorkflowError(409,'REVIEW_MISMATCH')
                    return self._submission(db,row)
                allowed = ('DRAFT','PAUSED') if action == 'publish' else ('PUBLISHED',)
                if status not in allowed:
                    raise WorkflowError(409,'INVALID_TRANSITION')
                if status == 'PAUSED' and action == 'publish' and not row['owner_paused']:
                    raise WorkflowError(409,'MODERATION_PAUSE')
                if attach:
                    if status != 'DRAFT':
                        raise WorkflowError(409,'INVALID_TRANSITION')
                    self._attach_direct_review(db, user, row, data['reviewId'])
                if status == 'DRAFT':
                    photos = data.get('photoIds',[])
                    if not isinstance(photos,list) or len(photos)>8 or any(not isinstance(p,str) for p in photos):
                        raise WorkflowError()
                    staged = db.execute("SELECT id FROM evidence WHERE review_id=? AND kind='PHOTO'",
                                        (row['review_id'],)).fetchall()
                    staged_ids = {photo['id'] for photo in staged}
                    if not set(photos).issubset(staged_ids):
                        raise WorkflowError(422,'PHOTO_NOT_OWNED')
                    db.executemany('DELETE FROM evidence WHERE id=?',((photo,) for photo in staged_ids-set(photos)))
                db.execute('UPDATE submissions SET status=? WHERE id=?',(desired,sid))
                db.execute('UPDATE direct_listing_metadata SET owner_paused=? WHERE submission_id=?',
                           (action == 'pause',sid))
            elif action == 'edit':
                fields = self._direct_fields(data)
                if status not in ('PUBLISHED','PAUSED') or data.get('version') != row['version']:
                    raise WorkflowError(409,'STALE_LISTING')
                current = json.loads(row['listing'])
                if any(fields[k] != current[k] for k in ('region','latitude','longitude','area','kind')):
                    db.execute("UPDATE direct_listing_metadata SET verification_status='INCOMPLETE' WHERE submission_id=?",(sid,))
                db.execute('UPDATE submissions SET listing=? WHERE id=?',(json.dumps(fields),sid))
            elif action == 'verification':
                review = data.get('reviewId')
                if status not in ('PUBLISHED','PAUSED'):
                    raise WorkflowError(409,'INVALID_TRANSITION')
                if review == row['verification_review_id'] and row['verification_status'] != 'INCOMPLETE':
                    return self._submission(db,row)
                self._attach_direct_review(db, user, row, review)
            else:
                raise WorkflowError()
            db.execute('UPDATE submissions SET version=version+1,updated=? WHERE id=?',(now(),sid))
            db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),user,sid,'DIRECT_'+action.upper(),now()))
            return self._submission(db,db.execute('SELECT * FROM submissions WHERE id=?',(sid,)).fetchone())

    @staticmethod
    def _parcel_scope(listing):
        fields = json.loads(listing)
        return digest(json.dumps([fields.get(k) for k in ('kind','region','area','latitude','longitude')]))

    def _parcel_listing(self, db, sid):
        return db.execute("""SELECT s.*,d.verification_review_id,d.verification_status
            FROM submissions s JOIN direct_listing_metadata d ON d.submission_id=s.id
            WHERE s.id=?""",(sid,)).fetchone()

    def parcel_link_candidate(self, sid, version):
        with self.db() as db:
            row = self._parcel_listing(db, sid)
            if not row or row['status'] != 'PUBLISHED':
                raise WorkflowError(404,'NOT_FOUND')
            if row['version'] != version:
                raise WorkflowError(409,'STALE_REVIEW')
            if row['verification_status'] not in ('PLAN_REQUIRED','UNDER_REVIEW'):
                raise WorkflowError(422,'REVIEW_REQUIRED')
            fields = json.loads(row['listing'])
            review = row['verification_review_id']
            if fields.get('kind') != 'LAND' or not review:
                raise WorkflowError(422,'LAND_REVIEW_REQUIRED')
            report = json.loads(db.execute('SELECT report FROM reviews WHERE id=?',(review,)).fetchone()['report'])
            facts = report.get('parcelPlanFacts',[]) + report.get('propertyFacts',[])
            def unique_reference(keys):
                if any(f.get('key') in keys and f.get('status')=='CONFLICT' for f in facts):
                    raise WorkflowError(422,'PARCEL_REFERENCES_REQUIRED')
                values = {f['value'].strip().upper() for f in facts if f.get('key') in keys
                          and f.get('status')!='CONFLICT' and isinstance(f.get('value'),str) and f['value'].strip()}
                if len(values) != 1:
                    raise WorkflowError(422,'PARCEL_REFERENCES_REQUIRED')
                return values.pop()
            title_ref = unique_reference(('titleReference','registerReference'))
            plot_ref = unique_reference(('plotReference','soldPlotNumber','registeredPlotNumber'))
            hashes = {'IDENTITY':set(),'DOCUMENT':set()}
            for item in db.execute("SELECT * FROM evidence WHERE review_id=? AND kind IN ('IDENTITY','DOCUMENT')",(review,)):
                sealed = item['sealed']
                raw = self.cipher.decrypt(sealed[:12],sealed[12:],(review+':'+item['id']).encode())
                hashes[item['kind']].add(hashlib.sha256(raw).hexdigest())
            if not hashes['IDENTITY'] or not hashes['DOCUMENT']:
                raise WorkflowError(422,'REVIEW_REQUIRED')
            return {'latitude':float(fields['latitude']),'longitude':float(fields['longitude']),
                    'titleReference':title_ref,'plotReference':plot_ref,
                    'identityHashes':sorted(hashes['IDENTITY']),'documentHashes':sorted(hashes['DOCUMENT'])}

    def store_parcel_link(self, admin, sid, version, v2_id, candidate):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._parcel_listing(db,sid)
            if not row or row['status'] in ('DRAFT','REJECTED'):
                raise WorkflowError(404,'NOT_FOUND')
            if row['version'] != version:
                raise WorkflowError(409,'STALE_REVIEW')
            if v2_id is None:
                db.execute('DELETE FROM pilot_parcel_links WHERE submission_id=?',(sid,))
            else:
                if row['status']!='PUBLISHED' or not row['verification_review_id'] or candidate is None:
                    raise WorkflowError(409,'STALE_REVIEW')
                try:
                    db.execute("""INSERT INTO pilot_parcel_links VALUES(?,?,?,?,?,?)
                       ON CONFLICT(submission_id) DO UPDATE SET v2_listing_id=excluded.v2_listing_id,
                        review_id=excluded.review_id,scope_fingerprint=excluded.scope_fingerprint,
                        match_request=excluded.match_request,linked_at=excluded.linked_at""",
                        (sid,v2_id,row['verification_review_id'],self._parcel_scope(row['listing']),json.dumps(candidate),now()))
                except sqlite3.IntegrityError as error:
                    raise WorkflowError(409,'PARCEL_ALREADY_LINKED') from error
            db.execute('UPDATE submissions SET version=version+1 WHERE id=?',(sid,))
            db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),admin,sid,
                       'PILOT_PARCEL_LINKED' if v2_id else 'PILOT_PARCEL_UNLINKED',now()))

    def public_parcel_link(self, sid):
        with self.db() as db:
            row = self._parcel_listing(db,sid)
            if not row:
                public = db.execute("SELECT 1 FROM submissions WHERE id=? AND status='PUBLISHED'",(sid,)).fetchone()
                return {} if public else None
            if row['status']!='PUBLISHED':
                return None
            link=db.execute('SELECT * FROM pilot_parcel_links WHERE submission_id=?',(sid,)).fetchone()
            if (not link or link['review_id']!=row['verification_review_id']
                    or row['verification_status']=='INCOMPLETE'
                    or link['scope_fingerprint']!=self._parcel_scope(row['listing'])):
                return {}
            return {'v2_listing_id':link['v2_listing_id'],'match_request':json.loads(link['match_request'])}

    def atlas_listings(self):
        features = []
        for row in self.public_listings():
            if not (row.get('latitude') and row.get('longitude')):
                continue
            features.append({
                'type':'Feature','id':row['id'],
                'geometry':{'type':'Point','coordinates':[float(row['longitude']),float(row['latitude'])]},
                'properties':{'listingId':row['id'],'title':row['title'],'kind':row['kind'],
                              'locationAccuracy':row['locationAccuracy']},
            })
        return {'type':'FeatureCollection','features':features}

    def _submission(self,db,row,admin=False):
        out={'id':row['id'],'reviewId':row['review_id'],'customerName':row['customer_name'],'status':row['status'],'note':row['note'],'version':row['version'],'created':row['created'],'updated':row['updated'],**json.loads(row['listing'])}
        direct=db.execute('SELECT * FROM direct_listing_metadata WHERE submission_id=?',(row['id'],)).fetchone()
        review=row['review_id']
        if direct:
            out.update(direct=True,verificationStatus=direct['verification_status'],ownerPaused=bool(direct['owner_paused']))
            if admin:
                linked=db.execute('SELECT v2_listing_id FROM pilot_parcel_links WHERE submission_id=?',(row['id'],)).fetchone()
                out['v2ListingId']=linked['v2_listing_id'] if linked else None
            if direct['verification_review_id']:
                review=direct['verification_review_id']
                out['reviewId']=review
        if admin:
            out['phone']=db.execute('SELECT phone FROM accounts WHERE id=?',(row['account_id'],)).fetchone()['phone']
            out['report']=json.loads(db.execute('SELECT report FROM reviews WHERE id=?',(review,)).fetchone()['report'])
            out['evidence']=[dict(x) for x in db.execute('SELECT id,kind,mime FROM evidence WHERE review_id IN (?,?)',(row['review_id'],review))]
        return out
    def own_submissions(self,user):
        with self.db() as db:return [self._submission(db,r) for r in db.execute("SELECT * FROM submissions WHERE account_id=? AND status<>'DRAFT' ORDER BY created DESC",(user,)).fetchall()]
    def bootstrap_admin(self,email,password):
        if not isinstance(email, str) or '@' not in email or len(email) > 200:
            raise WorkflowError()
        if not isinstance(password, str) or not 14 <= len(password) <= MAX_ADMIN_PASSWORD_LENGTH:
            raise WorkflowError()
        salt=secrets.token_hex(16)
        hashed=admin_password_digest(password,bytes.fromhex(salt))
        with self.db() as db:db.execute('INSERT INTO admins VALUES(?,?,?,?)',(ident(),email.strip().lower(),salt,hashed))
    def admin_login(self,email,password):
        if not isinstance(email,str) or not isinstance(password,str) or len(password)>MAX_ADMIN_PASSWORD_LENGTH:raise WorkflowError(401,'LOGIN_DENIED')
        with self.db() as db:
            row=db.execute('SELECT * FROM admins WHERE email=?',(email.strip().lower(),)).fetchone()
            # Equal work for unknown accounts.
            salt=bytes.fromhex(row['salt']) if row else bytes(16)
            hashed=admin_password_digest(password,salt)
            if not row or not hmac.compare_digest(hashed,row['password_hash']):raise WorkflowError(401,'LOGIN_DENIED')
            token=secrets.token_urlsafe(32)
            db.execute('INSERT INTO admin_sessions VALUES(?,?,?)',(digest(token),row['id'],now()+28800))
            return {'id':row['id'],'bearer':token,'expiresInSeconds':28800}
    def admin_authenticate(self,token):
        with self.db() as db:
            row=db.execute('SELECT admin_id FROM admin_sessions WHERE digest=? AND expires>?',(digest(token),now())).fetchone()
            return row['admin_id'] if row else None
    def admin_submissions(self,admin):
        with self.db() as db:
            if not db.execute('SELECT 1 FROM admins WHERE id=?',(admin,)).fetchone():raise WorkflowError(403,'ADMIN_REQUIRED')
            return [self._submission(db,r,True) for r in db.execute("SELECT * FROM submissions WHERE status<>'DRAFT' ORDER BY created DESC").fetchall()]
    def evidence(self,admin,eid):
        with self.db() as db:
            if not db.execute('SELECT 1 FROM admins WHERE id=?',(admin,)).fetchone():raise WorkflowError(403,'ADMIN_REQUIRED')
            row=db.execute('SELECT * FROM evidence WHERE id=?',(eid,)).fetchone()
            if not row:raise WorkflowError(404,'NOT_FOUND')
            sid=db.execute('''SELECT id FROM submissions WHERE review_id=? AND status<>'DRAFT'
                UNION SELECT submission_id FROM direct_listing_review_history WHERE review_id=?''',
                (row['review_id'],row['review_id'])).fetchone()
            if not sid:raise WorkflowError(404,'NOT_FOUND')
            db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),admin,sid['id'],'EVIDENCE_READ',now()))
            sealed=row['sealed']
            return row['mime'],self.cipher.decrypt(sealed[:12],sealed[12:],(row['review_id']+':'+eid).encode())
    def decide(self,admin,sid,status,fields,note,version):
        if status not in ('PUBLISHED','REJECTED','CHANGES_REQUESTED','PAUSED') or not isinstance(note,str) or len(note)>1000 or not isinstance(version,int):raise WorkflowError()
        values=listing_fields(fields)
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute('SELECT 1 FROM admins WHERE id=?',(admin,)).fetchone():raise WorkflowError(403,'ADMIN_REQUIRED')
            row=db.execute('SELECT * FROM submissions WHERE id=?',(sid,)).fetchone()
            if not row:raise WorkflowError(404,'NOT_FOUND')
            if row['version']!=version:raise WorkflowError(409,'STALE_REVIEW')
            current=json.loads(row['listing'])
            if 'kind' in current:values['kind']=current['kind']
            if status=='PAUSED' and row['status']!='PUBLISHED':raise WorkflowError(409,'INVALID_TRANSITION')
            report=json.loads(db.execute('SELECT report FROM reviews WHERE id=?',(row['review_id'],)).fetchone()['report'])
            if status=='PUBLISHED' and report.get('assessment') not in ('ROLE_TEXT_MATCH_ONLY','NAME_ONLY_REVIEW') and len(note.strip())<3:raise WorkflowError(422,'REVIEW_NOTE_REQUIRED')
            db.execute('UPDATE submissions SET status=?,listing=?,note=?,version=version+1,updated=? WHERE id=?',(status,json.dumps(values),note.strip(),now(),sid))
            if status=='PAUSED':
                db.execute('UPDATE direct_listing_metadata SET owner_paused=0 WHERE submission_id=?',(sid,))
            db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),admin,sid,status,now()))
            return self._submission(db,db.execute('SELECT * FROM submissions WHERE id=?',(sid,)).fetchone(),True)
    def _attachment_scope(self,db,user,review,write=False):
        # Fixed-code open Pilot login is not sufficient for private supplements.
        if self.enrolled_devices is None:raise WorkflowError(404,'NOT_FOUND')
        account=db.execute('SELECT phone FROM accounts WHERE id=?',(user,)).fetchone()
        if not account or account['phone'] not in self.enrolled_devices:raise WorkflowError(404,'NOT_FOUND')
        if not db.execute('SELECT 1 FROM reviews WHERE id=? AND account_id=?',(review,user)).fetchone():raise WorkflowError(404,'NOT_FOUND')
        if write and (db.execute("SELECT 1 FROM submissions WHERE review_id=? AND status<>'DRAFT'",(review,)).fetchone()
                or db.execute('SELECT 1 FROM direct_listing_review_history WHERE review_id=?',(review,)).fetchone()):
            raise WorkflowError(409,'REVIEW_CLOSED')
    def review_attachments(self,user,review):
        with self.db() as db:
            self._attachment_scope(db,user,review)
            return [dict(r) for r in db.execute("SELECT id,kind,mime FROM evidence WHERE review_id=? AND kind IN ('CADASTRAL_PLAN','OTHER_LEGAL_PROOF') ORDER BY kind",(review,))]
    def add_review_attachment(self,user,review,kind,mime,data,replace_id=None):
        import io
        from PIL import Image
        from mac_pair_runtime import sniff
        if kind not in ('CADASTRAL_PLAN','OTHER_LEGAL_PROOF'):raise WorkflowError()
        if not 20<=len(data)<=6*1024*1024:raise WorkflowError(413,'ATTACHMENT_TOO_LARGE')
        if mime not in ('application/pdf','image/png','image/jpeg','image/tiff') or sniff(data)!=mime:raise WorkflowError(422,'FILE_OR_DATA_REJECTED')
        if mime=='application/pdf':
            # Format envelope only. This is unverified private evidence, not
            # a sanitized rendition or a valid cadastre/ownership assertion.
            if not data.rstrip().endswith(b'%%EOF'):raise WorkflowError(422,'FILE_OR_DATA_REJECTED')
        else:
            try:
                with Image.open(io.BytesIO(data)) as image:
                    if min(image.size)<100 or image.width*image.height>25_000_000 or getattr(image,'n_frames',1)!=1:raise ValueError()
                    image.verify()
            except Exception:raise WorkflowError(422,'FILE_OR_DATA_REJECTED')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            self._attachment_scope(db,user,review,write=True)
            rows=db.execute('SELECT * FROM evidence WHERE review_id=? AND kind=?',(review,kind)).fetchall()
            if len(rows)>1:raise WorkflowError(409,'ATTACHMENT_CONFLICT')
            old=rows[0] if rows else None
            if old:
                sealed=old['sealed']
                if old['mime']==mime and self.cipher.decrypt(sealed[:12],sealed[12:],(review+':'+old['id']).encode())==bytes(data):
                    return {'id':old['id'],'kind':kind,'mime':mime}
            if (old['id'] if old else None)!=replace_id:raise WorkflowError(409,'ATTACHMENT_CONFLICT')
            eid=ident();nonce=os.urandom(12)
            sealed=nonce+self.cipher.encrypt(nonce,bytes(data),(review+':'+eid).encode())
            if old:db.execute('DELETE FROM evidence WHERE id=?',(old['id'],))
            db.execute('INSERT INTO evidence VALUES(?,?,?,?,?)',(eid,review,kind,mime,sealed))
            db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),user,None,'PRIVATE_ATTACHMENT_SAVED:'+review,now()))
            return {'id':eid,'kind':kind,'mime':mime}
    def remove_review_attachment(self,user,review,eid):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            self._attachment_scope(db,user,review,write=True)
            row=db.execute("SELECT id FROM evidence WHERE id=? AND review_id=? AND kind IN ('CADASTRAL_PLAN','OTHER_LEGAL_PROOF')",(eid,review)).fetchone()
            if not row:raise WorkflowError(404,'NOT_FOUND')
            db.execute('DELETE FROM evidence WHERE id=?',(eid,))
            db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),user,None,'PRIVATE_ATTACHMENT_REMOVED:'+review,now()))
    def read_review_attachment(self,user,review,eid):
        with self.db() as db:
            self._attachment_scope(db,user,review)
            row=db.execute("SELECT * FROM evidence WHERE id=? AND review_id=? AND kind IN ('CADASTRAL_PLAN','OTHER_LEGAL_PROOF')",(eid,review)).fetchone()
            if not row:raise WorkflowError(404,'NOT_FOUND')
            sealed=row['sealed']
            return row['mime'],self.cipher.decrypt(sealed[:12],sealed[12:],(review+':'+eid).encode())
    @staticmethod
    def _boundary_value(vertices):
        # Private owner drawing only. Never treated as cadastral evidence.
        if not isinstance(vertices,list) or not 3<=len(vertices)<=64:raise WorkflowError(422,'INVALID_BOUNDARY')
        ring=[]
        for point in vertices:
            if not isinstance(point,list) or len(point)!=2 or any(type(v) not in (float,int) or not math.isfinite(v) for v in point):
                raise WorkflowError(422,'INVALID_BOUNDARY')
            lon,lat=point
            if not 6<=lon<=13 or not 29<=lat<=39:raise WorkflowError(422,'INVALID_BOUNDARY')
            ring.append((float(lon),float(lat)))
        if len(set(ring))!=len(ring):raise WorkflowError(422,'INVALID_BOUNDARY')
        lon0,lat0=ring[0];factor=math.pi/180*6371008.8
        xy=[((lon-lon0)*factor*math.cos(math.radians(lat0)),(lat-lat0)*factor) for lon,lat in ring]
        if max(x for x,y in xy)-min(x for x,y in xy)>20000 or max(y for x,y in xy)-min(y for x,y in xy)>20000:raise WorkflowError(422,'INVALID_BOUNDARY')
        def orientation(a,b,c):
            v=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
            return 0 if abs(v)<1e-8 else 1 if v>0 else -1
        def on(a,b,c):
            return min(a[0],b[0])-1e-8<=c[0]<=max(a[0],b[0])+1e-8 and min(a[1],b[1])-1e-8<=c[1]<=max(a[1],b[1])+1e-8
        def cross(a,b,c,d):
            o,p,q,r=orientation(a,b,c),orientation(a,b,d),orientation(c,d,a),orientation(c,d,b)
            return (o*p<0 and q*r<0) or (o==0 and on(a,b,c)) or (p==0 and on(a,b,d)) or (q==0 and on(c,d,a)) or (r==0 and on(c,d,b))
        edges=list(zip(xy,xy[1:]+xy[:1]))
        lengths=[math.hypot(b[0]-a[0],b[1]-a[1]) for a,b in edges]
        area=abs(sum(a[0]*b[1]-b[0]*a[1] for a,b in edges))/2
        if area<1 or min(lengths)<.5:raise WorkflowError(422,'INVALID_BOUNDARY')
        for i,(a,b) in enumerate(edges):
            for j in range(i+1,len(edges)):
                if j==i+1 or (i==0 and j==len(edges)-1):continue
                if cross(a,b,*edges[j]):raise WorkflowError(422,'INVALID_BOUNDARY')
        return {'method':'OWNER_TRACED_ESTIMATE','vertices':ring,'areaM2':round(area,2),'lengthsM':[round(v,2) for v in lengths],'official':False}
    def review_boundary(self,user,review):
        with self.db() as db:
            self._attachment_scope(db,user,review)
            rows=db.execute("SELECT * FROM evidence WHERE review_id=? AND kind='OWNER_DRAWN_BOUNDARY'",(review,)).fetchall()
            if not rows:return None
            if len(rows)!=1:raise WorkflowError(409,'BOUNDARY_CONFLICT')
            row=rows[0];sealed=row['sealed']
            value=json.loads(self.cipher.decrypt(sealed[:12],sealed[12:],(review+':'+row['id']).encode()))
            return {'id':row['id'],**value}
    def save_review_boundary(self,user,review,vertices,replace_id):
        value=self._boundary_value(vertices) if vertices!=[] else None
        data=json.dumps(value,separators=(',',':')).encode() if value is not None else None
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            self._attachment_scope(db,user,review,write=True)
            rows=db.execute("SELECT * FROM evidence WHERE review_id=? AND kind='OWNER_DRAWN_BOUNDARY'",(review,)).fetchall()
            if len(rows)>1:raise WorkflowError(409,'BOUNDARY_CONFLICT')
            old=rows[0] if rows else None
            if old and data is not None:
                sealed=old['sealed']
                if self.cipher.decrypt(sealed[:12],sealed[12:],(review+':'+old['id']).encode())==data:return {'id':old['id'],**value}
            if (old['id'] if old else None)!=replace_id:raise WorkflowError(409,'BOUNDARY_CONFLICT')
            if old:db.execute('DELETE FROM evidence WHERE id=?',(old['id'],))
            if value is None:return None
            eid=ident();nonce=os.urandom(12)
            sealed=nonce+self.cipher.encrypt(nonce,data,(review+':'+eid).encode())
            db.execute('INSERT INTO evidence VALUES(?,?,?,?,?)',(eid,review,'OWNER_DRAWN_BOUNDARY','application/json',sealed))
            db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),user,None,'PRIVATE_BOUNDARY_SAVED:'+review,now()))
            return {'id':eid,**value}
    def add_photo(self,user,review,data):
        import io
        from PIL import Image, ImageOps
        if not 20<=len(data)<=6*1024*1024:raise WorkflowError(413,'PHOTO_TOO_LARGE')
        with Image.open(io.BytesIO(data)) as image:
            if image.width*image.height>25_000_000 or min(image.size)<100:raise WorkflowError()
            image=ImageOps.exif_transpose(image).convert('RGB')
            image.thumbnail((1800,1800))
            output=io.BytesIO();image.save(output,format='JPEG',quality=88)
            clean=output.getvalue()
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute('SELECT 1 FROM reviews WHERE id=? AND account_id=?',(review,user)).fetchone():raise WorkflowError(403,'REVIEW_DENIED')
            existing=db.execute("SELECT id,sealed FROM evidence WHERE review_id=? AND kind='PHOTO'",(review,)).fetchall()
            # Retry-safe deduplication of sanitized property photos only.
            for old in existing:
                v=old['sealed']
                if self.cipher.decrypt(v[:12],v[12:],(review+':'+old['id']).encode())==clean:return old['id']
            if db.execute("SELECT 1 FROM submissions WHERE review_id=? AND status<>'DRAFT'",(review,)).fetchone():raise WorkflowError(409,'ALREADY_SUBMITTED')
            if len(existing)>=8:raise WorkflowError(413,'TOO_MANY_PHOTOS')
            eid=ident();nonce=os.urandom(12)
            sealed=nonce+self.cipher.encrypt(nonce,clean,(review+':'+eid).encode())
            db.execute('INSERT INTO evidence VALUES(?,?,?,?,?)',(eid,review,'PHOTO','image/jpeg',sealed))
            return eid
    def public_photo(self,eid):
        with self.db() as db:
            row=db.execute("SELECT e.* FROM evidence e JOIN submissions s ON s.review_id=e.review_id WHERE e.id=? AND e.kind='PHOTO' AND s.status='PUBLISHED'",(eid,)).fetchone()
            if not row:raise WorkflowError(404,'NOT_FOUND')
            sealed=row['sealed']
            return self.cipher.decrypt(sealed[:12],sealed[12:],(row['review_id']+':'+eid).encode())
    @staticmethod
    def _public_listing_fields(listing, allow_public_location=False):
        """Strict public allowlist, independent from mutable private review snapshots.

        A legacy reviewed submission has no property kind: UNKNOWN means unknown,
        never inferred LAND. Exact owner-supplied coordinates stay private; only
        a rounded, approximate point is exposed where coordinates are plausible.
        This does NOT certify title, seller consent for a cadastral boundary, or
        legal parcel accuracy.
        """
        kinds = ('LAND', 'APARTMENT', 'HOUSE', 'VILLA', 'FACTORY')
        kind = listing.get('kind')
        result = {key: listing.get(key, '') for key in
                  ('title', 'region', 'description', 'area', 'price')}
        result['kind'] = kind if kind in kinds else 'UNKNOWN'
        try:
            latitude = float(listing.get('latitude') or '')
            longitude = float(listing.get('longitude') or '')
            valid = (math.isfinite(latitude) and math.isfinite(longitude)
                     and 29 <= latitude <= 39 and 7 <= longitude <= 13)
        except (ValueError, TypeError, OverflowError):
            valid = False
        if valid and allow_public_location:
            # Only an owner-published direct listing can expose a coarse
            # point. Legacy evidence-first reviews did not authorize a pin.
            # Public ~1 km precision, never return saved private coordinates.
            result['latitude'] = f'{latitude:.2f}'
            result['longitude'] = f'{longitude:.2f}'
            result['locationAccuracy'] = 'APPROXIMATE'
        else:
            result['latitude'] = None
            result['longitude'] = None
            result['locationAccuracy'] = 'AREA_ONLY' if result['region'] else 'UNKNOWN'
        return result

    def public_listings(self):
        with self.db() as db:
            return [
                {'id': row['id'], **self._public_listing_fields(
                     json.loads(row['listing']), allow_public_location=bool(row['direct_owner_publish'])),
                 'publishedAt': row['updated'], 'ownershipVerified': False,
                 'photos': [photo['id'] for photo in db.execute(
                     "SELECT id FROM evidence WHERE review_id=? AND kind='PHOTO'",
                     (row['review_id'],))]}
                for row in db.execute(
                    """SELECT s.*, EXISTS (
                        SELECT 1 FROM direct_listing_metadata d WHERE d.submission_id=s.id
                    ) AS direct_owner_publish
                    FROM submissions s WHERE s.status='PUBLISHED' ORDER BY s.updated DESC"""
                ).fetchall()
            ]
