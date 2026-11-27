#!/usr/bin/env python3
"""Update ONLY the existing Mac pilot code; model and other services untouched.
Run tests first. Restart is explicit via --restart and existing launchd KeepAlive.
"""
import argparse,json,os,shutil,signal,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--runtime-root',type=Path,required=True);p.add_argument('--data-root',type=Path,required=True);p.add_argument('--node',required=True);p.add_argument('--restart',action='store_true');a=p.parse_args()
r=a.runtime_root.resolve()
if not (r/'services/verification/qa/mac_pilot_ocr_http.py').is_file():raise SystemExit('Existing runtime required')
backup=r.parent/('runtime-code-backup-'+time.strftime('%Y%m%d-%H%M%S'))
shutil.copytree(r/'services',backup/'services')
for name in ('ocr','qa','src'):
 shutil.copytree(ROOT/'services/verification'/name,r/'services/verification'/name,dirs_exist_ok=True,ignore=shutil.ignore_patterns('__pycache__','runtime.json','*.pyc'))
shutil.copytree(ROOT/'apps/admin',r/'apps/admin',dirs_exist_ok=True)
shutil.copytree(ROOT/'.runtime/python',r/'.runtime/python',dirs_exist_ok=True)
(r/'.runtime').mkdir(exist_ok=True)
shutil.copy2(ROOT/'.runtime/mac-vision',r/'.runtime/mac-vision')
a.data_root.mkdir(parents=True,mode=0o700,exist_ok=True);os.chmod(a.data_root,0o700)
revision=subprocess.run(['git','-C',str(ROOT),'rev-parse','HEAD'],capture_output=True,text=True,check=True).stdout.strip()
config={'MALAK_SOURCE_SHA':revision,'MALAK_PILOT_DATA':str(a.data_root.resolve()),'MALAK_PYTHON_DEPS':str(r/'.runtime/python'),'MALAK_VISION_BIN':str(r/'.runtime/mac-vision'),'MALAK_NODE_BIN':str(Path(a.node).resolve()),'MALAK_PUBLIC_ORIGIN':'https://malak-api.ayasofia.shop'}
file=r/'services/verification/qa/runtime.json';file.write_text(json.dumps(config));os.chmod(file,0o600)
print('Code backup:',backup)
if a.restart:
 result=subprocess.run(['lsof','-t','-iTCP:18443','-sTCP:LISTEN'],capture_output=True,text=True,check=True)
 for raw in result.stdout.split():
  pid=int(raw)
  command=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True,check=True).stdout
  if str(r/'services/verification/qa/mac_pilot_ocr_http.py') not in command:raise SystemExit('Unexpected listener; refusing restart')
  os.kill(pid,signal.SIGTERM)
 print('Restart requested for existing pilot listener only')
