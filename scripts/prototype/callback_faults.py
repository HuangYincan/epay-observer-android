#!/usr/bin/env python3
from pathlib import Path
import argparse
from lab_state import load_runtime, require_container
import urllib.request, urllib.parse, json, http.cookiejar, os, subprocess, time, hashlib
os.umask(63)
p = argparse.ArgumentParser()
p.add_argument('directory', type=Path)
p.add_argument('serial')
args = p.parse_args()
assert args.serial.startswith('emulator-') and args.serial[9:].isdigit()
lab = args.directory.resolve()
meta = load_runtime(lab)
accounts = json.loads((lab / 'private-accounts.json').read_text())
merchant = json.loads((lab / 'gateway-merchant.json').read_text())
admin = json.loads((lab / 'gateway-admin.json').read_text())
evidence = []
jar = http.cookiejar.LWPCookieJar(str(lab / 'newapi-cookies'))
jar.load(ignore_discard=True, ignore_expires=True)
http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

def login():
    r = urllib.request.Request('http://127.0.0.1:13000/api/user/login', json.dumps(accounts['newapi_root']).encode(), {'Content-Type': 'application/json'})
    result = json.load(http.open(r, timeout=20))
    assert result['success']
    (lab / 'newapi-session.json').write_text(json.dumps(result))
    jar.save(ignore_discard=True, ignore_expires=True)
    return result['data']
session = login()

def newapi(path, data=None, method=None):
    r = urllib.request.Request('http://127.0.0.1:13000' + path, None if data is None else json.dumps(data).encode(), {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + session['access_token']}, method=method)
    return json.load(http.open(r, timeout=20))

def gw(path, data=None, owner=merchant):
    r = urllib.request.Request('http://127.0.0.1:18080' + path, None if data is None else json.dumps(data).encode(), {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + owner['token']})
    out = json.load(urllib.request.urlopen(r, timeout=15))
    assert out['code'] == 0, out.get('msg')
    return out.get('data')

def orders():
    return gw('/api/merchant/orders?page_size=50')['list']

def order(no):
    return next((o for o in orders() if o['trade_no'] == no))

def quota():
    r = newapi('/api/user/self')
    assert r['success']
    return r['data']['quota']

def wait(label, check, seconds=100):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            if check():
                return
        except (OSError, StopIteration):
            pass
        time.sleep(0.5)
    raise AssertionError(label + ' timeout')

def save():
    (lab / 'callback-fault-results.json').write_text(json.dumps(evidence, indent=2))

def create(provider, amount):
    payment = newapi('/api/user/pay', {'amount': amount, 'payment_method': provider})
    assert payment['message'] == 'success'
    r = urllib.request.Request(payment['url'], urllib.parse.urlencode(payment['data']).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})
    urllib.request.urlopen(r, timeout=15).read()
    return next((o for o in orders() if o['out_trade_no'] == payment['data']['out_trade_no']))
adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', args.serial]
runner = 'dev.epay.observer.fixtureclient.debug.test/androidx.test.runner.AndroidJUnitRunner'
subprocess.run(adb + ['reverse', 'tcp:18443', 'tcp:18443'], check=True, capture_output=True)

def post(scenario):
    r = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'dev.epay.observer.RechargeNetworkTest#existingPairingDeliversAnotherIndependentNotification', '-e', 'scenario', scenario, runner], capture_output=True, timeout=120, text=True)
    (lab / ('avd-' + scenario + '.txt')).write_text(r.stdout + r.stderr)
    assert r.returncode == 0 and 'OK (1 test)' in r.stdout and ('FAILURES' not in r.stdout)

def restart(name):
    suffix = name.removeprefix(meta['prefix'] + '-')
    require_container(meta, suffix)
    subprocess.run(['docker', 'kill', '--signal', 'KILL', name], check=True, capture_output=True)
    subprocess.run(['docker', 'start', name], check=True, capture_output=True)
initial = quota()
assert initial == 110000000
wx = create('wxpay', 20)
(lab / 'callback-reject-once').touch()
post('fresh-wxpay-20')
wait('persisted failed callback', lambda: order(wx['trade_no'])['status'] == 1 and order(wx['trade_no'])['notify_count'] == 1)
assert order(wx['trade_no'])['notify_status'] == 1 and quota() == initial
restart(meta['prefix'] + '-gateway')
print('wx20: actual gateway killed after persisted callback failure; awaiting ordinary retry', flush=True)
wait('wx20 callback recovered', lambda: order(wx['trade_no'])['notify_status'] == 2)
assert quota() == initial + 10000000 and order(wx['trade_no'])['notify_count'] == 2
evidence.append({'case': 'callback_503_gateway_SIGKILL_restart', 'provider': 'wxpay', 'callback_attempts': 2, 'quota_delta': 10000000, 'passed': True})
save()
ali = create('alipay', 20)
before = quota()
(lab / 'callback-drop-response-once').touch()
post('fresh-alipay-20')
wait('New API commit despite lost response', lambda: quota() == before + 10000000 and order(ali['trade_no'])['notify_status'] == 1)
assert order(ali['trade_no'])['notify_count'] == 1
restart(meta['prefix'] + '-gateway')
restart(meta['prefix'] + '-newapi')
print('ali20: New API committed, callback response lost, both processes killed/restarted; awaiting retry', flush=True)
wait('ali20 callback recovered', lambda: order(ali['trade_no'])['notify_status'] == 2)
assert quota() == before + 10000000 and order(ali['trade_no'])['notify_count'] == 2
evidence.append({'case': 'lost_callback_response_both_processes_SIGKILL_restart', 'provider': 'alipay', 'callback_attempts': 2, 'quota_delta': 10000000, 'passed': True})
save()
before = quota()
old_key = merchant['merchant']['api_key']
rotated = gw('/api/merchant/profile/reset-key', {})['api_key']
merchant['merchant']['api_key'] = rotated
(lab / 'gateway-merchant.json').write_text(json.dumps(merchant))
gw('/api/admin/orders/' + wx['trade_no'] + '/renotify', {}, admin)
assert order(wx['trade_no'])['notify_status'] == 1 and quota() == before
result = newapi('/api/option/', {'key': 'EpayKey', 'value': rotated}, 'PUT')
assert result['success']
print('merchant key rotated through normal API; mismatched callback failed, New API updated, awaiting signed retry', flush=True)
wait('rotated-key retry', lambda: order(wx['trade_no'])['notify_status'] == 2)
assert order(wx['trade_no'])['notify_count'] == 2 and quota() == before
evidence.append({'case': 'current_merchant_key_rotation', 'callback_attempts': 2, 'quota_delta': 0, 'passed': True})
save()

def callback(money, key, tampered=None):
    params = {'pid': str(merchant['merchant']['id']), 'trade_no': wx['trade_no'], 'out_trade_no': wx['out_trade_no'], 'type': 'wxpay', 'name': wx['name'], 'money': money, 'trade_status': 'TRADE_SUCCESS'}
    payload = '&'.join((k + '=' + params[k] for k in sorted(params) if params[k])) + key
    params['sign'] = hashlib.md5(payload.encode()).hexdigest()
    params['sign_type'] = 'MD5'
    if tampered is not None:
        params['money'] = tampered
    args = ['docker', 'exec', '-i', meta['prefix'] + '-runtime', 'curl', '--silent', '--show-error', '--max-time', '15', '--cacert', '/lab/ca.pem', '-H', 'Content-Type: application/x-www-form-urlencoded', '--data-binary', '@-', 'https://127.0.0.1:8444/api/user/epay/notify']
    response = subprocess.check_output(args, input=urllib.parse.urlencode(params).encode(), stderr=subprocess.PIPE).decode()
    return response
for name, money, key, tampered in [('wrong signature', '20', old_key, None), ('signed wrong amount', '20.01', rotated, None), ('unsigned amount tamper', '20', rotated, '20.01'), ('missing amount', '', rotated, None)]:
    assert callback(money, key, tampered) == 'fail'
    assert quota() == before
    evidence.append({'case': name, 'quota_delta': 0, 'passed': True})
assert callback('20', rotated) == 'success' and callback('20.00', rotated) == 'success' and (quota() == before)
evidence.append({'case': 'late_duplicate_TLS_callback', 'quota_delta': 0, 'passed': True})
save()
post('fresh-wxpay')
wait('duplicate evidence classified', lambda: subprocess.check_output(['docker', 'exec', meta['prefix'] + '-postgres', 'psql', '-U', 'observer_lab', '-d', meta['gateway_database'], '-Atc', "SELECT count(*) FROM observer_match_decisions WHERE outcome='duplicate_suspected'"]).decode().strip() == '1')
assert quota() == before
evidence.append({'case': 'new_key_identical_content', 'quota_delta': 0, 'passed': True})
save()
counts = subprocess.check_output(['docker', 'exec', meta['prefix'] + '-postgres', 'psql', '-U', 'observer_lab', '-d', meta['gateway_database'], '-Atc', "SELECT count(*) FROM balance_records WHERE type='order_income'"]).decode().strip()
assert counts == '4'
print(json.dumps({'cases_passed': len(evidence), 'initial_quota': initial, 'final_quota': quota(), 'total_income_records': int(counts)}), flush=True)
subprocess.run(adb + ['reverse', '--remove', 'tcp:18443'], capture_output=True)
