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
            def compare(self,data):
                from mac_pair_runtime import frame
                return frame(data),{'detectedKind':'UNKNOWN','assessment':'INSUFFICIENT_EVIDENCE','propertyFacts':[]}
        self.server.workflow=WorkflowRoutes(self.store,Pair())
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join();self.tmp.cleanup()
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
        status,result=self.request('/api/pilot/submissions',{'reviewId':rid,'requestKey':'a'*32,'customerName':'Test Person','title':'Test land','region':'Tunis','area':'100','price':'50000','description':'Test'},token)
        self.assertEqual(status,200)
        self.assertEqual(result['submission']['status'],'PENDING_REVIEW')
        self.assertEqual(len(self.request('/api/pilot/submissions',token=token)[1]['submissions']),1)
        self.assertEqual(self.request('/api/admin/submissions',token=token)[0],401)
        self.assertEqual(self.request('/api/public/listings')[1]['listings'],[])
    def test_admin_browser_origin_allowed_cross_origin_denied(self):
        self.store.bootstrap_admin('admin@example.test','a-long-test-password')
        data={'email':'admin@example.test','password':'a-long-test-password'}
        self.assertEqual(self.request('/api/admin/login',data,headers={'Origin':'https://malak-api.ayasofia.shop'})[0],200)
        self.assertEqual(self.request('/api/admin/login',data,headers={'Origin':'https://evil.test'})[0],400)
        self.assertEqual(self.request('/admin')[0],200)

if __name__=='__main__':unittest.main()
