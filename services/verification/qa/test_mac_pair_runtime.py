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
if __name__=='__main__':unittest.main()
