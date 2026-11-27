/**
 * One OCR subprocess at a time for each dedicated worker instance. Reject
 * rather than queue: no unbounded background memory, concurrent rasterizers
 * or latent requests when Huawei is under pressure. Production must also
 * enforce a cross-instance gate and host/container CPU/RAM limits.
 */
import type {PrivateOcrWorker} from './seller-ocr-service.ts';

export function exclusivePrivateOcrWorker(inner:PrivateOcrWorker):PrivateOcrWorker {
  if(!inner || typeof inner.extract!=='function')throw Error('OCR_WORKER_REQUIRED');
  let busy=false;
  return {
    async extract(bytes,mime) {
      if(busy)throw Error('PRIVATE_OCR_BUSY');
      busy=true;
      try{return await inner.extract(bytes,mime);}
      finally{busy=false;}
    }
  };
}
