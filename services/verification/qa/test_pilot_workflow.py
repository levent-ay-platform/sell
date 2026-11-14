import tempfile
import unittest
from pathlib import Path
from pilot_workflow import PilotStore, WorkflowError

class Workflow(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)
        self.store=PilotStore(self.root)
    def tearDown(self): self.tmp.cleanup()
    def test_parallel_analysis_intent_records_exactly_one_review(self):
        from concurrent.futures import ThreadPoolExecutor
        import threading
        barrier=threading.Barrier(2)
        def record(_):
            barrier.wait()
            return self.store.record_review('account-a',[('DOCUMENT','image/png',b'synthetic')],
                {'assessment':'INSUFFICIENT_EVIDENCE'},('a'*64,'b'*64,'version-one'))
        with ThreadPoolExecutor(max_workers=2) as pool:ids=list(pool.map(record,range(2)))
        self.assertEqual(ids[0],ids[1])
        with self.store.db() as db:self.assertEqual(db.execute('SELECT count(*) FROM reviews').fetchone()[0],1)
    def test_cloning_analysis_does_not_renew_original_inference_age(self):
        from unittest.mock import patch
        files=[('DOCUMENT','image/png',b'synthetic')];report={'assessment':'INSUFFICIENT_EVIDENCE'}
        with patch('pilot_workflow.now',return_value=1000):
            self.store.record_review('account-a',files,report,('a'*64,'b'*64,'version-one',1000))
        with patch('pilot_workflow.now',return_value=44000):
            hit=self.store.cached_analysis('account-a','c'*64,'b'*64,'version-one')
            self.assertIsNotNone(hit)
            self.store.record_review('account-a',files,report,('c'*64,'b'*64,'version-one',hit[3]))
        with patch('pilot_workflow.now',return_value=45000):
            self.assertIsNone(self.store.cached_analysis('account-a','c'*64,'b'*64,'version-one'))
    def test_phone_is_account_key_across_devices_and_restart(self):
        a=self.store.login('+21623456789','1234','A'*43)
        b=self.store.login('+21623456789','1234','B'*43)
        c=self.store.login('+21698765432','1234','A'*43)
        self.assertEqual(self.store.authenticate(a['bearer'])['id'],self.store.authenticate(b['bearer'])['id'])
        self.assertNotEqual(self.store.authenticate(a['bearer'])['id'],self.store.authenticate(c['bearer'])['id'])
        self.assertEqual(PilotStore(self.root).authenticate(a['bearer'])['phone'],'+21623456789')
        with self.assertRaises(WorkflowError): self.store.login('+21623456789','9999','A'*43)
    def test_submission_durable_isolated_and_idempotent(self):
        a=self.store.login('+21623456789','1234','A'*43)
        user=self.store.authenticate(a['bearer'])
        report={'assessment':'INSUFFICIENT_EVIDENCE','detectedKind':'UNKNOWN','propertyFacts':[]}
        review=self.store.record_review(user['id'],[('DOCUMENT','image/png',b'original-document'),('IDENTITY','image/jpeg',b'original-identity')],report)
        data={'reviewId':review,'requestKey':'a'*32,'customerName':'Test Person',
          'title':'Test land','region':'Tunis','area':'100','price':'50000','description':'A plot','latitude':'','longitude':''}
        one=self.store.submit(user['id'],data)
        two=self.store.submit(user['id'],data)
        self.assertEqual(one['id'],two['id'])
        self.assertEqual(len(PilotStore(self.root).own_submissions(user['id'])),1)
        other=self.store.authenticate(self.store.login('+21698765432','1234','C'*43)['bearer'])
        self.assertEqual(self.store.own_submissions(other['id']),[])
        with self.assertRaises(WorkflowError): self.store.submit(other['id'],data)
        self.assertEqual(self.store.public_listings(),[])
        # Raw evidence must never be stored in plaintext.
        self.assertNotIn(b'original-identity',b''.join(p.read_bytes() for p in self.root.rglob('*') if p.is_file()))
    def test_admin_may_publish_conflict_without_legal_verification(self):
        user=self.store.authenticate(self.store.login('+21623456789','1234','A'*43)['bearer'])
        rid=self.store.record_review(user['id'],[('DOCUMENT','image/png',b'doc'),('IDENTITY','image/jpeg',b'id')],{'assessment':'CONFLICT_REVIEW','detectedKind':'SALE_DEED'})
        data={'reviewId':rid,'requestKey':'b'*32,'customerName':'Test Person','title':'Test land','region':'Tunis','area':'100','price':'50000','description':'Plot','latitude':'','longitude':''}
        sub=self.store.submit(user['id'],data)
        self.store.bootstrap_admin('admin@example.test','a-long-test-password')
        admin=self.store.admin_login('admin@example.test','a-long-test-password')
        with self.assertRaises(WorkflowError): self.store.decide(user['id'],sub['id'],'PUBLISHED',data,'Reviewed conflict',1)
        published=self.store.decide(admin['id'],sub['id'],'PUBLISHED',data,'Reviewed conflict',1)
        self.assertEqual(published['status'],'PUBLISHED')
        cards=self.store.public_listings()
        self.assertEqual(len(cards),1)
        for key in ['phone','customerName','evidence','report','reviewId']:
            self.assertNotIn(key,cards[0])
        self.assertFalse(cards[0]['ownershipVerified'])
        with self.assertRaises(WorkflowError): self.store.decide(admin['id'],sub['id'],'REJECTED',data,'Stale',1)
        self.store.decide(admin['id'],sub['id'],'PAUSED',data,'Paused',2)
        self.assertEqual(self.store.public_listings(),[])


class PhotoProtection(unittest.TestCase):
    def test_only_sanitized_property_photos_become_public(self):
        import io
        from PIL import Image
        with tempfile.TemporaryDirectory() as folder:
            store=PilotStore(Path(folder));user=store.authenticate(store.login('+21623456789','1234','A'*43)['bearer'])
            rid=store.record_review(user['id'],[('DOCUMENT','application/pdf',b'private doc'),('IDENTITY','image/jpeg',b'private id')],{'assessment':'CONFLICT_REVIEW'})
            im=Image.new('RGB',(300,200),'blue');out=io.BytesIO();im.save(out,format='JPEG');photo=out.getvalue()
            eid=store.add_photo(user['id'],rid,photo)
            self.assertEqual(store.add_photo(user['id'],rid,photo),eid)
            with self.assertRaises(WorkflowError):store.public_photo(eid)
            data={'reviewId':rid,'requestKey':'photo-test-request-20261114','customerName':'Test Person','title':'Test land','region':'Tunis','area':'100','price':'50000','description':''}
            sub=store.submit(user['id'],data)
            self.assertEqual(store.add_photo(user['id'],rid,photo),eid)
            store.bootstrap_admin('admin@example.test','a-long-test-password')
            admin=store.admin_login('admin@example.test','a-long-test-password')['id']
            store.decide(admin,sub['id'],'PUBLISHED',data,'Reviewed conflict',1)
            self.assertEqual(store.public_listings()[0]['photos'],[eid])
            self.assertTrue(store.public_photo(eid).startswith(b'\xff\xd8'))
            with store.db() as db:identity=db.execute("SELECT id FROM evidence WHERE kind='IDENTITY'").fetchone()['id']
            with self.assertRaises(WorkflowError):store.public_photo(identity)
            store.decide(admin,sub['id'],'PAUSED',data,'Paused',2)
            with self.assertRaises(WorkflowError):store.public_photo(eid)

class ReplacementEvidence(unittest.TestCase):
    def test_customer_can_replace_documents_only_on_own_changes_request(self):
        with tempfile.TemporaryDirectory() as folder:
            s=PilotStore(Path(folder));u=s.authenticate(s.login('+21623456789','1234','A'*43)['bearer'])
            rid=s.record_review(u['id'],[('DOCUMENT','image/png',b'first document')],{'assessment':'INSUFFICIENT_EVIDENCE'})
            data={'reviewId':rid,'requestKey':'first-request-20261114','customerName':'Test Person','title':'Test land','region':'Tunis','area':'100','price':'50000','description':''}
            first=s.submit(u['id'],data)
            s.bootstrap_admin('admin@example.test','a-long-test-password');admin=s.admin_login('admin@example.test','a-long-test-password')['id']
            s.decide(admin,first['id'],'CHANGES_REQUESTED',data,'Upload another page',1)
            new=s.record_review(u['id'],[('DOCUMENT','image/png',b'replacement')],{'assessment':'NAME_ONLY_REVIEW'})
            update={**data,'submissionId':first['id'],'reviewId':new,'requestKey':'replacement-request-20261114'}
            result=s.submit(u['id'],update)
            self.assertEqual(result['id'],first['id']);self.assertEqual(result['reviewId'],new)
            self.assertEqual(result['status'],'PENDING_REVIEW')
            self.assertEqual(len(s.own_submissions(u['id'])),1)
            with self.assertRaises(WorkflowError):s.decide(admin,first['id'],'PUBLISHED',data,'Reviewed',2)

class PreserveListingMedia(unittest.TestCase):
    def test_document_only_correction_keeps_property_photos_and_coordinates(self):
        import io
        from PIL import Image
        with tempfile.TemporaryDirectory() as folder:
            s=PilotStore(Path(folder));u=s.authenticate(s.login('+21623456789','1234','A'*43)['bearer'])
            rid=s.record_review(u['id'],[('DOCUMENT','image/png',b'first doc')],{'assessment':'INSUFFICIENT_EVIDENCE'})
            out=io.BytesIO();Image.new('RGB',(300,200),'green').save(out,format='JPEG');s.add_photo(u['id'],rid,out.getvalue())
            data={'reviewId':rid,'requestKey':'media-first-request-20261114','customerName':'Test Person','title':'Test land','region':'Tunis','area':'100','price':'50000','description':'','latitude':'36.8','longitude':'10.2'}
            first=s.submit(u['id'],data)
            s.bootstrap_admin('admin@example.test','a-long-test-password');admin=s.admin_login('admin@example.test','a-long-test-password')['id']
            s.decide(admin,first['id'],'CHANGES_REQUESTED',data,'Replace document',1)
            new=s.record_review(u['id'],[('DOCUMENT','image/png',b'new doc')],{'assessment':'INSUFFICIENT_EVIDENCE'})
            update={**data,'reviewId':new,'submissionId':first['id'],'requestKey':'media-replacement-request-20261114','latitude':'','longitude':''}
            changed=s.submit(u['id'],update)
            self.assertEqual(changed['latitude'],'36.8');self.assertEqual(changed['longitude'],'10.2')
            reviewed=s.admin_submissions(admin)[0]
            photos=[x for x in reviewed['evidence'] if x['kind']=='PHOTO']
            self.assertEqual(len(photos),1)
            self.assertEqual(s.evidence(admin,photos[0]['id'])[1][:2],b'\xff\xd8')
            s.decide(admin,first['id'],'PUBLISHED',changed,'Reviewed incomplete data',3)
            self.assertEqual(len(s.public_listings()[0]['photos']),1)

if __name__=='__main__':unittest.main()
