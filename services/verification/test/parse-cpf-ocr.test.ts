import test from 'node:test';
import assert from 'node:assert/strict';
import {parseCpfOcrText} from '../src/parse-cpf-ocr.ts';
import {triageIndividualClaim} from '../src/triage.ts';

test('conservative parser extracts distinct area, ownership units, title and page count',()=>{
 const text='شهادة ملكية\nمعرف الرسم العقاري: 987654\nالمساحة: 843 م\nالتجزئة: 3000 جزء\nموضوع الملكية: 512 جزء\nصفحة 1 من 2\nتاريخ آخر تحيين: 2014/07/13';
 const parsed=parseCpfOcrText(text);
 assert.equal(parsed.kind,'CPF_OWNERSHIP_CERTIFICATE');
 assert.equal(parsed.extracted?.registryReference,'987654');
 assert.equal(parsed.extracted?.areaSquareMetres,843);
 assert.equal(parsed.extracted?.ownershipShareUnits,512);
 assert.equal(parsed.extracted?.ownershipTotalUnits,undefined);
 assert.deepEqual(parsed.receivedPageNumbers,[1]);
 const checked=triageIndividualClaim({claimantKind:'PERSON',capacity:'SOLE_OWNER',document:parsed});
 assert.ok(checked.findings.includes('MISSING_PAGES'));
 assert.equal(checked.publicationAllowed,false);
});
test('Arabic Indic digits are parsed, not interpreted as identity validation',()=>{
 const parsed=parseCpfOcrText('شهادة ملكية\nصفحة ١ من ٢\nالمساحة: ٨٤٣ م\nموضوع الملكية: ٥١٢ جزء');
 assert.equal(parsed.declaredPageCount,2);
 assert.equal(parsed.extracted?.areaSquareMetres,843);
 assert.equal(parsed.extracted?.ownershipShareUnits,512);
});
test('ambiguous OCR text remains unknown and cannot approve',()=>{
 const parsed=parseCpfOcrText('وثيقة مموهة\nمجموع القطع: 02\nالمساحة غير معروفة');
 assert.equal(parsed.kind,'OTHER_OR_UNKNOWN');
 assert.equal(parsed.extracted?.areaSquareMetres,undefined);
 const result=triageIndividualClaim({claimantKind:'PERSON',capacity:'SOLE_OWNER',document:parsed});
 assert.equal(result.lane,'NEEDS_INFORMATION');
 assert.equal(result.publicationAllowed,false);
});
test('unsafe oversized extraction is rejected',()=>{
 assert.throws(()=>parseCpfOcrText('x'.repeat(50001)),/Invalid OCR text size/);
});
