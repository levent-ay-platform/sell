/** Isolated microbenchmark for deterministic post-OCR parsing, NOT image OCR latency. */
import {performance} from 'node:perf_hooks';
import {extractCpfSmartFields} from '../src/smart-fields.ts';
const sample=[
  'شهادة ملكية','معرف الرسم العقاري: 654321','المساحة: 843 م',
  'موضوع الملكية: 512 جزء','التجزئة: 3000 جزء',
  'تاريخ آخر تحيين: 2014/07/13','صفحة 1 من 2',
  'Certificat de propriété','Titre foncier: 654321',
  'Superficie: 843 m²',
];
const page=sample.map((text,i)=>({text,page:1,confidence:0.98,source:'ocr-ar'}));
for(let i=0;i<300;i++) extractCpfSmartFields(page);
const timings=[];
for(let i=0;i<1500;i++){
  const start=performance.now();
  const result=extractCpfSmartFields(page);
  timings.push(performance.now()-start);
  if(result.mayUseForAutomaticApproval!==false) throw Error('Unsafe outcome');
}
timings.sort((a,b)=>a-b);
const at=q=>Math.round(timings[Math.floor((timings.length-1)*q)]*1000)/1000;
console.log(JSON.stringify({task:'post-OCR deterministic field extraction ONLY',runs:timings.length,syntheticLines:page.length,
  medianMs:at(0.5),p95Ms:at(0.95),p99Ms:at(0.99),
  limitation:'Does NOT measure camera capture, PDF extraction, OCR model accuracy or model inference speed'},null,2));
