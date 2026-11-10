import type {UntrustedDocumentExtraction} from './triage.ts';

/**
 * Parse ALREADY OCR-EXTRACTED text from a CPF paper certificate.
 * This does not authenticate documents, names, boundaries, ownership or signatures.
 * Only conservative matches are accepted; ambiguous fields stay absent.
 */
export function parseCpfOcrText(input: string): UntrustedDocumentExtraction {
  if (typeof input !== 'string' || input.length > 50000) throw new TypeError('Invalid OCR text size');
  const text = input
    .replace(/[٠-٩]/g, c => String(c.charCodeAt(0)-0x660))
    .replace(/[۰-۹]/g, c => String(c.charCodeAt(0)-0x6f0))
    .replace(/ـ/g, '')
    .replace(/[\u200e\u200f]/g, ' ')
    .replace(/[ \t]+/g,' ');
  const numeric = (re: RegExp): number | undefined => {
    const m = text.match(re);
    if (!m) return undefined;
    const n = Number(m[1]?.replace(/,/g, '.'));
    return Number.isFinite(n) && n>0 ? n : undefined;
  };
  const page = text.match(/صفحة\s*[:#]?\s*(\d{1,2})\s*(?:من|\/)\s*(\d{1,2})/u);
  const pageNumber = page ? Number(page[1]) : null;
  const pageTotal = page ? Number(page[2]) : null;
  const registryReference = text.match(/معر?ف\s*الرسم\s*العقاري\s*[:：]?\s*([0-9]{3,12})/u)?.[1];
  const areaSquareMetres = numeric(/المساحة\s*[:：]?\s*(\d+(?:[.,]\d+)?)\s*(?:م(?:تر)?(?:\s*مربع|²|2)?)?(?=\s|$|[،؛])/u);
  const ownershipShareUnits = numeric(/موضوع\s*الملكية\s*[:：]?\s*(\d+)\s*جزء/u);
  // Do not assume that 'التجزئة' means a verified total share denominator.
  const lastRegistryUpdatedAt = text.match(/تاريخ\s*(?:آخر\s*)?تحيين\s*[:：]?\s*(\d{4}\/\d{2}\/\d{2})/u)?.[1];
  return {
    kind: /شهادة\s*ملكية/u.test(text) ? 'CPF_OWNERSHIP_CERTIFICATE' : 'OTHER_OR_UNKNOWN',
    format: 'PAPER_SCAN', hasCev: false,
    declaredPageCount: pageTotal,
    receivedPageNumbers: pageNumber===null ? [] : [pageNumber],
    extracted: {
      ...(registryReference ? {registryReference} : {}),
      ...(areaSquareMetres !== undefined ? {areaSquareMetres} : {}),
      ...(ownershipShareUnits !== undefined ? {ownershipShareUnits} : {}),
      ...(lastRegistryUpdatedAt ? {lastRegistryUpdatedAt} : {})
    }
  };
}
