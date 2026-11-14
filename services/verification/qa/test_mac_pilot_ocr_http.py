"""Only fictional/private-free HTTP contract tests, never real CIN scans."""
import contextlib
import json
import secrets
import sys
import threading
import unittest
from http.client import HTTPConnection
from http.server import HTTPServer

from mac_pilot_ocr_http import Handler, MemoryPilot, NULL_FIELDS


class FakeOCR:
    @staticmethod
    def extract_fields(*_args):
        return dict(NULL_FIELDS)

class Contract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server=HTTPServer(("127.0.0.1",0),Handler)
        cls.server.pilot=MemoryPilot(None,[],FakeOCR())
        cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True)
        cls.thread.start()
    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=3)

    def request(self,path,body=b"",headers=None,method="POST"):
        conn=HTTPConnection("127.0.0.1",self.server.server_port,timeout=5)
        try:
            conn.request(method,path,body,headers or {})
            r=conn.getresponse()
            raw=r.read()
            return r.status,json.loads(raw)
        finally:
            conn.close()

    def login(self,pin):
        data=json.dumps({"phone":"+21623456789","code":pin,
                         "deviceKey":secrets.token_urlsafe(32)}).encode()
        return self.request("/internal/v1/pilot/login",data,
                 {"Content-Type":"application/json"})

    def test_six_field_v5_result_is_accepted(self):
        import cv2
        import numpy as np
        fields={"surname":"بن سالم","givenName":"ليلى","cinLast4":"1122",
                "cinFull":"00001122","birthDate":"1990-01-02","confidencePercent":96}
        class V5:
            @staticmethod
            def extract_fields(*args): return dict(fields)
        pilot=MemoryPilot(None,[],V5())
        ok,photo=cv2.imencode('.png',np.full((400,650,3),255,dtype=np.uint8))
        result=pilot.read(photo.tobytes(),'image/png')
        self.assertEqual(result['cinFull'],'00001122')
        self.assertEqual(result['birthDate'],'1990-01-02')
        self.assertFalse(result['identityVerified'])

    def test_real_memory_scoped_login(self):
        status,reply=self.login("1234")
        self.assertEqual(status,200)
        self.assertTrue(reply["pilotConfirmed"])
        self.assertFalse(reply["identityVerified"])
        self.assertFalse(reply["mayPublish"])
        self.assertEqual(len(reply["bearer"]),43)
        self.assertIsNotNone(self.server.pilot.authorized(
            "Bearer "+reply["bearer"]))
        self.assertNotIn("phone",reply)

    def test_wrong_code_rejected(self):
        self.assertEqual(self.login("9999")[0],401)

    def test_scoped_api_denies_without_consent(self):
        _,auth=self.login("1234")
        payload=b"\xff\xd8\xff"+b"a"*50
        headers={"Authorization":"Bearer "+auth["bearer"],
                 "Content-Type":"application/octet-stream",
                 "X-Claimed-Mime":"image/jpeg"}
        self.assertEqual(self.request("/internal/v1/pilot/preview-cin",
                         payload,headers)[0],400)
        headers["X-Pilot-Ocr-Consent"]="1"
        self.assertEqual(self.request("/internal/v1/pilot/preview-cin",
                         payload,headers)[0],400)  # cannot decode fake pixels

    def test_server_exposes_no_owner_api(self):
        self.assertEqual(self.request("/internal/v1/owner/cases",
            b'{"test":true}',{"Content-Type":"application/json"})[0],404)

    def test_health_is_private_ready_status_only(self):
        status,payload=self.request("/healthz",method="GET")
        self.assertEqual(status,200)
        self.assertTrue(payload["modelReady"])
        self.assertEqual(set(payload),{"ok","engine","modelReady","pilotQa"})

if __name__=="__main__":unittest.main()
