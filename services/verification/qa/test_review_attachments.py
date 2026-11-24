import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from PIL import Image
from pilot_workflow import PilotStore, WorkflowError, digest

def image(color):
    out=io.BytesIO()
    Image.new('RGB',(120,120),color).save(out,format='PNG')
    return out.getvalue()

class Attachments(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.store=PilotStore(self.root)
        self.store.enrolled_devices={'+21623456789':digest('A'*43),'+21623456782':digest('B'*43)}
        self.user=self.store.authenticate(self.store.login('+21623456789','1234','A'*43)['bearer'])['id']
        self.other=self.store.authenticate(self.store.login('+21623456782','1234','B'*43)['bearer'])['id']
        self.review=self.store.record_review(self.user,[],{'assessment':'INSUFFICIENT_EVIDENCE'})
    def tearDown(self):self.tmp.cleanup()
    def save(self,raw,replace=None):
        return self.store.add_review_attachment(self.user,self.review,'CADASTRAL_PLAN','image/png',raw,replace)
    def test_round_trip_encrypted_restart_idempotent_and_isolated(self):
        raw=image('red');item=self.save(raw)
        self.assertEqual(self.save(raw),item)
        self.assertEqual(self.store.review_attachments(self.user,self.review),[item])
        with self.store.db() as db:
            self.assertNotIn(raw,db.execute('SELECT sealed FROM evidence').fetchone()[0])
        reopened=PilotStore(self.root);reopened.enrolled_devices=self.store.enrolled_devices
        self.assertEqual(reopened.read_review_attachment(self.user,self.review,item['id']),('image/png',raw))
        with self.assertRaises(WorkflowError):reopened.read_review_attachment(self.other,self.review,item['id'])
        with self.assertRaises(WorkflowError):reopened.public_photo(item['id'])
    def test_replace_requires_expected_id_and_closed_review_blocks_write(self):
        a=self.save(image('red'))
        with self.assertRaises(WorkflowError):self.save(image('blue'))
        b=self.save(image('blue'),a['id'])
        self.assertNotEqual(a['id'],b['id'])
        with self.assertRaises(WorkflowError):self.store.read_review_attachment(self.user,self.review,a['id'])
        self.store.submit(self.user,{'reviewId':self.review,'requestKey':'a'*32,'customerName':'Test Person','title':'Test land','region':'Tunis','area':'100','price':'50000','description':''})
        with self.assertRaises(WorkflowError):self.save(image('green'),b['id'])
    def test_enrollment_and_file_type_required(self):
        with self.assertRaises((WorkflowError,ValueError)):self.save(b'not a valid image'*10)
        with self.assertRaises(WorkflowError):
            self.store.add_review_attachment(self.user,self.review,'PHOTO','image/png',image('red'))
        self.store.enrolled_devices=None
        with self.assertRaises(WorkflowError):self.save(image('red'))
