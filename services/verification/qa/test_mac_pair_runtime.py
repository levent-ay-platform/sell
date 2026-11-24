import struct
import unittest
from mac_pair_runtime import frame
class Frames(unittest.TestCase):
    def test_arabic_identity_adapter_reaches_existing_name_matcher(self):
        import json,shutil,subprocess
        from pathlib import Path
        from mac_pair_runtime import identity_document
        front=identity_document({'surname':'الشمري','givenName':'مروان',
            'cinFull':None,'confidencePercent':99})
        document={'format':'image','mayApproveOwnership':False,'pages':[{'page':1,
            'method':'ocr','lines':[{'text':t,'page':1,'confidence':.99,'source':'ocr-ar'}
             for t in ['شهادة ملكية','المالك: الشمري مروان','صفحة 1 من 1']]}]}
        p=subprocess.run([shutil.which('node'),'--experimental-strip-types',
            str(Path(__file__).with_name('compare_mac_pair.mjs'))],
            input=json.dumps({'documents':[document],'front':front}).encode(),
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
        result=json.loads(p.stdout)
        self.assertIn(result['assessment'],['NAME_ONLY_REVIEW','ROLE_TEXT_MATCH_ONLY'])
        self.assertEqual(result['matchedRole'],'REGISTERED_HOLDER')
    def test_existing_android_binary_contract(self):
        pdf=b'%PDF-1.4'+b' '*30
        jpg=b'\xff\xd8\xff'+b' '*30
        data=bytes([1,1])+struct.pack('>I',len(pdf))+pdf+struct.pack('>I',len(jpg))+jpg
        files=frame(data)
        self.assertEqual([f[1] for f in files],['application/pdf','image/jpeg'])
        for bad in [data+b'x',data[:-1],bytes([2])+data[1:],bytes([1,0])+data[2:]]:
            with self.assertRaises(ValueError):frame(bad)
    def test_property_views_are_separate_and_conflict_blocks_area(self):
        import json,shutil,subprocess
        from pathlib import Path
        from mac_pair_runtime import identity_document
        front=identity_document({'surname':'الشمري','givenName':'مروان',
            'cinFull':None,'confidencePercent':99})
        def image(rows,confidence=.99):
            return {'format':'image','mayApproveOwnership':False,'pages':[{'page':1,'method':'ocr',
                'lines':[{'page':1,'text':t,'confidence':confidence,'source':'ocr-ar'} for t in rows]}]}
        document=image(['شهادة ملكية','المالك: الشمري مروان','صفحة 1 من 1'])
        views=[image(['المساحة: 1000 م م التجزئة: 4000 جزء','مجموع القطع: 1 القطع عدد: 702','صبغة العقار: فلاحية']),
            image(['المساحة: 1008 م م','المالك: شخص آخر'])]
        result=json.loads(subprocess.run([shutil.which('node'),'--experimental-strip-types',
            str(Path(__file__).with_name('compare_mac_pair.mjs'))],
            input=json.dumps({'documents':[document],'front':front,'propertyViews':views}).encode(),
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout)
        self.assertIn('CPF_PROPERTY_AREA_CONFLICT',result['reasons'])
        self.assertEqual(result['matchedRole'],'REGISTERED_HOLDER')
        facts={f['key']:f for f in result['propertyFacts']+result['extendedPropertyFacts']}
        self.assertNotIn('registeredAreaM2',facts)
        self.assertEqual(facts['rightsDenominator']['value'],'4000')
        self.assertEqual(facts['registeredParcelCount']['status'],'LOW_CONFIDENCE')
        self.assertEqual(facts['registeredLandCategory']['value'],'فلاحية')
        self.assertFalse(result['ownershipVerified'])
    def test_property_crop_requires_both_distinct_nearby_labels(self):
        from cpf_property_views import property_regions
        def row(text,y):
            return {'text':text,'bbox':{'left':20,'right':800,'top':y,'bottom':y+35}}
        page={'pageImageWidth':1000,'pageImageHeight':1500,
              'lines':[row('التجزئة: 500 جزء',300),row('القطع عدد: 702',420)]}
        self.assertEqual(len(property_regions(page,1000,1500)),3)
        self.assertEqual(property_regions({**page,'lines':page['lines'][:1]},1000,1500),[])
        self.assertEqual(property_regions({**page,'lines':[page['lines'][0],row('القطع عدد: 702',1300)]},1000,1500),[])
    def test_unreadable_printed_count_is_not_a_missing_physical_page(self):
        import json,shutil,subprocess
        from pathlib import Path
        from mac_pair_runtime import identity_document
        front=identity_document({'surname':'الشمري','givenName':'مروان',
            'cinFull':None,'confidencePercent':99})
        doc={'format':'pdf','mayApproveOwnership':False,'pages':[{'page':1,'method':'ocr',
            'lines':[{'page':1,'text':'شهادة ملكية','confidence':.99,'source':'ocr-ar'},
                {'page':1,'text':'صفحة 1 من 8','confidence':.5,'source':'ocr-ar'}]}]}
        report=json.loads(subprocess.run([shutil.which('node'),'--experimental-strip-types',
            str(Path(__file__).with_name('compare_mac_pair.mjs'))],
            input=json.dumps({'documents':[doc],'front':front}).encode(),
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout)
        self.assertIn('CERTIFICATE_PAGE_COUNT_UNREADABLE',report['reasons'])
        self.assertNotIn('CERTIFICATE_PAGES_MISSING',report['reasons'])
        self.assertEqual(report['missingPages'],[])
        self.assertFalse(report['ownershipVerified'])
if __name__=='__main__':unittest.main()
