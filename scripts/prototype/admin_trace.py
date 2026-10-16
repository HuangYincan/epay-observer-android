#!/usr/bin/env python3
"""Authenticated local lab trace/pause; never confirms an order or rewrites evidence."""
import argparse
import json
from pathlib import Path
import ssl
import urllib.request
from lab_state import load_runtime, require_container

p=argparse.ArgumentParser()
p.add_argument('directory',type=Path)
p.add_argument('receiver',choices=['lab-receiver-wx','lab-receiver-ali'])
p.add_argument('--pause',action='store_true')
p.add_argument('--resume',action='store_true')
p.add_argument('--reason',choices=['incident','maintenance','review_completed'])
a=p.parse_args()
if a.pause and a.resume:p.error('choose pause or resume')
if (a.pause or a.resume) and not a.reason:p.error('control changes require an explicit reason')
lab=a.directory.resolve();meta=load_runtime(lab);require_container(meta,'gateway')
admin=json.loads((lab/'gateway-admin.json').read_text());ca=ssl.create_default_context(cafile=str(lab/'ca.pem'))
def call(path,data):
    request=urllib.request.Request('https://localhost:18443/api/admin/observer/'+path,json.dumps(data).encode(),{'Content-Type':'application/json','Authorization':'Bearer '+admin['token']})
    return json.load(urllib.request.urlopen(request,context=ca,timeout=10))
if a.pause or a.resume:
    result=call('confirmation',{'schema_version':1,'receiver_id':a.receiver,'paused':a.pause,'reason_code':a.reason})
    print(json.dumps(result))
trace=call('events/query',{'schema_version':1,'receiver_id':a.receiver,'before_event_pk':0,'limit':100})
(lab/('admin-trace-'+a.receiver+'.json')).write_text(json.dumps(trace,indent=2))
print(json.dumps(trace,indent=2))
