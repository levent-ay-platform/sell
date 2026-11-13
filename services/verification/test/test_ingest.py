import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ocr'))
from ingest import InvalidDocument, extract_document, _supplement_owner_ordinal_words


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
            page=out['pages'][0]
            self.assertFalse(page['spatialWordsTruncated'])
            self.assertGreater(len(page['ocrWords']),0)
            self.assertLessEqual(len(page['ocrWords']),1000)
            self.assertGreater(page['pageImageWidth'],200)
            self.assertGreater(page['pageImageHeight'],300)
            for word in page['ocrWords']:
                box=word['bbox']
                self.assertTrue(0<=box['left']<box['right']<=page['pageImageWidth'])
                self.assertTrue(0<=box['top']<box['bottom']<=page['pageImageHeight'])
                self.assertEqual(word['page'],1)
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



class TestFocusedOwnerOrdinalSafety(unittest.TestCase):
    """Only fabricated boxes and ordinals, never actual owner data."""
    @staticmethod
    def w(value,x,y,confidence=.98,page=1):
        return dict(text=value,page=page,confidence=confidence,
                    bbox=dict(left=x,top=y,right=x+30,bottom=y+17))

    def test_adds_only_missing_rightmost_ordinal_with_original_page_box(self):
        original=[self.w('1/2',910,570),self.w('اسم',770,590)]
        # Coordinates in crop-local frame (x+40,y+100 in original frame)
        extra=[self.w('1/1',870,140),
               self.w('CIN',730,180),
               self.w('00001122',780,180),
               self.w('1/2',870,470)]
        result,unsafe=_supplement_owner_ordinal_words(original,extra,
            left=40,top=100,original_width=1000,original_height=1500)
        self.assertFalse(unsafe)
        self.assertEqual(len(result),3)
        self.assertEqual(result[-1]['text'],'1/1')
        self.assertEqual(result[-1]['bbox']['top'],240)
        self.assertTrue(all('CIN'!=w['text'] for w in result))
        self.assertTrue(all('00001122'!=w['text'] for w in result))

    def test_wrong_column_or_low_confidence_is_not_added(self):
        original=[self.w('1/2',900,600)]
        added,unsafe=_supplement_owner_ordinal_words(original,
             [self.w('1/1',440,120),self.w('1/3',860,120,.56)],
             left=40,top=100,original_width=1000,original_height=1500)
        self.assertEqual(added,original)
        self.assertFalse(unsafe)

    def test_conflicting_duplicate_ordinals_fail_closed(self):
        original=[self.w('1/2',900,600)]
        rows=[self.w('1/2',860,40)]
        result,unsafe=_supplement_owner_ordinal_words(original,rows,
           left=40,top=100,original_width=1000,original_height=1500)
        self.assertTrue(unsafe)
        self.assertEqual(result,original)

    def test_same_missing_ordinal_twice_fails_closed(self):
        original=[self.w('1/2',900,600)]
        rows=[self.w('1/1',860,200),self.w('1/1',865,400)]
        result,unsafe=_supplement_owner_ordinal_words(original,rows,
           left=40,top=100,original_width=1000,original_height=1500)
        self.assertTrue(unsafe)
        self.assertEqual(result,original)

    def test_numbered_shares_and_8_digit_cin_never_count_as_ordinal(self):
        original=[self.w('1/2',900,600)]
        extras=[self.w('1/1000',860,200),self.w('00001122',860,290),
                self.w('1/0',860,330),self.w('03/04',860,370)]
        result,unsafe=_supplement_owner_ordinal_words(original,extras,
           left=40,top=100,original_width=1000,original_height=1500)
        self.assertEqual(result,original)
        self.assertFalse(unsafe)
