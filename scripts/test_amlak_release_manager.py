import unittest
from amlak_release_manager import evaluate, GATES, APK_NAME

SHA="a"*40
IDS={"android":11,"private":12,"ocr":13}
class GateTests(unittest.TestCase):
    def fixture(self):
        data={}
        for k,(path,expected) in GATES.items():
            rid=IDS[k]
            data[f"actions/runs/{rid}"]={"head_sha":SHA,"path":path,"event":"push","status":"completed","conclusion":"success"}
            data[f"actions/runs/{rid}/jobs?per_page=100"]={"jobs":[{"name":n,"head_sha":SHA,"status":"completed","conclusion":"success"} for n in expected]}
        data["actions/runs/11/artifacts?per_page=100"]={"artifacts":[{"name":APK_NAME,"id":42,"expired":False,"size_in_bytes":100,"digest":"sha256:"+"b"*64}]}
        return data
    def run_gate(self,data):
        return evaluate(SHA,IDS,data.__getitem__)
    def test_ready_requires_every_gate_and_artifact(self):
        self.assertEqual(self.run_gate(self.fixture())["status"],"CI_CANDIDATE_READY")
    def test_wrong_revision(self):
        d=self.fixture();d["actions/runs/11"]["head_sha"]="c"*40
        self.assertEqual(self.run_gate(d)["status"],"BLOCKED")
    def test_wrong_event(self):
        d=self.fixture();d["actions/runs/11"]["event"]="pull_request"
        self.assertEqual(self.run_gate(d)["status"],"BLOCKED")
    def test_queued(self):
        d=self.fixture();d["actions/runs/11"]["status"]="in_progress"
        self.assertEqual(self.run_gate(d)["status"],"PENDING")
    def test_failed(self):
        d=self.fixture();d["actions/runs/11"]["conclusion"]="failure"
        self.assertEqual(self.run_gate(d)["status"],"BLOCKED")
    def test_skipped_job(self):
        d=self.fixture();d["actions/runs/11/jobs?per_page=100"]["jobs"][1]["conclusion"]="skipped"
        self.assertEqual(self.run_gate(d)["status"],"BLOCKED")
    def test_expired_artifact(self):
        d=self.fixture();d["actions/runs/11/artifacts?per_page=100"]["artifacts"][0]["expired"]=True
        self.assertEqual(self.run_gate(d)["status"],"BLOCKED")
    def test_no_deployment_claim(self):
        self.assertEqual(self.run_gate(self.fixture())["deployment"],"NOT_PERFORMED")
if __name__=="__main__":unittest.main()
