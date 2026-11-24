import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject,NameObject
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_georeference_case import prepare,assess

class CaseTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup)
        self.root=Path(t.name).resolve()
        self.pdf=self.root/"plan.pdf"
        w=PdfWriter();page=w.add_blank_page(200,150)
        s=DecodedStreamObject();s.set_data(b"20 30 60 40 re S")
        page[NameObject("/Contents")]=w._add_object(s)
        with self.pdf.open("wb") as f:w.write(f)
        self.sha=hashlib.sha256(self.pdf.read_bytes()).hexdigest()
        self.idx=self.root/"index.json"
        write_private_json(self.idx,{"kind":"PRIVATE_ATLAS_PROPERTY_INDEX","public_export_permitted":False,
          "official_plan_count":None,"documents_private":[{"document_sha256":self.sha,"ground_verified":False,
           "official_plan_verified":None,"observations_private":[]}]})
    def case(self,render=False):
        d=self.root/"case"
        r=prepare(self.idx,self.pdf,self.sha,1,d,render=render)
        self.assertEqual(len(r["outlines_private"]),1)
        p=d/"case.json"
        return r,p
    def controls(self,case,p):
        def point(x,y,role,n):
            return {"page":[x,y],"wgs84":[10+x*.00001,36-y*.00001],"role":role,
                "control_id":str(n),"source_evidence_sha256":"b"*64}
        return {"kind":"PRIVATE_ATLAS_CONTROL_OBSERVATIONS","case_sha256":hashlib.sha256(p.read_bytes()).hexdigest(),
            "document_sha256":self.sha,"page":1,"outline_ordinal":1,
            "outline_sha256":case["outlines_private"][0]["outline_sha256"],"maximum_holdout_error_m":2,
            "control_points":[point(0,0,"FIT",1),point(100,0,"FIT",2),point(0,100,"FIT",3),point(100,100,"CHECK",4)]}
    def test_document_bound_case_qa(self):
        c,p=self.case();controls=self.controls(c,p)
        path=self.root/"controls.json";write_private_json(path,controls)
        r=assess(p,path,self.root/"qa.json")
        self.assertEqual(r["qa"]["geometry_status"],"GEOREFERENCED_CANDIDATE")
        self.assertFalse(r["ground_verified"])
        self.assertFalse(r["control_source_authenticity_verified"])
        self.assertEqual(r["title_parcel_link"],"UNVERIFIED")
        self.assertEqual((self.root/"qa.json").stat().st_mode&0o777,0o600)
    def test_wrong_case_and_wrong_outline_rejected(self):
        c,p=self.case();controls=self.controls(c,p);controls["case_sha256"]="f"*64
        path=self.root/"controls.json";write_private_json(path,controls)
        with self.assertRaisesRegex(ValueError,"CASE_BINDING"):assess(p,path,self.root/"qa.json")
        controls=self.controls(c,p);controls["outline_sha256"]="f"*64
        path=self.root/"controls2.json";write_private_json(path,controls)
        with self.assertRaisesRegex(ValueError,"OUTLINE_BINDING"):assess(p,path,self.root/"qa.json")
    def test_bad_holdout_does_not_promote(self):
        c,p=self.case();controls=self.controls(c,p)
        controls["control_points"][-1]["wgs84"]=[10.1,36.1]
        path=self.root/"controls.json";write_private_json(path,controls)
        self.assertEqual(assess(p,path,self.root/"qa.json")["qa"]["geometry_status"],"GEOREFERENCE_REVIEW_REQUIRED")
    def test_modified_pdf_rejected(self):
        with self.pdf.open("ab") as f:f.write(b"\n")
        with self.assertRaisesRegex(ValueError,"PDF_CHANGED"):prepare(self.idx,self.pdf,self.sha,1,self.root/"case")
    @unittest.skipUnless(shutil.which("pdftoppm"),"Poppler required")
    def test_real_private_render(self):
        c,p=self.case(render=True)
        d=p.parent
        self.assertEqual(hashlib.sha256((d/"page.png").read_bytes()).hexdigest(),c["page_render_private"]["sha256"])
        self.assertIn("data:image/png;base64,",(d/"page-review.html").read_text())
        self.assertEqual((d/"page-review.html").stat().st_mode&0o777,0o600)
