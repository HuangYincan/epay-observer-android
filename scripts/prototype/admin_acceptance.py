#!/usr/bin/env python3
"""Exercise ordinary order + paused Android receipt; preserves all historical evidence."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import ssl
import subprocess
import urllib.parse
import urllib.request
from lab_state import load_runtime, require_container

os.umask(0o077)
p = argparse.ArgumentParser()
p.add_argument('directory', type=Path)
p.add_argument('serial')
a = p.parse_args()
assert a.serial.startswith('emulator-') and a.serial[9:].isdigit()
lab = a.directory.resolve()
meta = load_runtime(lab)
require_container(meta, 'gateway')
accounts = json.loads((lab / 'private-accounts.json').read_text())
admin = json.loads((lab / 'gateway-admin.json').read_text())
ca = ssl.create_default_context(cafile=str(lab / 'ca.pem'))
jar = http.cookiejar.LWPCookieJar(str(lab / 'newapi-cookies'))
jar.load(ignore_discard=True, ignore_expires=True)
http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
r = urllib.request.Request('http://127.0.0.1:13000/api/user/login', json.dumps(accounts['newapi_root']).encode(), {'Content-Type': 'application/json'})
login = json.load(http.open(r, timeout=20))
assert login['success']
session = login['data']

def newapi(path, data=None):
    r = urllib.request.Request('http://127.0.0.1:13000' + path, None if data is None else json.dumps(data).encode(), {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + session['access_token']})
    return json.load(http.open(r, timeout=20))

def admin_call(path, data):
    r = urllib.request.Request('https://localhost:18443/api/admin/observer/' + path, json.dumps(data).encode(), {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + admin['token']})
    return json.load(urllib.request.urlopen(r, context=ca, timeout=15))

def trace():
    return admin_call('events/query', {'schema_version': 1, 'receiver_id': 'lab-receiver-wx', 'before_event_pk': 0, 'limit': 100})

def control(paused, reason):
    return admin_call('confirmation', {'schema_version': 1, 'receiver_id': 'lab-receiver-wx', 'paused': paused, 'reason_code': reason})

before = newapi('/api/user/self')['data']['quota']
payment = newapi('/api/user/pay', {'amount': 40, 'payment_method': 'wxpay'})
assert payment['message'] == 'success' and payment['url'] == 'http://127.0.0.1:18080/submit.php'
r = urllib.request.Request(payment['url'], urllib.parse.urlencode(payment['data']).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})
http.open(r, timeout=15).read()
pause = control(True, 'maintenance')
adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', a.serial]
try:
    subprocess.run(adb + ['reverse', 'tcp:18443', 'tcp:18443'], check=True, capture_output=True)
    r = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'dev.epay.observer.RechargeNetworkTest#existingPairingDeliversAnotherIndependentNotification', '-e', 'scenario', 'fresh-wxpay-40', 'dev.epay.observer.fixtureclient.debug.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True, timeout=120)
    (lab / 'avd-admin-pause.txt').write_text(r.stdout + r.stderr)
    assert r.returncode == 0 and 'OK (1 test)' in r.stdout and 'FAILURES' not in r.stdout
    paused = trace()
    e = paused['events'][0]
    assert paused['paused'] and e['amount_minor'] == 4000 and e['processing_status'] == 'needs_review'
    assert e['reason'] == 'confirmation_paused' and e['candidate_order_status'] == 0 and e['candidate_order_trade_no']
    assert e['order_trade_no'] is None and e['order_status'] is None and e['ledger_id'] is None
    resume = control(False, 'review_completed')
    resumed = trace()
    assert not resumed['paused'] and resumed['events'][0] == e
    assert newapi('/api/user/self')['data']['quota'] == before
    result = {'layer': 'actual HTTPS admin and AVD synthetic notification', 'pause': pause, 'resume': resume, 'paused_trace': paused, 'resumed_trace': resumed, 'quota_before': before, 'quota_after': before, 'passed': True}
    (lab / 'admin-acceptance-result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({'paused_event_persisted': True, 'candidate_order_unpaid': True, 'resume_preserved_review': True, 'quota_delta': 0}))
finally:
    subprocess.run(adb + ['reverse', '--remove', 'tcp:18443'], capture_output=True)
