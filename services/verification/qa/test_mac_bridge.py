import json
from pathlib import Path
import shutil
import subprocess
import unittest
class Bridge(unittest.TestCase):
    def test_low_confidence_source_fields_are_visible_without_forcing_match(self):
        def document(lines):return {'format':'image','mayApproveOwnership':False,'pages':[{'page':1,'method':'ocr','lines':[{'text':t,'page':1,'confidence':0.5,'source':'ocr-fr'} for t in lines]}]}
        doc=document(['Acte de vente','Acheteur: Leila Ben Salem','Superficie vendue: 185.25 m2','Titre foncier: 555941'])
        front=document(['Nom et prénom: Leila Ben Salem','CIN: 00001122'])
        path=Path(__file__).with_name('compare_mac_pair.mjs')
        p=subprocess.run([shutil.which('node'),'--experimental-strip-types',str(path)],input=json.dumps({'documents':[doc],'front':front}).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
        report=json.loads(p.stdout)
        area=next(x for x in report['propertyFacts'] if x['key']=='soldAreaM2')
        self.assertEqual(area['value'],'185.25')
        self.assertEqual(area['status'],'LOW_CONFIDENCE')
        self.assertEqual(area['page'],1)
        self.assertFalse(report['mayPublish'])
        self.assertNotIn(report['assessment'],('ROLE_TEXT_MATCH_ONLY','NAME_ONLY_REVIEW'))

class MultiPageBridge(unittest.TestCase):
    def test_photo_continuation_page_uses_document_heading_from_first_page(self):
        def page(lines):return {'format':'image','mayApproveOwnership':False,'pages':[{'page':1,'method':'ocr','lines':[{'text':t,'page':1,'confidence':0.99,'source':'ocr-fr'} for t in lines]}]}
        docs=[page(['Acte de vente','Acheteur: Leila Ben Salem','Page 1 de 2']),page(['Superficie vendue: 185.25 m2','Page 2 de 2'])]
        front=page(['Nom et prénom: Leila Ben Salem','CIN: 00001122'])
        p=subprocess.run([shutil.which('node'),'--experimental-strip-types',str(Path(__file__).with_name('compare_mac_pair.mjs'))],input=json.dumps({'documents':docs,'front':front}).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
        report=json.loads(p.stdout)
        area=next(x for x in report['propertyFacts'] if x['key']=='soldAreaM2')
        self.assertEqual(area['value'],'185.25');self.assertEqual(area['page'],2)
        self.assertEqual(report['certificatePages'],2)

if __name__=='__main__':unittest.main()
