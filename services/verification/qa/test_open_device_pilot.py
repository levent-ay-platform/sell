import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from pilot_workflow import PilotStore,WorkflowError
from workflow_http import WorkflowRoutes
from test_review_attachments import image

class OpenDevicePilot(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.env=patch.dict(os.environ,{"MALAK_OPEN_DEVICE_PILOT":"1","MALAK_DIRECT_PUBLISHING":"0"},clear=False)
        self.env.start()
        self.addCleanup(self.tmp.cleanup);self.addCleanup(self.env.stop)
        self.root=Path(self.tmp.name)/"open"
        self.store=PilotStore(self.root)
    def login(self,device="A",phone="+21623456789"):
        return self.store.login(phone,"1234",device*43)["bearer"]
    def test_same_phone_different_devices_isolated_and_restart_stable(self):
        a,b=self.login(),self.login("B")
        user=self.store.authenticate(a)["id"]
        other=self.store.authenticate(b)["id"]
        self.assertNotEqual(user,other)
        self.assertEqual(self.store.authenticate(self.login())["id"],user)
        self.assertNotEqual(self.store.authenticate(self.login(phone="+21623456788"))["id"],user)
        review=self.store.record_review(user,[],{"assessment":"INSUFFICIENT_EVIDENCE"})
        item=self.store.add_review_attachment(user,review,"CADASTRAL_PLAN","image/png",image("red"))
        ring=[[10.1,36.1],[10.101,36.1],[10.101,36.101]]
        self.store.save_review_boundary(user,review,ring,None)
        for operation in [lambda:self.store.restore_review(other,review),
                          lambda:self.store.review_attachments(other,review),
                          lambda:self.store.read_review_attachment(other,review,item["id"]),
                          lambda:self.store.review_boundary(other,review)]:
            with self.assertRaises(WorkflowError):operation()
        self.assertEqual(self.store.own_submissions(other),[])
        reopened=PilotStore(self.root)
        self.assertEqual(reopened.authenticate(a)["id"],user)
        self.assertEqual(reopened.read_review_attachment(user,review,item["id"])[1],image("red"))
        self.assertEqual(reopened.review_boundary(user,review)["vertices"],ring)
    def test_legacy_database_never_reused(self):
        legacy=Path(self.tmp.name)/"legacy"
        with patch.dict(os.environ,{"MALAK_OPEN_DEVICE_PILOT":"0"}):
            PilotStore(legacy)
            with self.assertRaises(ValueError):PilotStore(self.root)
        with self.assertRaises(ValueError):PilotStore(legacy)
    def test_publication_disabled(self):
        with patch.dict(os.environ,{"MALAK_DIRECT_PUBLISHING":"1"}):
            with self.assertRaises(ValueError):WorkflowRoutes(self.store,None)
        with self.assertRaises(WorkflowError):self.store.decide("admin","id","PUBLISHED",{},"",1)
        self.assertEqual(self.store.public_listings(),[])
    def test_explicit_test_publication_round_trip(self):
        user=self.store.authenticate(self.login())['id']
        other=self.store.authenticate(self.login('B'))['id']
        fields={'title':'Synthetic land','region':'Nabeul','description':'Test only','area':'120','price':'50000','kind':'LAND','latitude':'36.88','longitude':'11.005','requestKey':'a'*32}
        with patch.dict(os.environ,{'MALAK_DIRECT_PUBLISHING':'1','MALAK_OPEN_PILOT_TEST_PUBLICATION':'1'}):
            routes=WorkflowRoutes(self.store,None)
            self.assertTrue(routes.direct_publishing)
            self.assertIsNone(routes.parcel_bridge)
            self.assertEqual(routes.atlas_reader_token,'')
            draft=self.store.prepare_direct_listing(user,fields)
            with self.assertRaises(WorkflowError):self.store.change_direct_listing(other,draft['id'],'publish',{})
            review=self.store.record_review(user,[('IDENTITY','image/png',image('red')),('DOCUMENT','image/png',image('blue'))],{'assessment':'INSUFFICIENT_EVIDENCE'})
            self.store.add_review_attachment(user,review,'CADASTRAL_PLAN','image/png',image('green'))
            self.store.save_review_boundary(user,review,[[10.1,36.1],[10.101,36.1],[10.101,36.101]],None)
            published=self.store.change_direct_listing(user,draft['id'],'publish',{'reviewId':review,'photoIds':[]})
            self.assertEqual(published['status'],'PUBLISHED')
            self.assertEqual(published['reviewId'],review)
            public=self.store.public_listings()[0]
            self.assertTrue(public['testPublication'])
            self.assertTrue(public['title'].startswith('[TEST]'))
            self.assertFalse(public['ownershipVerified'])
            self.assertNotIn('reviewId',public)
            self.assertNotIn('phone',public)
            self.assertEqual(public['photos'],[])
            self.store.change_direct_listing(user,draft['id'],'pause',{})
            self.assertEqual(self.store.public_listings(),[])
        self.assertEqual(self.store.public_listings(),[])
    def test_wrong_code_and_invalid_device(self):
        for code,device in [("1235","A"*43),("1234","short")]:
            with self.assertRaises(WorkflowError):self.store.login("+21623456789",code,device)
