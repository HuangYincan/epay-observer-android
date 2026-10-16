#!/usr/bin/env python3
from pathlib import Path
import argparse
import urllib.request, json, http.cookiejar, os, subprocess, time, shlex
os.umask(63)
root = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('directory', type=Path)
p.add_argument('serial')
args = p.parse_args()
assert args.serial.startswith('emulator-') and args.serial[9:].isdigit()
lab = args.directory.resolve()
adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', args.serial]
clientpkg = 'dev.epay.observer.fixtureclient.debug'
runner = clientpkg + '.test/androidx.test.runner.AndroidJUnitRunner'

def run(*parts, timeout=60):
    return subprocess.check_output(adb + list(parts), stderr=subprocess.STDOUT, timeout=timeout).decode().strip()

def wait(label, check, timeout=90):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if check():
            return
        time.sleep(0.5)
    raise RuntimeError(label + ' timeout')
for p in ['fixture/build/outputs/apk/debug/fixture-debug.apk', 'app/build/outputs/apk/fixture/debug/app-fixture-debug.apk', 'app/build/outputs/apk/androidTest/fixture/debug/app-fixture-debug-androidTest.apk']:
    run('install', '-r', str(root / p), timeout=180)
run('shell', 'am', 'force-stop', clientpkg)
assert run('shell', 'pm', 'clear', clientpkg) == 'Success'
run('reverse', 'tcp:18443', 'tcp:18443')
subprocess.run(adb + ['shell', '-T', 'run-as', clientpkg, 'sh', '-c', shlex.quote('umask 077; mkdir -p no_backup; cat > no_backup/pairing-network-bootstrap.json')], input=(lab / 'avd-grants.json').read_bytes(), check=True, capture_output=True)
jar = http.cookiejar.LWPCookieJar(str(lab / 'newapi-cookies'))
jar.load(ignore_discard=True, ignore_expires=True)
http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
accounts = json.loads((lab / 'private-accounts.json').read_text())
login_request = urllib.request.Request('http://127.0.0.1:13000/api/user/login', json.dumps(accounts['newapi_root']).encode(), {'Content-Type': 'application/json'})
login_result = json.load(http.open(login_request, timeout=20))
assert login_result['success']
session = login_result['data']
(lab / 'newapi-session.json').write_text(json.dumps(login_result))
jar.save(ignore_discard=True, ignore_expires=True)

def newapi(path, data=None):
    h = {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + session['access_token']}
    r = urllib.request.Request('http://127.0.0.1:13000' + path, None if data is None else json.dumps(data).encode(), h)
    return json.load(http.open(r, timeout=20))
merchant = json.loads((lab / 'gateway-merchant.json').read_text())

def orders():
    r = urllib.request.Request('http://127.0.0.1:18080/api/merchant/orders?page_size=20', headers={'Authorization': 'Bearer ' + merchant['token']})
    d = json.load(urllib.request.urlopen(r, timeout=10))
    assert d['code'] == 0
    return d['data']['list']
start = newapi('/api/user/self')
assert start['success']
quota_before = start['data']['quota']
log = (lab / 'avd-recharge.txt').open('w')
p = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'dev.epay.observer.RechargeNetworkTest#independentNotificationsForOrdinaryRechargeOrders', runner], stdout=log, stderr=subprocess.STDOUT)
try:
    wait('pairing', lambda: run('shell', 'run-as', clientpkg, 'sh', '-c', '"test -f files/recharge-paired && echo ready || true"') == 'ready')
    created = []
    for provider in ['wxpay', 'alipay']:
        payment = newapi('/api/user/pay', {'amount': 10, 'payment_method': provider})
        assert payment.get('message') == 'success', payment.get('data')
        from urllib.parse import urlencode
        assert payment['url'] == 'http://127.0.0.1:18080/submit.php'
        req = urllib.request.Request(payment['url'], urlencode(payment['data']).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})
        page = urllib.request.urlopen(req, timeout=15).read()
        assert len(page) > 200
        matching = [o for o in orders() if o['out_trade_no'] == payment['data']['out_trade_no']]
        assert len(matching) == 1
        o = matching[0]
        assert o['status'] == 0
        created.append({'provider': provider, 'gateway_trade_no': o['trade_no'], 'newapi_trade_no': o['out_trade_no'], 'amount_minor': 1000})
    (lab / 'ordinary-orders.json').write_text(json.dumps(created, indent=2))
    run('shell', 'run-as', clientpkg, 'touch', 'files/recharge-orders-ready')
    assert p.wait(timeout=120) == 0
    log.close()
    out = (lab / 'avd-recharge.txt').read_text()
    assert 'OK (1 test)' in out and 'FAILURES' not in out
    android = json.loads(run('exec-out', 'run-as', clientpkg, 'cat', 'files/avd-recharge-result.json'))
    (lab / 'avd-recharge-result.json').write_text(json.dumps(android, indent=2))
    wait('both durable merchant callbacks', lambda: len([o for o in orders() if o['status'] == 1 and o['notify_status'] == 2]) == 2, 90)
    final = newapi('/api/user/self')
    assert final['success']
    quota_after = final['data']['quota']
    assert quota_after - quota_before == 10000000
    evidence = {'layer': 'AVD synthetic notifications to ordinary New API topups', 'providers': ['wxpay', 'alipay'], 'quota_before': quota_before, 'quota_after': quota_after, 'quota_delta': quota_after - quota_before, 'orders': created, 'gateway_paid_orders': 2, 'merchant_callbacks_succeeded': 2, 'android': android}
    (lab / 'recharge-result.json').write_text(json.dumps(evidence, indent=2))
    print(json.dumps({k: v for k, v in evidence.items() if k not in ['orders', 'android']}))
finally:
    if p.poll() is None:
        p.kill()
        p.wait(timeout=15)
    log.close()
    subprocess.run(adb + ['reverse', '--remove', 'tcp:18443'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
