import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject,NameObject
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_georeference_case import prepare,assess
from scripts.atlas_amlak_candidate_handoff import stage_batch

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()

class HandoffTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup)
        self.root=Path(t.name).resolve()
        pdf=self.root/"doc.pdf"
        w=PdfWriter();p=w.add_blank_page(200,150)
        s=DecodedStreamObject();s.set_data(b"20 30 60 40 re S")
        p[NameObject("/Contents")]=w._add_object(s)
        with pdf.open("wb") as f:w.write(f)
        self.sha=sha(pdf)
        self.idx=self.root/"index.json"
        write_private_json(self.idx,{"kind":"PRIVATE_ATLAS_PROPERTY_INDEX","public_export_permitted":False,
            "official_plan_count":None,"documents_private":[{"document_sha256":self.sha,
            "official_plan_verified":None,"ground_verified":False,"registry_scope":"N",
            "observations_private":[{"page":1,"origin":"DEEP_NATIVE_LAYOUT",
                "qualified_title_candidates_private":[{"number":"00123","suffix":"A"}],
                "qualified_parcel_candidates_private":[{"number":"02","suffix":None}]}]}]})
        d=self.root/"case"
        case=prepare(self.idx,pdf,self.sha,1,d)
        self.case=d/"case.json"
        self.ctrl=self.root/"controls.json"
        points=[{"control_id":str(i),"source_evidence_sha256":"b"*64,"page":[x,y],
            "wgs84":[10+x*.00001,36-y*.00001],"role":role} for i,(x,y,role) in enumerate(
            [(0,0,"FIT"),(100,0,"FIT"),(0,100,"FIT"),(100,100,"CHECK")])]
        write_private_json(self.ctrl,{"kind":"PRIVATE_ATLAS_CONTROL_OBSERVATIONS",
            "case_sha256":sha(self.case),"document_sha256":self.sha,"page":1,"outline_ordinal":1,
            "outline_sha256":case["outlines_private"][0]["outline_sha256"],"control_points":points,
            "maximum_holdout_error_m":2})
        self.qa=self.root/"qa.json"
        assess(self.case,self.ctrl,self.qa)
        self.sel={"kind":"PRIVATE_ATLAS_CANDIDATE_SELECTION","index_sha256":sha(self.idx),
            "case_sha256":sha(self.case),"controls_sha256":sha(self.ctrl),"qa_sha256":sha(self.qa),
            "document_sha256":self.sha,"page":1,"outline_ordinal":1,"registry_scope":"N",
            "locality_key":"KELIBIA","title":{"number":"00123","suffix":"A"},"parcel":{"number":"02","suffix":None}}
    def run_stage(self,selection=None,duplicate=False,qa=None,controls=None):
        selection=selection or self.sel
        sp=self.root/"selection.json";write_private_json(sp,selection)
        item={"selection_path":str(sp),"case_path":str(self.case),
              "controls_path":str(controls or self.ctrl),"qa_path":str(qa or self.qa)}
        m=self.root/"manifest.json"
        write_private_json(m,{"kind":"PRIVATE_ATLAS_CANDIDATE_HANDOFF_MANIFEST","index_sha256":sha(self.idx),
            "items":[item,item] if duplicate else [item]})
        out=self.root/"handoff";r=stage_batch(self.idx,m,out)
        return r,json.loads((out/"candidate-import-draft.json").read_text())
    def test_end_to_end_candidate_only(self):
        r,b=self.run_stage()
        self.assertEqual(r["counts"]["staged"],1)
        x=b["records"][0]
        self.assertEqual(x["classification"],"CANDIDATE")
        self.assertEqual(x["titleReference"],"00123/a")
        self.assertIsNotNone(x["geometry4326"])
        self.assertFalse(x["allowBoundaryRedistribution"])
        self.assertFalse(r["database_updated"])
        self.assertFalse(r["import_authorized"])
    def test_suffix_cannot_be_dropped(self):
        self.sel["title"]["suffix"]=None
        r,b=self.run_stage()
        self.assertEqual(r["counts"]["unresolved"],1);self.assertEqual(b["records"],[])
    def test_duplicate_selection_is_retained_not_imported(self):
        r,b=self.run_stage(duplicate=True)
        self.assertEqual(r["counts"]["unresolved"],2);self.assertEqual(b["records"],[])
    def test_modified_qa_rejected_even_if_selection_rebound(self):
        q=json.loads(self.qa.read_text());q["qa"]["fit_rms_m"]=123
        p=self.root/"modified-qa.json";write_private_json(p,q);self.sel["qa_sha256"]=sha(p)
        r,b=self.run_stage(qa=p)
        self.assertEqual(r["counts"]["unresolved"],1);self.assertEqual(b["records"],[])
    def test_unreviewed_scope_rejected(self):
        self.sel["registry_scope"]="OTHER"
        r,b=self.run_stage()
        self.assertEqual(r["counts"]["unresolved"],1);self.assertEqual(b["records"],[])
