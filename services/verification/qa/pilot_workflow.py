"""Durable pilot accounts, encrypted review evidence and admin publication.

Separate from legal verification: publishing never verifies a person or title.
"""
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

class WorkflowError(Exception):
    def __init__(self,status=422,code='INVALID_REQUEST'):
        self.status=status;self.code=code
        super().__init__(code)

def ident():return str(uuid.uuid4())
def digest(value):return hashlib.sha256(value.encode()).hexdigest()
def now():return int(time.time())

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
''')
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
        token=secrets.token_urlsafe(32)
        with self.db() as db:
            db.execute('INSERT OR IGNORE INTO accounts VALUES(?,?)',(ident(),phone))
            user=db.execute('SELECT id FROM accounts WHERE phone=?',(phone,)).fetchone()['id']
            db.execute('DELETE FROM sessions WHERE expires<=?',(now(),))
            db.execute('INSERT INTO sessions VALUES(?,?,?)',(digest(token),user,now()+43200))
        return {'ok':True,'pilotConfirmed':True,'bearer':token,'expiresInSeconds':43200,'identityVerified':False,'ownershipVerified':False,'mayPublish':False}
    def authenticate(self,token):
        if not isinstance(token,str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}',token):return None
        with self.db() as db:
            row=db.execute('SELECT a.* FROM accounts a JOIN sessions s ON s.account_id=a.id WHERE s.digest=? AND s.expires>?',(digest(token),now())).fetchone()
            return dict(row) if row else None
    def record_review(self,user,files,report):
        review=ident()
        with self.db() as db:
            db.execute('INSERT INTO reviews VALUES(?,?,?,?)',(review,user,json.dumps(report,ensure_ascii=False),now()))
            for kind,mime,data in files:
                eid=ident();nonce=os.urandom(12)
                sealed=nonce+self.cipher.encrypt(nonce,bytes(data),(review+':'+eid).encode())
                db.execute('INSERT INTO evidence VALUES(?,?,?,?,?)',(eid,review,kind,mime,sealed))
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
    def _submission(self,db,row,admin=False):
        out={'id':row['id'],'reviewId':row['review_id'],'customerName':row['customer_name'],'status':row['status'],'note':row['note'],'version':row['version'],'created':row['created'],'updated':row['updated'],**json.loads(row['listing'])}
        if admin:
            out['phone']=db.execute('SELECT phone FROM accounts WHERE id=?',(row['account_id'],)).fetchone()['phone']
            out['report']=json.loads(db.execute('SELECT report FROM reviews WHERE id=?',(row['review_id'],)).fetchone()['report'])
            out['evidence']=[dict(x) for x in db.execute('SELECT id,kind,mime FROM evidence WHERE review_id=?',(row['review_id'],))]
        return out
    def own_submissions(self,user):
        with self.db() as db:return [self._submission(db,r) for r in db.execute('SELECT * FROM submissions WHERE account_id=? ORDER BY created DESC',(user,)).fetchall()]
    def bootstrap_admin(self,email,password):
        if not isinstance(email,str) or '@' not in email or len(email)>200 or len(password)<14:raise WorkflowError()
        salt=secrets.token_hex(16)
        hashed=hashlib.scrypt(password.encode(),salt=bytes.fromhex(salt),n=16384,r=8,p=1).hex()
        with self.db() as db:db.execute('INSERT INTO admins VALUES(?,?,?,?)',(ident(),email.strip().lower(),salt,hashed))
    def admin_login(self,email,password):
        if not isinstance(email,str) or not isinstance(password,str) or len(password)>200:raise WorkflowError(401,'LOGIN_DENIED')
        with self.db() as db:
            row=db.execute('SELECT * FROM admins WHERE email=?',(email.strip().lower(),)).fetchone()
            # Equal work for unknown accounts.
            salt=bytes.fromhex(row['salt']) if row else bytes(16)
            hashed=hashlib.scrypt(password.encode(),salt=salt,n=16384,r=8,p=1).hex()
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
            return [self._submission(db,r,True) for r in db.execute('SELECT * FROM submissions ORDER BY created DESC').fetchall()]
    def evidence(self,admin,eid):
        with self.db() as db:
            if not db.execute('SELECT 1 FROM admins WHERE id=?',(admin,)).fetchone():raise WorkflowError(403,'ADMIN_REQUIRED')
            row=db.execute('SELECT * FROM evidence WHERE id=?',(eid,)).fetchone()
            if not row:raise WorkflowError(404,'NOT_FOUND')
            sid=db.execute('SELECT id FROM submissions WHERE review_id=?',(row['review_id'],)).fetchone()
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
            if status=='PAUSED' and row['status']!='PUBLISHED':raise WorkflowError(409,'INVALID_TRANSITION')
            report=json.loads(db.execute('SELECT report FROM reviews WHERE id=?',(row['review_id'],)).fetchone()['report'])
            if status=='PUBLISHED' and report.get('assessment') not in ('ROLE_TEXT_MATCH_ONLY','NAME_ONLY_REVIEW') and len(note.strip())<3:raise WorkflowError(422,'REVIEW_NOTE_REQUIRED')
            db.execute('UPDATE submissions SET status=?,listing=?,note=?,version=version+1,updated=? WHERE id=?',(status,json.dumps(values),note.strip(),now(),sid))
            db.execute('INSERT INTO audit VALUES(?,?,?,?,?)',(ident(),admin,sid,status,now()))
            return self._submission(db,db.execute('SELECT * FROM submissions WHERE id=?',(sid,)).fetchone(),True)
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
            if db.execute('SELECT 1 FROM submissions WHERE review_id=?',(review,)).fetchone():raise WorkflowError(409,'ALREADY_SUBMITTED')
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
    def public_listings(self):
        with self.db() as db:return [{'id':r['id'],**json.loads(r['listing']),'publishedAt':r['updated'],'ownershipVerified':False,'photos':[x['id'] for x in db.execute("SELECT id FROM evidence WHERE review_id=? AND kind='PHOTO'",(r['review_id'],))]} for r in db.execute("SELECT * FROM submissions WHERE status='PUBLISHED' ORDER BY updated DESC").fetchall()]
