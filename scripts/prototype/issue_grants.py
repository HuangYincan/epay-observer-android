#!/usr/bin/env python3
"""Issue short-lived grants immediately before the explicit emulator run."""
from pathlib import Path
import argparse
import json
import os
import ssl
import urllib.request
p = argparse.ArgumentParser()
p.add_argument('directory', type=Path)
a = p.parse_args()
lab = a.directory.resolve()
os.umask(63)
admin = json.loads((lab / 'gateway-admin.json').read_text())
ca = ssl.create_default_context(cafile=str(lab / 'ca.pem'))
grants = []
for receiver in ['lab-receiver-wx', 'lab-receiver-ali']:
    request = urllib.request.Request('https://localhost:18443/api/admin/observer/grants', json.dumps({'schema_version': 1, 'receiver_id': receiver, 'mode': 'replace'}).encode(), {'Authorization': 'Bearer ' + admin['token'], 'Content-Type': 'application/json'})
    data = json.load(urllib.request.urlopen(request, context=ca, timeout=10))
    token = data.pop('token')
    grants.append({'scope': data, 'token': token})
(lab / 'avd-grants.json').write_text(json.dumps(grants))
print('Two short-lived pairing grants saved privately; no credentials printed.')
