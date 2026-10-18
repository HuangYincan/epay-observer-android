#!/usr/bin/env python3
"""SPEC-030: persistent initial Alipay provisioning after user designation; no events or notification access."""
import argparse
import base64
import datetime
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
from check_gateway_connection_avd import canonical_origin

PACKAGE = 'dev.epay.observer.debug'
CLASS = 'dev.epay.observer.ProductionPairingNetworkTest'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
INPUT = 'no_backup/production-pairing-input.json'
PREFIX = 'INSTRUMENTATION_STATUS: production_pairing_result='


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate field')
        result[key] = value
    return result


def load_bootstrap(raw, pairing=True):
    if not 1 <= len(raw) <= 8192:
        raise ValueError('Invalid private bootstrap size')
    value = json.loads(raw, object_pairs_hook=unique_object)
    if not isinstance(value, dict) or set(value) != ({'scope', 'token'} if pairing else {'scope'}):
        raise ValueError('Invalid private bootstrap fields')
    scope = value['scope']
    fields = {'schema_version', 'grant_id', 'origin', 'environment', 'receiver_id', 'provider',
              'source_class', 'mode', 'expected_binding_version', 'device_id', 'expires_at_ms'}
    if not isinstance(scope, dict) or set(scope) != fields:
        raise ValueError('Invalid scope fields')
    if (type(scope['schema_version']) is not int or scope['schema_version'] != 1 or
            type(scope['expected_binding_version']) is not int or scope['expected_binding_version'] != 0 or
            scope['provider'] != 'alipay' or scope['source_class'] != 'notification' or
            scope['mode'] != 'replace' or scope['device_id'] != ''):
        raise ValueError('Only a designated initial notification receiver is allowed')
    if not isinstance(scope['origin'], str):
        raise ValueError('Invalid origin')
    canonical_origin(scope['origin'])
    for name in ['grant_id', 'environment', 'receiver_id']:
        if not isinstance(scope[name], str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', scope[name]):
            raise ValueError('Invalid scope identifier')
    if type(scope['expires_at_ms']) is not int or not 0 <= scope['expires_at_ms'] <= 9007199254740991:
        raise ValueError('Invalid expiry')
    if pairing:
        token = value['token']
        if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', token):
            raise ValueError('Invalid private token')
        if base64.urlsafe_b64encode(base64.urlsafe_b64decode(token + '=')).decode().rstrip('=') != token:
            raise ValueError('Noncanonical token')
    return value


def expected_result(phase):
    return {'schema_version': 1, 'phase': phase, 'passed': True, 'active_credentials': 1,
            'authorized': True, 'nonexportable_key': True, 'empty_local_records': True,
            'notification_access': False, 'payment_verified': False}


def parse_result(output, returncode, phase):
    rows = [json.loads(line[len(PREFIX):], object_pairs_hook=unique_object)
            for line in output.splitlines() if line.startswith(PREFIX)]
    expected = expected_result(phase)
    if (returncode != 0 or output.count('OK (1 test)') != 1 or any(
            marker in output for marker in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=']) or
            len(rows) != 1 or rows[0] != expected or any(type(rows[0][key]) is not type(value)
                                                       for key, value in expected.items())):
        raise ValueError('Persistent pairing or health did not pass')
    return rows[0]


def write_private_input(adb, value, phase, run=subprocess.run):
    payload = {'scope': value['scope']}
    if phase == 'pair':
        payload['token'] = value['token']
    command = shlex.quote('umask 077; mkdir -p no_backup; cat > ' + INPUT)
    run(adb + ['shell', '-T', 'run-as', PACKAGE, 'sh', '-c', command],
        input=json.dumps(payload).encode(), capture_output=True, check=True, timeout=15)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('serial')
    p.add_argument('--bootstrap', type=Path, required=True)
    p.add_argument('--directory', type=Path, required=True)
    p.add_argument('--allow-persistent-pairing', action='store_true', required=True)
    p.add_argument('--stages', nargs='+', choices=['pair', 'verify'], default=['pair', 'verify'])
    args = p.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial) or args.stages not in [['pair'], ['verify'], ['pair', 'verify']]:
        p.error('Explicit emulator and ordered nonrepeating stages required')
    directory = args.directory.resolve()
    if not str(directory).startswith(('/tmp/observer-production-pairing-', '/private/tmp/observer-production-pairing-')) or directory.exists():
        p.error('New private /tmp/observer-production-pairing-* evidence directory required')
    if not args.bootstrap.is_file() or args.bootstrap.stat().st_mode & 0o077:
        p.error('Existing private bootstrap (0600) required')
    os.umask(0o077); directory.mkdir(mode=0o700)
    sdk = Path(os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk')
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
    report = {'schema_version': 1, 'passed': False, 'phases': [], 'payment_verified': False,
              'utc_started': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    try:
        value = load_bootstrap(args.bootstrap.read_bytes(), pairing='pair' in args.stages)
        def shell(*a):
            return subprocess.check_output(adb + ['shell', *a], text=True, stderr=subprocess.PIPE, timeout=15).strip()
        if shell('getprop', 'ro.kernel.qemu') != '1' or shell('am', 'get-current-user') != '0':
            raise ValueError('AVD user 0 required')
        for phase in args.stages:
            if phase == 'verify':
                shell('am', 'force-stop', PACKAGE)
                stopped = subprocess.run(adb + ['shell', 'pidof', PACKAGE], capture_output=True, text=True, timeout=10)
                if stopped.stdout.strip():
                    raise ValueError('Target process did not stop')
                shell('am', 'start', '-n', PACKAGE + '/dev.epay.observer.MainActivity')
                report['target_process_restarted'] = True
            write_private_input(adb, value, phase)
            with (directory / (phase + '.txt')).open('x') as stream:
                result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e',
                    'observerProductionPairing', 'true', '-e', 'observerPairingPhase', phase,
                    '-e', 'class', CLASS, RUNNER], stdout=stream, stderr=subprocess.STDOUT, timeout=120)
            report['phases'].append(parse_result((directory / (phase + '.txt')).read_text(), result.returncode, phase))
        report['passed'] = True
    except (OSError, ValueError, TypeError, argparse.ArgumentTypeError, subprocess.SubprocessError):
        report['failure'] = 'Pairing/health incomplete; preserve encrypted pending and inspect private evidence'
    finally:
        try:
            cleanup = subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'rm', '-f', INPUT], capture_output=True, timeout=15)
            report['private_input_removed'] = cleanup.returncode == 0
        except (OSError, subprocess.SubprocessError):
            report['private_input_removed'] = False
        report['passed'] = report['passed'] and report['private_input_removed']
        report['utc_finished'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (directory / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
