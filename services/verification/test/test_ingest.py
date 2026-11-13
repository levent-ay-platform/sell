import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ocr'))
from ingest import InvalidDocument, extract_document


def simple_native_pdf(path:Path, text='Certificat de propriete - Superficie 843 m2'):
    rawtext=text.replace('\\','\\\\').replace('(','\\(').replace(')','\\)')
    stream=f'BT /F1 18 Tf 72 700 Td ({rawtext}) Tj ET'.encode()
    objs=[
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream'
    ]
    data=b'%PDF-1.4\n';offsets=[0]
    for index,obj in enumerate(objs,1):
        offsets.append(len(data))
        data+=f'{index} 0 obj\n'.encode()+obj+b'\nendobj\n'
    start=len(data)
    data+=f'xref\n0 {len(offsets)}\n'.encode()+b'0000000000 65535 f \n'
    for offset in offsets[1:]:
        data+=f'{offset:010d} 00000 n \n'.encode()
    data+=f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode()
    path.write_bytes(data)


def synthetic_image(path:Path):
    from PIL import Image,ImageDraw,ImageFont
    im=Image.new('RGB',(1600,1400),'white')
    dr=ImageDraw.Draw(im)
    font='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
    if not Path(font).exists():
        font='/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf'
    f=ImageFont.truetype(font,62)
    for n,line in enumerate([
        'شهادة ملكية',
        'Superficie: 843 m2',
        'Titre foncier: 987654',
        'صفحة 1 من 2',
        'المساحة: 843 م',
    ]):
        dr.text((110,90+n*185),line,fill='black',font=f)
    im.save(path)

class TestMalakOcr(unittest.TestCase):
    def test_native_pdf_fast_path(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/'native.pdf';simple_native_pdf(f)
            t=time.perf_counter();out=extract_document(str(f));dt=round((time.perf_counter()-t)*1000,2)
            self.assertEqual(out['pages'][0]['method'],'pdf-text')
            self.assertTrue(any('843' in l['text'] for l in out['pages'][0]['lines']))
            self.assertEqual(out['mayApproveOwnership'],False)
            print(json.dumps({'benchmark':'synthetic_native_pdf_text_only','milliseconds':dt,'note':'not a real Tunisian document'}))

    def test_image_ocr_path(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/'fictional.png';synthetic_image(f)
            t=time.perf_counter();out=extract_document(str(f));dt=round((time.perf_counter()-t)*1000,2)
            self.assertEqual(out['pages'][0]['method'],'ocr')
            self.assertGreater(len(out['pages'][0]['lines']),0)
            full_text=' '.join(x['text'] for x in out['pages'][0]['lines'])
            self.assertIn('987654', full_text, 'clear synthetic title reference should survive OCR')
            self.assertIn('843', full_text, 'clear synthetic area should survive OCR')
            self.assertTrue(all(0<=l['confidence']<=1 for l in out['pages'][0]['lines']))
            boxes=[line['bbox'] for line in out['pages'][0]['lines'] if 'bbox' in line]
            self.assertGreater(len(boxes),0, 'Tesseract page scan should retain text boxes')
            self.assertTrue(all(0<=b['left']<b['right'] and 0<=b['top']<b['bottom']
                                for b in boxes))
            self.assertEqual(out['mayApproveOwnership'],False)
            # Strict text accuracy is evaluated in a separate golden corpus. OCR is imperfect.
            print(json.dumps({'benchmark':'synthetic_image_ocr','milliseconds':dt,
              'ocrLines':len(out['pages'][0]['lines']),
              'note':'single synthetic image, not real-document latency'}))

    def test_image_only_pdf_goes_through_ocr(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);png=p/'fictional.png';synthetic_image(png)
            with Image.open(png) as i:i.save(p/'scanned.pdf','PDF',resolution=160.0)
            o=extract_document(str(p/'scanned.pdf'))
            self.assertEqual(o['pages'][0]['method'],'ocr')
            self.assertGreater(len(o['pages'][0]['lines']),0)

    def test_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            real=Path(d)/'real.pdf';simple_native_pdf(real)
            link=Path(d)/'linked.pdf';link.symlink_to(real)
            with self.assertRaisesRegex(InvalidDocument,'INVALID_FILE'):
                extract_document(str(link))

    def test_fake_extensions_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/'fake.pdf';f.write_bytes(b'not a real PDF ' * 20)
            with self.assertRaisesRegex(InvalidDocument,'UNSUPPORTED_DOCUMENT_TYPE'):
                extract_document(str(f))

    def test_oversize_rejected_before_processing(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/'large.pdf'
            with f.open('wb') as h:
                h.write(b'%PDF-1.4\n');h.seek(12*1024*1024 + 1);h.write(b'0')
            with self.assertRaisesRegex(InvalidDocument,'INVALID_FILE_SIZE'):
                extract_document(str(f))

    def test_encrypted_or_corrupt_pdf_is_never_assumed_valid(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/'broken.pdf';f.write_bytes(b'%PDF-1.4\ntruncated and invalid contents')
            with self.assertRaises(InvalidDocument):
                extract_document(str(f))

    def test_force_ocr_bypasses_native_fast_path(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/'native.pdf';simple_native_pdf(f)
            out=extract_document(str(f),mode='force-ocr')
            self.assertEqual(out['pages'][0]['method'],'ocr')

if __name__=='__main__':unittest.main()
