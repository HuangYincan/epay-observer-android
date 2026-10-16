#!/usr/bin/env python3
"""Run the real HTTPS pairing test with private bootstrap, without shell secrets.

Requires an explicitly generated loopback-CA fixtureDebug APK and a running
observeravd Go test bridge. Never accepts a physical device serial.
"""
import argparse
import json
import os
import shlex
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('serial')
parser.add_argument('directory', type=Path)
parser.add_argument('--upload', action='store_true', help='Reset only the dedicated debug client, then test actual notification uploads')
args = parser.parse_args()
if not args.serial.startswith('emulator-') or not args.serial[9:].isdigit():
    parser.error('emulator-NNNN required')
sdk = Path(os.environ['ANDROID_HOME'])
adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
client = 'dev.epay.observer.fixtureclient.debug'
apks = ['app/build/outputs/apk/fixture/debug/app-fixture-debug.apk',
        'app/build/outputs/apk/androidTest/fixture/debug/app-fixture-debug-androidTest.apk']
if args.upload:
    apks.insert(0, 'fixture/build/outputs/apk/debug/fixture-debug.apk')
for apk in apks:
    subprocess.run(adb + ['install', '-r', apk], check=True, timeout=180)
if args.upload:
    subprocess.run(adb + ['shell', 'am', 'force-stop', client], check=True, timeout=15)
    result = subprocess.check_output(adb + ['shell', 'pm', 'clear', client], timeout=30)
    assert b'Success' in result, 'dedicated debug client reset failed'
subprocess.run(adb + ['reverse', 'tcp:18443', 'tcp:18443'], check=True, timeout=15)
bootstrap = (args.directory / 'avd-grants.json').read_bytes()
assert len(bootstrap) < 4096
try:
    # Input bytes go through stdin; no grant is placed in argv, shared storage,
    # screenshots, instrumentation extras or logs. The test immediately deletes it.
    subprocess.run(adb + ['shell', '-T', 'run-as', client, 'sh', '-c',
                         shlex.quote('umask 077; mkdir -p no_backup; cat > no_backup/pairing-network-bootstrap.json')],
                   input=bootstrap, check=True, timeout=15)
    report = args.directory / 'android-instrumentation.txt'
    with report.open('w') as output:
        subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                             'dev.epay.observer.UploadNetworkTest' if args.upload else 'dev.epay.observer.PairingNetworkTest',
                             client + '.test/androidx.test.runner.AndroidJUnitRunner'],
                       stdout=output, stderr=subprocess.STDOUT, check=True, timeout=180)
    text = report.read_text()
    assert 'OK (1 test)' in text and 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text, report
    filename = 'avd-upload-result.json' if args.upload else 'avd-pairing-result.json'
    result = subprocess.check_output(adb + ['exec-out', 'run-as', client, 'cat', 'files/' + filename], timeout=15)
    parsed = json.loads(result)
    assert parsed['historical_unbound'] == 2
    if args.upload:
        assert parsed['acked_events'] == 2 and parsed['transport_attempts'] == 3 and parsed['missing_ack_recovered'] is True
    else:
        assert parsed['active_credentials'] == 2 and parsed['fresh_queued'] == 2
    assert all(parsed[key] is True for key in ['unknown_ca_rejected', 'redirect_rejected', 'unexpected_origin_rejected'])
    (args.directory / ('upload-android-result.json' if args.upload else 'android-result.json')).write_bytes(result)
    (args.directory / ('device-uploaded' if args.upload else 'device-paired')).write_text('two synthetic channels verified\n')
    print('Actual synthetic notifications: two signed HTTPS events ACKED with missing-ACK recovery.' if args.upload else
          'Actual Android HTTPS pairing: wxpay/alipay active; historical UNBOUND retained; two fresh events queued.')
finally:
    subprocess.run(adb + ['reverse', '--remove', 'tcp:18443'], check=False, timeout=15)
    subprocess.run(adb + ['shell', 'run-as', client, 'rm', '-f', 'no_backup/pairing-network-bootstrap.json'], check=False, timeout=15)
