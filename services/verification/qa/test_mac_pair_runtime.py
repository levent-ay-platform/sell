import struct
import unittest
from mac_pair_runtime import frame
class Frames(unittest.TestCase):
    def test_existing_android_binary_contract(self):
        pdf=b'%PDF-1.4'+b' '*30
        jpg=b'\xff\xd8\xff'+b' '*30
        data=bytes([1,1])+struct.pack('>I',len(pdf))+pdf+struct.pack('>I',len(jpg))+jpg
        files=frame(data)
        self.assertEqual([f[1] for f in files],['application/pdf','image/jpeg'])
        for bad in [data+b'x',data[:-1],bytes([2])+data[1:],bytes([1,0])+data[2:]]:
            with self.assertRaises(ValueError):frame(bad)
if __name__=='__main__':unittest.main()
