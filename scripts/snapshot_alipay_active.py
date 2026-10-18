#!/usr/bin/env python3
"""SPEC-037: one authorized historical snapshot; never a payment or live window."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import time

from private_content_sample import private_json, shape

PACKAGE = 'dev.epay.observer.sample.debug.test'
COMPONENT = PACKAGE + '/dev.epay.observer.sample.ActiveSnapshotListener'
CONSENT = 'active-snapshot-once-max2-v1'
ROOT = Path(__file__).resolve().parents[1]


def snapshot_state(data):
    """A visible file may still be incomplete on older collectors. Keep waiting."""
    try:
        value = json.loads(data)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    expected = {'phase', 'records', 'limit', 'historical_snapshot', 'new_notifications_read'}
    if (not isinstance(value, dict) or set(value) != expected or
            value['phase'] not in {'COMPLETE', 'ERROR'} or
            type(value['records']) is not int or not 0 <= value['records'] <= 2 or
            type(value['limit']) is not int or value['limit'] != 2 or
            value['historical_snapshot'] is not True or value['new_notifications_read'] is not False):
        raise ValueError('snapshot state schema')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--consent', required=True, choices=[CONSENT])
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(mode=0o700, parents=False, exist_ok=False)
    apk = ROOT / 'sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk'
    adb = Path(os.environ.get('ANDROID_HOME', '/Users/acccan/Library/Android/sdk')) / 'platform-tools/adb'
    prefix = [str(adb), '-s', 'emulator-5554']

    def call(*arguments, output=subprocess.PIPE, timeout=15):
        return subprocess.run(prefix + list(arguments), check=True, stdout=output,
                              stderr=subprocess.DEVNULL, timeout=timeout).stdout

    def shell(*arguments):
        return call('shell', shlex.join(arguments)).decode().strip()

    def listeners():
        value = shell('settings', 'get', 'secure', 'enabled_notification_listeners')
        return set(value.split(':')) if value and value != 'null' else set()

    report = {'scope': CONSENT, 'historical_only': True, 'limit': 2,
              'new_notifications_read': False, 'payment_or_settlement': False}
    raw = args.evidence / 'raw.json'
    before = None
    installed = False
    prepared = False
    try:
        report['test_apk_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        before = listeners()
        if COMPONENT in before:
            raise RuntimeError('test listener already authorized')
        report['stage'] = 'install_test_apk'
        call('install', '-r', '-t', str(apk), timeout=60)
        installed = True
        report['stage'] = 'unused_gate_checks'
        for name in ['files/active-snapshot-raw.json', 'files/active-snapshot-status.json',
                     'files/active-snapshot-raw.json.pending', 'files/active-snapshot-status.json.pending',
                     'files/active-snapshot-consent', 'no_backup/used-active-snapshot-max2-v1']:
            shell('run-as', PACKAGE, 'test', '!', '-e', name)
        # Only this no-display test activity is launched; Alipay is untouched.
        report['stage'] = 'prepare_test_process'
        shell('am', 'start', '-W', '-n', PACKAGE + '/dev.epay.observer.sample.SnapshotPrepareActivity')
        prepared = True
        report['stage'] = 'prepare_consent'
        shell('run-as', PACKAGE, 'sh', '-c',
              'mkdir -p files && printf %s ' + CONSENT + ' > files/active-snapshot-consent')
        report['stage'] = 'snapshot'
        shell('cmd', 'notification', 'allow_listener', COMPONENT)
        deadline = time.monotonic() + 30
        state = None
        while time.monotonic() < deadline:
            result = subprocess.run(prefix + ['exec-out', 'run-as', PACKAGE, 'cat',
                                    'files/active-snapshot-status.json'], stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, timeout=8)
            if result.returncode == 0:
                state = snapshot_state(result.stdout)
                if state is not None:
                    break
            time.sleep(0.5)
        shell('cmd', 'notification', 'disallow_listener', COMPONENT)
        shell('am', 'force-stop', PACKAGE)
        if state is None or state.get('phase') != 'COMPLETE' or type(state.get('records')) is not int or not 0 <= state['records'] <= 2:
            raise RuntimeError('snapshot not complete')
        report['snapshot'] = state
        report['stage'] = 'private_processing'
        # Raw never crosses stdout/logs/model context. Only fixed-word shapes do.
        with raw.open('xb') as target:
            os.chmod(raw, 0o600)
            call('exec-out', 'run-as', PACKAGE, 'cat', 'files/active-snapshot-raw.json', output=target)
        if raw.stat().st_size > 32768:
            raise ValueError('raw size')
        rows = json.loads(raw.read_bytes())
        if not isinstance(rows, list) or len(rows) != state['records']:
            raise ValueError('raw count')
        templates = []
        for row in rows:
            if not isinstance(row, dict) or set(row) != {'title', 'text', 'big_text'}:
                raise ValueError('raw schema')
            for field, limit in [('title', 256), ('text', 1024), ('big_text', 1024)]:
                if row[field] is not None and (not isinstance(row[field], str) or len(row[field]) > limit):
                    raise ValueError('raw field')
            templates.append({key + '_template': shape(value) for key, value in row.items()})
        private_json(args.evidence / 'templates.json', templates)
        report['result'] = 'snapshot_complete_not_payment_verification'
        report['stage'] = 'complete'
    except Exception as error:
        # Do not print exception text, command output, paths with tokens or data.
        report['result'] = 'failed'
        report['error_type'] = type(error).__name__
    finally:
        cleanup = {}
        if installed:
            actions = {
                'listener_revoked': lambda: shell('cmd', 'notification', 'disallow_listener', COMPONENT),
                'test_process_stopped': lambda: shell('am', 'force-stop', PACKAGE),
            }
            if prepared:
                actions.update({
                    'device_raw_deleted': lambda: shell('run-as', PACKAGE, 'rm', '-f',
                        'files/active-snapshot-raw.json', 'files/active-snapshot-raw.json.pending',
                        'files/active-snapshot-consent', 'files/active-snapshot-status.json.pending'),
                    'device_raw_absent': lambda: shell('run-as', PACKAGE, 'test', '!', '-e', 'files/active-snapshot-raw.json'),
                })
            for label, action in actions.items():
                try:
                    action()
                    cleanup[label] = True
                except Exception:
                    cleanup[label] = False
        raw.unlink(missing_ok=True)
        cleanup['host_raw_deleted'] = not raw.exists()
        try:
            cleanup['listeners_restored'] = before is not None and listeners() == before
        except Exception:
            cleanup['listeners_restored'] = False
        report['cleanup'] = cleanup
        report['cleanup_ok'] = all(cleanup.values())
        private_json(args.evidence / 'report.json', report)
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report['result'] != 'failed' and report['cleanup_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
