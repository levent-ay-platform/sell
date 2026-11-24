import json
import struct
import tempfile
import threading
import unittest
from http.client import HTTPConnection
from http.server import HTTPServer
from pathlib import Path
from mac_pilot_ocr_http import Handler,MemoryPilot
from pilot_workflow import PilotStore
from workflow_http import WorkflowRoutes

class HttpWorkflow(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.store=PilotStore(Path(self.tmp.name))
        self.server=HTTPServer(('127.0.0.1',0),Handler)
        self.server.pilot=MemoryPilot(None,[],None);self.server.pilot.store=self.store
        class Pair:
            calls=0
            def compare(self,data,cache_scope=None):
                self.calls+=1
                from mac_pair_runtime import frame
                return frame(data),{'detectedKind':'UNKNOWN','assessment':'INSUFFICIENT_EVIDENCE','propertyFacts':[]}
        self.pair=Pair()
        self.pair.pilot=self.server.pilot
        self.server.workflow=WorkflowRoutes(self.store,self.pair)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join();self.tmp.cleanup()
    def test_nozha_feed_tracks_committed_snapshot_and_refuses_stale_inventory(self):
        from datetime import datetime, timedelta, timezone
        from unittest.mock import patch
        path = Path(self.tmp.name) / 'nozha.json'
        url = '/api/public/projects/nozha'
        now = datetime.now(timezone.utc)
        with patch.dict('os.environ', {'MALAK_NOZHA_FEED_FILE': str(path)}):
            self.assertEqual(self.request(url)[0], 404)
            for offset, status in enumerate(('AVAILABLE', 'RESERVED', 'SOLD', 'AVAILABLE')):
                card = {'schemaVersion': 1, 'source': 'CASAIMED_CRM',
                        'project': {'id':'3','code':'561663','title':'Nozha'},
                        'refreshedAt': now.isoformat(), 'inventoryCurrent': True,
                        'lots': [{'id': '4', 'code': '7', 'status': status,
                                  'availableForInquiry': status == 'AVAILABLE'}]}
                stamp = now - timedelta(seconds=offset)
                envelope = {'approvedUntil': (now + timedelta(minutes=1)).isoformat(), 'card': card}
                path.write_text(json.dumps(envelope))
                code, result = self.request(url)
                self.assertEqual((code, result['card']['lots'][0]['status']), (200, status))
            envelope['card']['refreshedAt'] = (stamp - timedelta(minutes=1)).isoformat()
            path.write_text(json.dumps(envelope))
            self.assertEqual(self.request(url)[0], 503)
            envelope['approvedUntil'] = (now - timedelta(seconds=1)).isoformat()
            path.write_text(json.dumps(envelope))
            self.assertEqual(self.request(url)[0], 404)

    def test_nozha_plan_is_only_available_to_enrolled_pilot_accounts(self):
        from datetime import datetime, timedelta, timezone
        from unittest.mock import patch
        from pilot_workflow import digest
        path = Path(self.tmp.name) / 'nozha-plan.json'
        now = datetime.now(timezone.utc)
        plan = [{'code': '7', 'ring': [[11,36],[11.001,36],[11,36.001],[11,36]]}]
        path.write_text(json.dumps({
            'approvedUntil': (now + timedelta(minutes=1)).isoformat(),
            'card': {'schemaVersion': 1, 'source': 'CASAIMED_CRM',
                     'project': {'id':'3','code':'561663','title':'Nozha'},
                     'refreshedAt': now.isoformat(), 'inventoryCurrent': True},
            'pilotPlan': plan
        }))
        private = '/api/pilot/projects/nozha'
        with patch.dict('os.environ', {'MALAK_NOZHA_FEED_FILE': str(path)}):
            self.assertEqual(self.request(private)[0], 404)
            self.store.enrolled_devices = {'+21623456789': digest('A' * 43)}
            bearer = self.store.login('+21623456789', '1234', 'A' * 43)['bearer']
            self.assertEqual(self.request(private)[0], 401)
            self.assertEqual(self.request(private, token=bearer)[1]['pilotPlan'], plan)
            self.assertNotIn('pilotPlan', self.request('/api/public/projects/nozha')[1])
            envelope = json.loads(path.read_text())
            envelope['publicDerivedPlan'] = True
            path.write_text(json.dumps(envelope))
            self.assertEqual(self.request('/api/public/projects/nozha')[1]['pilotPlan'], plan)
            self.store.enrolled_devices = {}
            self.assertEqual(self.request(private, token=bearer)[0], 401)

    def test_exact_input_replay_avoids_ocr_but_distinct_listing_gets_own_review(self):
        from unittest.mock import patch
        token=self.store.login('+21623456789','1234','A'*43)['bearer']
        other=self.store.login('+21698765432','1234','B'*43)['bearer']
        doc=b'%PDF-1.4'+b' '*30;photo=b'\xff\xd8\xff'+b' '*30
        payload=bytes([1,1])+struct.pack('>I',len(doc))+doc+struct.pack('>I',len(photo))+photo
        def post(key='a'*64,t=token,data=payload):
            return self.request('/internal/v1/pilot/compare-pair',data,t,
                {'Content-Type':'application/octet-stream','X-Pilot-Ocr-Consent':'1','X-Pilot-Analysis-Key':key})
        with patch.dict('os.environ',{'MALAK_SOURCE_SHA':'version-one'}):
            status,first=post();self.assertEqual(status,200)
            status,again=post();self.assertEqual(status,200)
            self.assertEqual(first['comparison']['reviewId'],again['comparison']['reviewId'])
            self.assertEqual(self.pair.calls,1)
            status,new=post('b'*64);self.assertEqual(status,200)
            self.assertNotEqual(first['comparison']['reviewId'],new['comparison']['reviewId'])
            self.assertEqual(self.pair.calls,1)
            self.assertEqual(post(t=other)[0],200);self.assertEqual(self.pair.calls,2)
            self.assertEqual(post(data=payload[:-1]+b'x')[0],409)
            self.assertEqual(post(key='bad-key')[0],400)
        with patch.dict('os.environ',{'MALAK_SOURCE_SHA':'version-two'}):
            status,fresh=post();self.assertEqual(status,200);self.assertEqual(self.pair.calls,3)
            with self.store.db() as db:db.execute('UPDATE analysis_cache SET analyzed_at=0 WHERE review_id=?',(fresh['comparison']['reviewId'],))
            self.assertEqual(post()[0],200);self.assertEqual(self.pair.calls,4)
            from pilot_workflow import digest
            with self.store.db() as db:db.execute('UPDATE sessions SET expires=0 WHERE digest=?',(digest(token),))
            self.assertEqual(post()[0],401);self.assertEqual(self.pair.calls,4)
    def request(self,path,data=None,token=None,headers=None):
        h=headers or {};method='GET' if data is None else 'POST'
        if token:h['Authorization']='Bearer '+token
        if isinstance(data,dict):data=json.dumps(data).encode();h['Content-Type']='application/json'
        c=HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        try:
            c.request(method,path,data,h);r=c.getresponse();body=r.read()
            return r.status,json.loads(body) if r.getheader('Content-Type','').startswith('application/json') else body
        finally:c.close()
    def test_actual_handler_login_compare_and_submission(self):
        status,login=self.request('/internal/v1/pilot/login',{'phone':'+21623456789','code':'1234','deviceKey':'A'*43})
        self.assertEqual(status,200);token=login['bearer']
        doc=b'%PDF-1.4'+b' '*30;front=b'\xff\xd8\xff'+b' '*30
        data=bytes([1,1])+struct.pack('>I',len(doc))+doc+struct.pack('>I',len(front))+front
        endpoint='/internal/v1/pilot/compare-pair'
        headers={'Content-Type':'application/octet-stream','X-Pilot-Ocr-Consent':'1'}
        self.assertEqual(self.request(endpoint,data,None,headers.copy())[0],401)
        self.assertEqual(self.request(endpoint,data,token,{'Content-Type':'application/octet-stream'})[0],400)
        status,result=self.request(endpoint,data,token,headers.copy())
        self.assertEqual(status,200)
        rid=result['comparison']['reviewId']
        restore='/api/pilot/reviews/'+rid
        self.assertEqual(self.request(restore)[0],401)
        other=self.store.login('+21698765432','1234','B'*43)['bearer']
        self.assertEqual(self.request(restore,token=other)[0],404)
        restored=self.request(restore,token=token)
        self.assertEqual(restored[0],200)
        self.assertEqual(restored[1]['comparison']['reviewId'],rid)
        self.assertIsNone(restored[1]['submission'])
        self.assertNotIn('evidence',restored[1])
        status,result=self.request('/api/pilot/submissions',{'reviewId':rid,'requestKey':'a'*32,'customerName':'Test Person','title':'Test land','region':'Tunis','area':'100','price':'50000','description':'Test'},token)
        self.assertEqual(status,200)
        self.assertEqual(result['submission']['status'],'PENDING_REVIEW')
        self.assertEqual(self.request(restore,token=token)[1]['submission']['id'],result['submission']['id'])
        self.assertEqual(len(self.request('/api/pilot/submissions',token=token)[1]['submissions']),1)
        self.assertEqual(self.request('/api/admin/submissions',token=token)[0],401)
        self.assertEqual(self.request('/api/public/listings')[1]['listings'],[])
    def test_admin_browser_origin_allowed_cross_origin_denied(self):
        self.store.bootstrap_admin('admin@example.test','a-long-test-password')
        data={'email':'admin@example.test','password':'a-long-test-password'}
        self.assertEqual(self.request('/api/admin/login',data,headers={'Origin':'https://malak-api.ayasofia.shop'})[0],200)
        self.assertEqual(self.request('/api/admin/login',data,headers={'Origin':'https://evil.test'})[0],400)
        self.assertEqual(self.request('/admin')[0],200)
    def test_publish_with_document_area_binds_one_private_review_atomically(self):
        self.server.workflow.direct_publishing = True
        base = '/api/pilot/direct-listings'
        owner = self.store.login('+21623456789','1234','A'*43)['bearer']
        other = self.store.login('+21698765432','1234','B'*43)['bearer']
        fields = dict(title='Test parcel', region='Tunis', area='275', price='54000',
                      kind='LAND', latitude='36.85', longitude='10.10', requestKey='a'*32)
        sid = self.request(base, fields, owner)[1]['listing']['id']
        evidence = [('IDENTITY','image/jpeg',b'private id'), ('DOCUMENT','application/pdf',b'private deed')]
        def review(token):
            return self.store.record_review(self.store.authenticate(token)['id'], evidence, {
                'propertyFacts':[{'key':'registeredAreaM2','value':'900','page':2,'status':'CANDIDATE'}]})
        def publish(listing, rid):
            return self.request(f'{base}/{listing}/publish', {'reviewId':rid}, owner)
        self.assertEqual(publish(sid, review(other))[0], 404)
        self.assertEqual(self.request('/api/public/listings')[1]['listings'], [])
        rid = review(owner)
        saved = publish(sid, rid)[1]['listing']
        self.assertEqual((saved['area'], saved['reviewId'], saved['verificationStatus']), ('275', rid, 'PLAN_REQUIRED'))
        self.assertEqual(publish(sid, rid)[1]['listing']['version'], saved['version'])
        second = self.request(base, {**fields,'requestKey':'b'*32}, owner)[1]['listing']['id']
        self.assertEqual(publish(second, rid)[0], 404)
        public, = self.request('/api/public/listings')[1]['listings']
        self.assertEqual((public['id'], public['area']), (sid, '275'))
        self.assertEqual(public['kind'], 'LAND')
        self.assertEqual(public['locationAccuracy'], 'APPROXIMATE')
        self.assertEqual((public['latitude'], public['longitude']), ('36.85','10.10'))
        self.assertNotIn('reviewId', public)
        self.assertNotIn('propertyFacts', public)

    def test_direct_listing_publication_verification_and_atlas_use_one_record(self):
        owner=self.store.login('+21623456789','1234','A'*43)['bearer']
        stranger=self.store.login('+21698765432','1234','B'*43)['bearer']
        data={'title':'Isolated QA parcel','region':'Nabeul','area':'120',
              'price':'48000','kind':'LAND','latitude':'36.85','longitude':'11.09','requestKey':'a'*32}
        base='/api/pilot/direct-listings'
        self.assertEqual(self.request(base,data,owner)[0],404)
        self.server.workflow.direct_publishing=True
        self.assertEqual(self.request(base,data)[0],401)
        status,result=self.request(base,data,owner)
        self.assertEqual(status,200)
        draft=result['listing']
        sid=draft['id']
        self.assertEqual(self.request(base,data,owner)[1]['listing']['id'],sid)
        self.assertEqual(self.request(base,{**data,'price':'49000'},owner)[0],409)
        self.assertEqual(self.request('/api/public/listings')[1]['listings'],[])
        self.assertEqual(self.request(base+'/'+sid+'/publish',{},stranger)[0],404)
        photo_path='/api/pilot/reviews/'+draft['reviewId']+'/photos'
        import io
        from PIL import Image
        image=io.BytesIO()
        Image.new('RGB',(100,100)).save(image,format='PNG')
        media_headers={'Content-Type':'application/octet-stream'}
        self.assertEqual(self.request(photo_path,image.getvalue(),stranger,media_headers.copy())[0],403)
        photo=self.request(photo_path,image.getvalue(),owner,media_headers.copy())[1]['photoId']
        self.assertEqual(self.request('/api/public/photos/'+photo)[0],404)
        self.assertEqual(self.request(base+'/'+sid+'/publish',{'photoIds':[photo]},owner)[1]['listing']['status'],'PUBLISHED')
        self.assertEqual(self.request('/api/public/photos/'+photo)[0],200)
        user=self.store.authenticate(owner)['id']
        incomplete=self.store.record_review(user,[('IDENTITY','image/jpeg',b'private')],{})
        self.assertEqual(self.request(base+'/'+sid+'/verification',{'reviewId':incomplete},owner)[0],422)
        review=self.store.record_review(user,[('IDENTITY','image/jpeg',b'private'),
                    ('DOCUMENT','application/pdf',b'private')],{'assessment':'ROLE_TEXT_MATCH_ONLY'})
        first=self.request(base+'/'+sid+'/verification',{'reviewId':review},owner)
        self.assertEqual(first[1]['listing']['verificationStatus'],'PLAN_REQUIRED')
        review=self.store.record_review(user,[('IDENTITY','image/jpeg',b'private'),
                    ('DOCUMENT','application/pdf',b'private')],{'parcelPlanScans':[{'page':2}]})
        private=self.request(base+'/'+sid+'/verification',{'reviewId':review},owner)
        self.assertEqual(private[0],200)
        self.assertEqual(private[1]['listing']['verificationStatus'],'UNDER_REVIEW')
        public,=self.request('/api/public/listings')[1]['listings']
        self.assertEqual(public['id'],sid)
        self.assertEqual(public['kind'],'LAND')
        self.assertEqual(public['locationAccuracy'],'APPROXIMATE')
        self.assertEqual((public['latitude'],public['longitude']),('36.85','11.09'))
        self.assertFalse(public['ownershipVerified'])
        self.assertNotIn('reviewId',public)
        self.assertNotIn('customerName',public)
        self.assertEqual(self.request('/api/admin/atlas-listings',token=owner)[0],401)
        self.server.workflow.atlas_reader_token='R'*43
        self.assertEqual(self.request('/api/atlas/listings',token=owner)[0],401)
        self.store.bootstrap_admin('admin@example.test','a-long-test-password')
        admin=self.store.admin_login('admin@example.test','a-long-test-password')['bearer']
        feature,=self.request('/api/admin/atlas-listings',token=admin)[1]['features']
        self.assertEqual(feature['id'],sid)
        self.assertEqual(feature['geometry'],{'type':'Point','coordinates':[11.09,36.85]})
        self.assertNotIn('reviewId',feature['properties'])
        shared=self.request('/api/atlas/listings',token='R'*43)
        self.assertEqual(shared[0],200)
        self.assertEqual(shared[1]['features'],[feature])
        self.assertEqual(self.request('/api/admin/submissions',token='R'*43)[0],401)
        edit={**data,'price':'51000','version':private[1]['listing']['version']}
        changed=self.request(base+'/'+sid+'/edit',edit,owner)
        self.assertEqual(changed[1]['listing']['verificationStatus'],'UNDER_REVIEW')
        self.assertEqual(self.request(base+'/'+sid+'/edit',edit,owner)[0],409)
        moved=self.request(base+'/'+sid+'/edit',
            {**edit,'version':changed[1]['listing']['version'],'longitude':'11.095'},owner)
        self.assertEqual(moved[1]['listing']['verificationStatus'],'INCOMPLETE')
        with self.store.db() as db:
            historical=db.execute("SELECT COUNT(*) FROM direct_listing_review_history WHERE submission_id=?",(sid,)).fetchone()[0]
        self.assertEqual(historical,2)
        self.assertEqual(self.request(base+'/'+sid+'/pause',{},owner)[1]['listing']['status'],'PAUSED')
        self.assertEqual(self.request('/api/public/listings')[1]['listings'],[])
        self.assertEqual(self.request('/api/admin/atlas-listings',token=admin)[1]['features'],[])
        self.assertEqual(self.request('/api/atlas/listings',token='R'*43)[1]['features'],[])
        resumed=self.request(base+'/'+sid+'/publish',{},owner)[1]['listing']
        self.assertEqual(resumed['status'],'PUBLISHED')
        self.assertEqual(self.request('/api/admin/submissions/'+sid+'/decision',
            {'status':'PAUSED','listing':edit,'note':'QA moderation','version':resumed['version']},admin)[0],200)
        self.assertEqual(self.request(base+'/'+sid+'/publish',{},owner)[0],409)
    def test_linked_parcel_follows_pilot_id_and_live_publication_consent(self):
        import hashlib
        from unittest.mock import Mock
        self.server.workflow.direct_publishing=True
        bridge=Mock()
        self.server.workflow.parcel_bridge=bridge
        owner=self.store.login('+21623456789','1234','A'*43)['bearer']
        account=self.store.authenticate(owner)['id']
        self.store.bootstrap_admin('reviewer@example.test','example-reviewer-password')
        admin=self.store.admin_login('reviewer@example.test','example-reviewer-password')['bearer']
        data={'title':'Local bridge QA','kind':'LAND','region':'Nabeul','area':'120','price':'50000',
              'latitude':'36','longitude':'10','requestKey':'d'*32}
        owner_route='/api/pilot/direct-listings'
        sid=self.request(owner_route,data,owner)[1]['listing']['id']
        self.assertEqual(self.request(owner_route+'/'+sid+'/publish',{},owner)[0],200)
        review=self.store.record_review(account,[('IDENTITY','image/jpeg',b'closed identity'),
                 ('DOCUMENT','application/pdf',b'closed title')],{'parcelPlanFacts':[
                     {'key':'titleReference','value':'00991'}, {'key':'plotReference','value':'QA01'}]})
        saved=self.request(owner_route+'/'+sid+'/verification',{'reviewId':review},owner)[1]['listing']
        edit='/api/admin/submissions/'+sid+'/parcel-link'
        parcel='/api/v1/listings/'+sid+'/parcel'
        approved='11111111-5555-4111-8111-111111111111'
        doc='22222222-5555-4222-8222-222222222222'
        params={'v2ListingId':approved,'version':saved['version']}
        self.assertEqual(self.request(edit,params,owner)[0],401)
        self.assertFalse(self.request(parcel)[1]['verifiedParcel'])
        bridge.projection.return_value=None
        self.assertEqual(self.request(edit,params,admin)[0],422)
        bridge.projection.assert_called_with(approved,{'latitude':36.0,'longitude':10.0,
            'titleReference':'00991','plotReference':'QA01',
            'identityHashes':[hashlib.sha256(b'closed identity').hexdigest()],
            'documentHashes':[hashlib.sha256(b'closed title').hexdigest()]})
        projection={'verifiedParcel':True,
            'boundaries':[{'id':doc,'method':'ATLAS_OFFICIAL_BOUNDARY','geometry':{'type':'Polygon',
                  'coordinates':[[[10,36],[10.001,36],[10,36.001],[10,36]]]}}],
            'documents':[{'id':doc,'kind':'CADASTRAL_PLAN','mime':'application/pdf'}]}
        bridge.projection.return_value=projection
        self.assertEqual(self.request(edit,params,admin)[0],200)
        status,public=self.request(parcel)
        self.assertEqual(status,200)
        self.assertTrue(public['verifiedParcel'])
        self.assertEqual(public['boundaries'],projection['boundaries'])
        self.assertNotIn(approved,json.dumps(public))
        file_path=public['documents'][0]['url']
        self.assertEqual(file_path,'/api/v1/listings/'+sid+'/documents/'+doc)
        bridge.document.return_value=(b'%PDF-1.4 test approved rendition','application/pdf')
        self.assertEqual(self.request(file_path)[0],200)
        bridge.projection.return_value=None
        self.assertFalse(self.request(parcel)[1]['verifiedParcel'])
        self.assertEqual(self.request(file_path)[0],404)
        bridge.projection.return_value=projection
        self.assertEqual(self.request(owner_route+'/'+sid+'/pause',{},owner)[0],200)
        self.assertEqual(self.request(parcel)[0],404)
        self.assertEqual(self.request(owner_route+'/'+sid+'/publish',{},owner)[0],200)
        self.assertTrue(self.request(parcel)[1]['verifiedParcel'])
        current=self.request('/api/pilot/submissions',token=owner)[1]['submissions'][0]
        changed=self.request(owner_route+'/'+sid+'/edit',{**data,'price':'51000','version':current['version']},owner)
        self.assertEqual(changed[0],200)
        self.assertTrue(self.request(parcel)[1]['verifiedParcel'])
        self.assertEqual(self.request(owner_route+'/'+sid+'/edit',
            {**data,'longitude':'10.01','version':changed[1]['listing']['version']},owner)[0],200)
        self.assertFalse(self.request(parcel)[1]['verifiedParcel'])

    def test_identity_date_refresh_requires_owner_consent_and_accepts_no_manual_fields(self):
        from unittest.mock import patch
        token=self.store.login('+21623456789','1234','A'*43)['bearer']
        user=self.store.authenticate(token)['id']
        fields={'surname':'TEST','givenName':'PERSON','cinFull':'00001122','birthDate':None,'confidencePercent':90}
        rid=self.store.record_review(user,[('IDENTITY','image/jpeg',b'fictional')],{'identityFields':fields})
        path='/api/pilot/reviews/'+rid+'/identity-date'
        with patch.object(self.server.pilot,'read',return_value={**fields,'birthDate':'2000-12-05'}) as read:
            self.assertEqual(self.request(path,{},token)[0],400)
            self.assertEqual(self.request(path,{},None,{'X-Pilot-Ocr-Consent':'1'})[0],401)
            self.assertEqual(self.request(path,{'birthDate':'2000-12-05'},token,{'X-Pilot-Ocr-Consent':'1'})[0],400)
            self.assertEqual(read.call_count,0)
            status,result=self.request(path,{},token,{'X-Pilot-Ocr-Consent':'1'})
            self.assertEqual(status,200)
            self.assertEqual(result['comparison']['identityFields']['birthDate'],'2000-12-05')
            self.assertFalse(result['ownershipVerified'])

    def test_private_supplements_and_boundary_http_enrollment_isolation_and_public_denial(self):
        import io
        from PIL import Image
        from unittest.mock import patch
        from pilot_workflow import digest
        self.store.enrolled_devices={'+21623456789':digest('A'*43),'+21623456782':digest('B'*43)}
        owner=self.store.login('+21623456789','1234','A'*43)['bearer']
        other=self.store.login('+21623456782','1234','B'*43)['bearer']
        uid=self.store.authenticate(owner)['id']
        review=self.store.record_review(uid,[],{'assessment':'INSUFFICIENT_EVIDENCE'})
        endpoint='/api/pilot/reviews/'+review
        image=io.BytesIO()
        Image.new('RGB',(120,120),'white').save(image,format='PNG')
        raw=image.getvalue()
        def upload(token,consent=True):
            h={'Content-Type':'application/octet-stream','X-Attachment-Kind':'CADASTRAL_PLAN','X-Claimed-Mime':'image/png'}
            if consent:h['X-Pilot-Ocr-Consent']='1'
            return self.request(endpoint+'/attachments',raw,token,h)
        with patch.dict('os.environ',{'MALAK_PRIVATE_REVIEW_ATTACHMENTS':'0'}):
            self.assertEqual(self.request(endpoint+'/attachments',token=owner)[0],404)
            self.assertFalse(self.request('/api/public/capabilities')[1]['privateReviewAttachments'])
        with patch.dict('os.environ',{'MALAK_PRIVATE_REVIEW_ATTACHMENTS':'1'}):
            self.assertTrue(self.request('/api/public/capabilities')[1]['privateReviewAttachments'])
            self.assertEqual(self.request(endpoint+'/attachments')[0],401)
            self.assertEqual(upload(owner,False)[0],400)
            self.assertEqual(upload(other)[0],404)
            code,res=upload(owner)
            self.assertEqual(code,200);self.assertFalse(res['mayPublish'])
            item=res['attachment'];path=endpoint+'/attachments/'+item['id']
            self.assertEqual(self.request(path,token=owner),(200,raw))
            self.assertEqual(self.request(path,token=other)[0],404)
            self.assertEqual(self.request('/api/public/photos/'+item['id'])[0],404)
            self.assertEqual(self.request(endpoint+'/attachments',token=owner)[1]['attachments'],[item])
            vertices=[[10.1,36.1],[10.101,36.1],[10.101,36.101],[10.1,36.101]]
            data={'vertices':vertices,'replaceId':None}
            self.assertEqual(self.request(endpoint+'/boundary',data,owner)[0],400)
            code,res=self.request(endpoint+'/boundary',data,owner,{'X-Pilot-Ocr-Consent':'1'})
            self.assertEqual(code,200);self.assertFalse(res['boundary']['official'])
            self.assertEqual(self.request(endpoint+'/boundary',token=owner)[1]['boundary']['vertices'],vertices)
            self.assertEqual(self.request(endpoint+'/boundary',token=other)[0],404)
            self.assertEqual(self.request(path,{},owner,{'X-Pilot-Ocr-Consent':'1'})[0],200)
            self.assertEqual(self.request(path,token=owner)[0],404)

if __name__=='__main__':unittest.main()
