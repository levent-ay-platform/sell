#!/usr/bin/env python3
"""Build a deterministic, offline-testable CPF source package. No live deployment."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'services/verification'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def build(out,node):
    if out.exists():
        raise SystemExit("Output directory must not exist")
    out.mkdir(parents=True)
    revision=subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip()
    paths=[p.relative_to(SOURCE) for p in (SOURCE/'src').rglob('*.ts')]
    paths.extend(map(Path,['qa/compare_mac_pair.mjs','qa/analysis_revision.py','test/document-family-fields-v2.test.ts']))
    for rel in sorted(paths):
        dest=out/rel;dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(SOURCE/rel,dest)
    def doc(rows,fmt):
        return {'format':fmt,'mayApproveOwnership':False,'pages':[{'page':1,'method':'ocr','lines':[{'text':t,'page':1,'confidence':0.99,'source':'ocr-ar'} for t in rows]}]}
    payload={'documents':[doc(['شهادة ملكية','معرف الرسم العقاري: 999901','المساحة: 37000 م م التجزئة: 56000 جزء','مجموع القطع: 1 القطع عدد: 312','صبغة العقار: فلاحية','موقع العقار: مشيخة المثال معتمدية الحمامات ولاية نابل'],'pdf')],'front':doc(['اللقب والاسم: مثال تجريبي','CIN: 00011223'],'image')}
    report=json.loads(subprocess.check_output([node,'--experimental-strip-types',str(out/'qa/compare_mac_pair.mjs')],input=json.dumps(payload,ensure_ascii=False).encode(),timeout=30))
    facts={f['key']:f['value'] for f in report['propertyFacts']+report['extendedPropertyFacts']}
    expected={'registeredAreaM2':'37000','rightsDenominator':'56000','registeredPlotNumber':'312','registeredParcelCount':'1','registeredLandCategory':'فلاحية','propertyGovernorate':'نابل','propertyDelegation':'الحمامات','historicalLocality':'المثال'}
    if any(facts.get(k)!=v for k,v in expected.items()):
        raise SystemExit('Synthetic pair field contract failed')
    if any(report.get(k) is not False for k in ('ownershipVerified','identityVerified','mayPublish')):
        raise SystemExit('Synthetic pair safety contract failed')
    subprocess.run([node,'--experimental-strip-types','--test',str(out/'test/document-family-fields-v2.test.ts')],check=True,timeout=90)
    manifest={'schemaVersion':1,'documentEngineRevision':revision,'scope':'isolated CPF TypeScript matcher; not an installer','files':{str(rel):sha(out/rel) for rel in sorted(paths)},'tests':{'syntheticPair':'PASS','documentFamily':'PASS'}}
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('CPF_BUNDLE_VERIFIED='+revision)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',required=True,type=Path)
    p.add_argument('--node',default='node')
    args=p.parse_args()
    build(args.output.resolve(),args.node)
