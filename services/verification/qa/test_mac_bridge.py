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
if __name__=='__main__':unittest.main()
