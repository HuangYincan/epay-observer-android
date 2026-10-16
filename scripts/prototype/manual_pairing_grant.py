#!/usr/bin/env python3
"""Create a short-lived grant for a human to paste into the existing pairing UI."""
import argparse
import json
import os
from pathlib import Path
import ssl
import urllib.request
from lab_state import load_runtime, require_container


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    p.add_argument('provider', choices=['wxpay', 'alipay'])
    p.add_argument('--mode', choices=['rotate', 'replace'], default='rotate')
    args = p.parse_args()
    lab = args.directory.resolve()
    meta = load_runtime(lab)
    require_container(meta, 'gateway')
    if meta['environment'] != meta['prefix']:
        p.error('lab environment mismatch')
    origin = 'https://localhost:18443'
    receiver = 'lab-receiver-wx' if args.provider == 'wxpay' else 'lab-receiver-ali'
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(lab / 'ca.pem'))))

    def post(path, data, token=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(origin + path, json.dumps(data).encode(), headers)
        with client.open(request, timeout=15) as response:
            return json.load(response)

    account = json.loads((lab / 'private-accounts.json').read_text())['gateway_admin']
    login = post('/api/admin/auth/login', account)
    if login.get('code') != 0:
        raise RuntimeError('administrator login rejected')
    grant = post('/api/admin/observer/grants', {'schema_version': 1, 'receiver_id': receiver,
        'mode': args.mode}, login['data']['token'])
    expected = {'schema_version': 1, 'origin': origin, 'environment': meta['environment'],
        'source_class': 'synthetic', 'provider': args.provider, 'receiver_id': receiver, 'mode': args.mode}
    if any(grant.get(k) != v for k, v in expected.items()):
        raise RuntimeError('grant scope mismatch; nothing exported')
    fields = ['origin', 'environment', 'provider', 'receiver_id', 'mode', 'expires_at_ms', 'token']
    out = lab / ('manual-pairing-' + args.provider + '.json')
    # Do not follow an existing symlink or leave an older file with broad permissions.
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as file:
        os.fchmod(file.fileno(), 0o600)
        json.dump({k: grant[k] for k in fields}, file, indent=2)
    print(f'已保存5分钟短期授权：{out}')
    print(f'渠道：{args.provider}；模式：{args.mode}。在本机文件中复制token，粘贴到观察器配对框；不要贴入聊天或Issue。')


if __name__ == '__main__':
    main()
