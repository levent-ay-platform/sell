#!/usr/bin/env python3
"""Provision NON-PERSISTENT Vault DEV mode for CI only. Never use in production."""
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import time

if os.environ.get('MALAK_DISPOSABLE_VAULT') != 'yes' or not os.environ.get('RUNNER_TEMP'):
    raise SystemExit('DISPOSABLE_GITHUB_VAULT_ONLY')
URL='http://127.0.0.1:18200/v1/'
ROOT='MALAK_CI_DEV_ROOT_ONLY'   # FIXED, PUBLIC, EPHEMERAL TEST-ONLY dev server token

def call(path,payload,token=ROOT):
    req=Request(URL+path,method='POST',data=json.dumps(payload).encode(),
                headers={'X-Vault-Token':token,'Content-Type':'application/json'})
    try:
        with urlopen(req,timeout=7) as resp:
            data=resp.read(65536)
            return json.loads(data) if data else {}
    except HTTPError as err:
        raise RuntimeError(f'Vault CI setup failed: http {err.code} at restricted setup path') from None

for _ in range(80):
    try:
        with urlopen(URL+'sys/health',timeout=2) as resp:
            if resp.status==200: break
    except Exception:
        time.sleep(.25)
else: raise SystemExit('DISPOSABLE_VAULT_NOT_READY')

call('sys/mounts/transit',{'type':'transit'})
call('transit/keys/malak-evidence',{'type':'aes256-gcm96','derived':True,
                                   'exportable':False,'allow_plaintext_backup':False})
policy='''
path "transit/datakey/plaintext/malak-evidence" { capabilities = ["update"] }
path "transit/decrypt/malak-evidence" { capabilities = ["update"] }
'''
call('sys/policies/acl/malak-escrow-ci',{'policy':policy})
created=call('auth/token/create',{'policies':['malak-escrow-ci'],'ttl':'15m','explicit_max_ttl':'15m'})
app=created.get('auth',{}).get('client_token')
if not isinstance(app,str) or len(app)<12:raise SystemExit('DISPOSABLE_VAULT_APP_TOKEN_MISSING')
folder=Path(os.environ['RUNNER_TEMP'])/'malak-vault-ci'
folder.mkdir(mode=0o700,exist_ok=True)
folder.chmod(0o700)
secret=folder/'app-token'
fd=os.open(secret,os.O_CREAT|os.O_WRONLY|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f: f.write(app)
print('PASS: isolated derived nonexportable Vault Transit key and scoped app token provisioned (token not printed)')
