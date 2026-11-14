import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {createPrivatePythonOcrWorker} from '../src/private-ocr-worker.ts';
import {comparePrivateCertificateIdentityOcr} from '../src/private-document-comparison.ts';
if(process.env.MALAK_SYNTHETIC_OCR!=='yes')throw Error('SYNTHETIC_ONLY');
const script=fileURLToPath(new URL('../ocr/ingest.py',import.meta.url));
function pdf(lines:readonly string[]):Buffer {
 const content=lines.map((line,i)=>'BT /F1 14 Tf 60 '+(730-i*28)+' Td ('+line.replace(/[()\\]/g,'')+') Tj ET').join('\n');
 const objs=['<< /Type /Catalog /Pages 2 0 R >>',
  '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
  '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  '<< /Length '+Buffer.byteLength(content)+' >>\nstream\n'+content+'\nendstream'];
 let s='%PDF-1.4\n'; const offsets=[0];
 for(let i=0;i<objs.length;i++){offsets.push(Buffer.byteLength(s));s+=(i+1)+' 0 obj\n'+objs[i]+'\nendobj\n';}
 const ptr=Buffer.byteLength(s);s+='xref\n0 '+offsets.length+'\n0000000000 65535 f \n';
 for(const o of offsets.slice(1))s+=String(o).padStart(10,'0')+' 00000 n \n';
 s+='trailer\n<< /Size '+offsets.length+' /Root 1 0 R >>\nstartxref\n'+ptr+'\n%%EOF\n';
 return Buffer.from(s);
}
test('real dual synthetic PDFs: preliminary OCR text agreement, mismatch and missing name never verify ownership',async()=>{
 const temp=await mkdtemp(join(tmpdir(),'malak-dual-ci-'));
 const worker=createPrivatePythonOcrWorker({tmpRoot:temp,scriptPath:script,
   pythonPath:process.env.MALAK_OCR_PYTHON??'/usr/bin/python3',timeoutMs:30000});
 try{
  const cert=await worker.extract(pdf(['Certificat de propriete','Proprietaire: Amira Ben Salem',
   'Titre foncier: 987654','Superficie: 240 m2']),'application/pdf');
  const same=await worker.extract(pdf(['Carte nationale identite','Nom et prenom: Amira Ben Salem']),'application/pdf');
  const report=comparePrivateCertificateIdentityOcr(cert,same);
  assert.equal(report.assessment,'PRELIMINARY_TEXT_AGREEMENT');
  assert.equal(report.ownershipVerified,false);assert.equal(report.identityVerified,false);
  assert.equal(report.mayPublish,false);assert.equal(report.requiresHumanReview,true);
  const different=await worker.extract(pdf(['Carte nationale identite','Nom et prenom: Amira Gharbi']),'application/pdf');
  assert.equal(comparePrivateCertificateIdentityOcr(cert,different).assessment,'TEXT_DIFFERENCE_NEEDS_REVIEW');
  const unknown=await worker.extract(pdf(['Carte nationale identite','Numero CIN: 00123456']),'application/pdf');
  assert.equal(comparePrivateCertificateIdentityOcr(cert,unknown).assessment,'INSUFFICIENT_OR_AMBIGUOUS');
 }finally{await rm(temp,{recursive:true,force:true});}
});
