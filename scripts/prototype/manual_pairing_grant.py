#!/usr/bin/env python3
"""Create a short-lived grant for a human to paste into the existing pairing UI."""
import argparse
import json
from email.utils import parsedate_to_datetime
import os
from pathlib import Path
import ssl
import subprocess
import time
import re
import urllib.request
from lab_state import load_runtime, require_container


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def check_emulator_clock(server_date, received_at, serial):
    try:
        timestamp = parsedate_to_datetime(server_date)
        if timestamp.tzinfo is None:
            raise ValueError('timezone required')
        server_ms = timestamp.timestamp() * 1000
    except (TypeError, ValueError, OverflowError):
        raise RuntimeError('网关未提供有效Date，无法预检时钟；未创建配对授权。') from None
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        or Path.home() / 'Library/Android/sdk')
    adb = sdk / 'platform-tools/adb'
    if not adb.is_file():
        raise RuntimeError('请将ANDROID_HOME指向已有SDK；未创建配对授权。')
    try:
        device_ms = int(subprocess.check_output([str(adb), '-s', serial, 'shell', 'date', '+%s'],
            text=True, stderr=subprocess.PIPE, timeout=5).strip()) * 1000
    except (OSError, subprocess.SubprocessError, ValueError):
        raise RuntimeError('无法读取指定模拟器时间，请检查ADB连接；未创建配对授权。') from None
    expected_ms = server_ms + (time.monotonic() - received_at) * 1000
    if abs(device_ms - expected_ms) > 3000:
        raise RuntimeError('模拟器与网关时钟偏差超过3秒，请先校时或保留数据冷启动AVD；未创建配对授权。')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    p.add_argument('provider', choices=['wxpay', 'alipay'])
    p.add_argument('--mode', choices=['rotate', 'replace'], default='rotate')
    p.add_argument('--serial', default='emulator-5554', help='Explicit emulator-NNNN for clock preflight; never a physical device')
    args = p.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        p.error('只允许显式emulator-NNNN目标，未创建配对授权')
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
            received_at = time.monotonic()
            return json.load(response), response.headers.get('Date'), received_at

    account = json.loads((lab / 'private-accounts.json').read_text())['gateway_admin']
    login, server_date, received_at = post('/api/admin/auth/login', account)
    if login.get('code') != 0:
        raise RuntimeError('administrator login rejected')
    check_emulator_clock(server_date, received_at, args.serial)
    grant, _, _ = post('/api/admin/observer/grants', {'schema_version': 1, 'receiver_id': receiver,
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
    try:
        main()
    except RuntimeError as failure:
        raise SystemExit(str(failure)) from None
