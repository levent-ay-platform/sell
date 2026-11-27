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
        self.assertIn('DOCUMENT_TYPE_NOT_CONFIRMED',report['reasons'])

    def test_missing_printed_page_is_reported_even_when_classification_uncertain(self):
        def doc(lines):return {'format':'image','mayApproveOwnership':False,'pages':[{'page':1,'method':'ocr','lines':[{'text':t,'page':1,'confidence':.5,'source':'ocr-ar'} for t in lines]}]}
        p=subprocess.run([shutil.which('node'),'--experimental-strip-types',str(Path(__file__).with_name('compare_mac_pair.mjs'))],input=json.dumps({'documents':[doc(['شهادة ملكية','صفحة 1 من 2'])],'front':doc(['اللقب والاسم: الشمري مروان'])}).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
        report=json.loads(p.stdout)
        self.assertEqual(report['missingPages'],[2])
        self.assertIn('CERTIFICATE_PAGES_MISSING',report['reasons'])

class MultiPageBridge(unittest.TestCase):
    def test_missing_page_beyond_upload_limit_still_warns(self):
        pages=[{'page':n,'method':'ocr','lines':[{'text':t,'page':n,
            'confidence':.99,'source':'ocr-fr'} for t in
            ['Certificat de propriete',f'Page {n} of 9']]} for n in range(1,9)]
        doc={'format':'pdf','mayApproveOwnership':False,'pages':pages}
        front={'format':'image','mayApproveOwnership':False,'pages':[{
            'page':1,'method':'ocr','lines':[{'text':'Nom et prénom: Leila Ben Salem',
            'page':1,'confidence':.99,'source':'ocr-fr'}]}]}
        p=subprocess.run([shutil.which('node'),'--experimental-strip-types',str(Path(__file__).with_name('compare_mac_pair.mjs'))],input=json.dumps({'documents':[doc],'front':front}).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
        report=json.loads(p.stdout)
        self.assertIn('CERTIFICATE_PAGES_MISSING',report['reasons'])
        self.assertTrue(all(n<=8 for n in report['missingPages']))

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
