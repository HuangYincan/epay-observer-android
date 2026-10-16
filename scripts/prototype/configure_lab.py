#!/usr/bin/env python3
"""Configure only the task lab through ordinary application APIs and scope tables."""
from pathlib import Path
import argparse
import http.cookiejar
import json
import os
import secrets
import subprocess
import urllib.request
p = argparse.ArgumentParser()
p.add_argument('directory', type=Path)
a = p.parse_args()
lab = a.directory.resolve()
os.umask(63)
meta = json.loads((lab / 'runtime.json').read_text())
accounts = json.loads((lab / 'private-accounts.json').read_text())
if (lab / 'configured').exists():
    p.error('lab is already configured')

def gateway(path, data=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request('http://127.0.0.1:18080' + path, None if data is None else json.dumps(data).encode(), headers)
    result = json.load(urllib.request.urlopen(request, timeout=20))
    assert result.get('code') == 0, result.get('msg')
    return result['data']
admin = gateway('/api/admin/auth/login', accounts['gateway_admin'])
merchant = gateway('/api/merchant/auth/register', accounts['gateway_merchant'])
channel = gateway('/api/admin/channels', {'name': 'isolated-synthetic', 'plugin': 'epay', 'app_type': 'qrcode', 'pay_types': 'wxpay,alipay', 'config': {'wechat_qr': 'synthetic://wxpay', 'alipay_qr': 'synthetic://alipay', 'notify_key': secrets.token_hex(32)}, 'rate': '0', 'daily_limit': '0', 'status': 1}, admin['token'])
for name, data in [('gateway-admin', admin), ('gateway-merchant', merchant), ('gateway-channel', channel)]:
    (lab / (name + '.json')).write_text(json.dumps(data))
environment = meta['environment']
assert environment == meta['prefix'] and environment.startswith('epay-observer-') and all((c.islower() or c.isdigit() or c == '-' for c in environment))
mid = int(merchant['merchant']['id'])
cid = int(channel['id'])
sql = f"INSERT INTO observer_synthetic_merchants(merchant_id,environment,enabled) VALUES ({mid},'{environment}',true); INSERT INTO observer_channel_aliases(channel_id,environment,receiver_id,provider) VALUES ({cid},'{environment}','lab-receiver-wx','wxpay'),({cid},'{environment}','lab-receiver-ali','alipay');"
subprocess.run(['docker', 'exec', '-i', meta['prefix'] + '-postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'observer_lab', '-d', 'epay'], input=sql.encode(), check=True, capture_output=True)
jar = http.cookiejar.LWPCookieJar(str(lab / 'newapi-cookies'))
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
session = None

def newapi(path, data, method='POST'):
    headers = {'Content-Type': 'application/json'}
    if session:
        headers['Authorization'] = 'Bearer ' + session['data']['access_token']
    request = urllib.request.Request('http://127.0.0.1:13000' + path, json.dumps(data).encode(), headers, method=method)
    result = json.load(client.open(request, timeout=20))
    assert result.get('success'), result.get('message')
    return result
root = accounts['newapi_root']
newapi('/api/setup', dict(root, confirmPassword=root['password'], SelfUseModeEnabled=False, DemoSiteEnabled=False))
session = newapi('/api/user/login', root)
jar.save(ignore_discard=True, ignore_expires=True)
(lab / 'newapi-session.json').write_text(json.dumps(session))
newapi('/api/option/payment_compliance', {'confirmed': True})
settings = {'PayAddress': 'http://127.0.0.1:18080', 'EpayId': str(mid), 'EpayKey': merchant['merchant']['api_key'], 'Price': '1', 'MinTopUp': '1', 'ServerAddress': 'http://127.0.0.1:13000', 'CustomCallbackAddress': 'https://127.0.0.1:8444', 'PayMethods': json.dumps([{'name': 'Synthetic WeChat', 'type': 'wxpay'}, {'name': 'Synthetic Alipay', 'type': 'alipay'}])}
for key, value in settings.items():
    newapi('/api/option/', {'key': key, 'value': value}, 'PUT')
(lab / 'configured').write_text('ordinary APIs and explicit synthetic scope initialized\n')
print('Ordinary accounts, dual-provider channel, isolated scope and New API settings configured.')
