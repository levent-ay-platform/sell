import hashlib
from pathlib import Path
import shutil
import tempfile
import unittest
from PIL import Image,ImageDraw
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_raster_review_case import prepare_scan,trace
from scripts.atlas_georeference_case import assess

@unittest.skipUnless(shutil.which("pdfinfo") and shutil.which("pdftoppm"),"Poppler required")
class RasterCaseTests(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup)
        self.root=Path(t.name).resolve()
        self.pdf=self.root/"scan.pdf"
        im=Image.new("RGB",(1000,850),"white")
        d=ImageDraw.Draw(im);d.rectangle((200,230,690,620),outline="black",width=8)
        im.save(self.pdf,"PDF",save_all=True,append_images=[im]*8)
        self.sha=hashlib.sha256(self.pdf.read_bytes()).hexdigest()
        self.index=self.root/"index.json"
        write_private_json(self.index,{"kind":"PRIVATE_ATLAS_PROPERTY_INDEX","public_export_permitted":False,
            "official_plan_count":None,"documents_private":[{"document_sha256":self.sha,"official_plan_verified":None,
                 "ground_verified":False,"observations_private":[]}]})
    def test_page_nine_scan_and_georef(self):
        out=self.root/"case"
        r=prepare_scan(self.index,self.pdf,self.sha,9,out)
        self.assertEqual(r["page"],9);self.assertEqual(r["scan_state"],"CANDIDATE")
        self.assertEqual(r["page_unit"],"PIXEL")
        self.assertEqual(len(r["outlines_private"]),1)
        self.assertEqual(hashlib.sha256((out/"page.png").read_bytes()).hexdigest(),r["page_render_private"]["sha256"])
        c=out/"case.json"
        pts=[{"control_id":str(i),"source_evidence_sha256":"b"*64,"page":[x,y],
              "wgs84":[10+x*.000001,36-y*.000001],"role":role} for i,(x,y,role) in enumerate(
              [(0,0,"FIT"),(1000,0,"FIT"),(0,1000,"FIT"),(1000,1000,"CHECK")])]
        controls=self.root/"controls.json"
        write_private_json(controls,{"kind":"PRIVATE_ATLAS_CONTROL_OBSERVATIONS",
            "case_sha256":hashlib.sha256(c.read_bytes()).hexdigest(),"document_sha256":self.sha,"page":9,
            "outline_ordinal":1,"outline_sha256":r["outlines_private"][0]["outline_sha256"],
            "control_points":pts,"maximum_holdout_error_m":2})
        result=assess(c,controls,self.root/"qa.json")
        self.assertEqual(result["qa"]["geometry_status"],"GEOREFERENCED_CANDIDATE")
        self.assertFalse(result["ground_verified"])
    def test_trace_preserves_original_and_rejects_mismatch(self):
        out=self.root/"case"
        r=prepare_scan(self.index,self.pdf,self.sha,1,out,crop=[300,300,1900,1600])
        c=out/"case.json";before=c.read_bytes()
        ann={"kind":"PRIVATE_ATLAS_MANUAL_PIXEL_TRACE","case_sha256":hashlib.sha256(before).hexdigest(),
             "document_sha256":self.sha,"page":1,"render_sha256":r["page_render_private"]["sha256"],
             "pixel_ring":[[400,400],[500,400],[500,500],[400,400]]}
        a=self.root/"annotation.json";write_private_json(a,ann)
        result=trace(c,a,self.root/"traced")
        self.assertEqual(result["outlines_private"][-1]["method"],"MANUAL_PIXEL_TRACE")
        self.assertEqual(c.read_bytes(),before)
        ann["render_sha256"]="0"*64
        b=self.root/"bad.json";write_private_json(b,ann)
        with self.assertRaisesRegex(ValueError,"TRACE_CASE_BINDING"):trace(c,b,self.root/"bad")
