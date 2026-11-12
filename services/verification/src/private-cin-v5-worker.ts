/** Huawei-only v5 worker adapter. Reuses the existing process-group timeout,
 * private 0700 tmpfs, capped output, zeroization and one-job-at-a-time gate.
 * No personal data is emitted to logs or test artifacts.
 */
import {createPrivatePythonOcrWorker} from './private-ocr-worker.ts';
import type {PrivateOcrWorkerConfig} from './private-ocr-worker.ts';
import type {CinFields,CinV5Worker} from './private-cin-v5-service.ts';

export function createHuaweiPrivateCinV5Worker(config:PrivateOcrWorkerConfig):CinV5Worker {
  const privateWorker=createPrivatePythonOcrWorker(config);
  return {async readFront(bytes:Uint8Array,mime:'image/jpeg'|'image/png'):Promise<CinFields>{
    const raw=await privateWorker.extract(bytes,mime);
    if(!raw||typeof raw!=='object'||Array.isArray(raw))
      throw Error('PRIVATE_CIN_V5_INVALID_OUTPUT');
    const obj=raw as Record<string,unknown>;
    if(Object.keys(obj).some(k=>![
      'surname','givenName','cinLast4','birthDate','confidencePercent'
    ].includes(k)))throw Error('PRIVATE_CIN_V5_INVALID_OUTPUT');
    if(!Number.isInteger(obj.confidencePercent))
      throw Error('PRIVATE_CIN_V5_INVALID_OUTPUT');
    return {
      surname:obj.surname as string|null,
      givenName:obj.givenName as string|null,
      cinLast4:obj.cinLast4 as string|null,
      birthDate:obj.birthDate as string|null,
      confidencePercent:obj.confidencePercent as number
    };
  }};
}
