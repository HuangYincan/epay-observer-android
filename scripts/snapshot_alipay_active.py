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
from snapshot_templates import classify_record, classify_field_roles

PACKAGE = 'dev.epay.observer.sample.debug.test'
COMPONENT = PACKAGE + '/dev.epay.observer.sample.ActiveSnapshotListener'
CONSENT = 'active-snapshot-once-max2-v1'
RECOVERY_CONSENT = 'active-snapshot-recovery-once-max2-v1'
CLASSIFICATION_CONSENT = 'active-snapshot-classification-once-max2-v1'
FIELD_ROLES_CONSENT = 'active-snapshot-field-roles-once-max2-v1'
POST_PAYMENT_CONSENT = 'active-snapshot-post-payment-once-max2-v1'
SCOPES = {
    CONSENT: ('active-snapshot', 'used-active-snapshot-max2-v1'),
    RECOVERY_CONSENT: ('active-snapshot-recovery', 'used-active-snapshot-recovery-max2-v1'),
    CLASSIFICATION_CONSENT: ('active-snapshot-classification', 'used-active-snapshot-classification-max2-v1'),
    FIELD_ROLES_CONSENT: ('active-snapshot-field-roles', 'used-active-snapshot-field-roles-max2-v1'),
    POST_PAYMENT_CONSENT: ('active-snapshot-post-payment', 'used-active-snapshot-post-payment-max2-v1'),
}
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


def wait_for_snapshot(read_status, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            data = read_status()
        except subprocess.TimeoutExpired:
            data = None
        if data is not None:
            state = snapshot_state(data)
            if state is not None:
                return state
        time.sleep(0.5)
    raise TimeoutError('snapshot metadata unavailable')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--consent', required=True, choices=tuple(SCOPES))
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    if args.evidence.resolve().is_relative_to(ROOT):
        parser.error('Private evidence must be outside repository')
    stem, marker = SCOPES[args.consent]
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

    report = {'scope': args.consent, 'historical_only': True, 'limit': 2,
              'new_notifications_read': False, 'payment_or_settlement': False}
    raw = args.evidence / 'raw.json'
    before = None
    installed = False
    prepared = False
    previous_states = {}
    try:
        report['test_apk_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        report['stage'] = 'listener_preflight'
        before = listeners()
        report['listeners_before'] = sorted(before)  # Private recovery metadata, never notification fields.
        if COMPONENT in before:
            raise RuntimeError('test listener already authorized')
        if args.consent == POST_PAYMENT_CONSENT:
            if 'dev.epay.observer.sample.debug/dev.epay.observer.sample.AlipayListener' in before:
                raise RuntimeError('live sample listener still authorized')
            shell('run-as', 'dev.epay.observer.sample.debug', 'sh', '-c',
                  'test -f no_backup/used-receipt-template-v1 && test ! -e no_backup/samples.jsonl')
        report['stage'] = 'verify_installed_test_apk'
        try:
            paths = shell('cmd', 'package', 'path', PACKAGE).splitlines()
        except subprocess.CalledProcessError:
            paths = []
        already_installed = False
        if len(paths) == 1 and paths[0].startswith('package:'):
            device_hash = shell('sha256sum', paths[0][8:]).split()[0]
            already_installed = device_hash == report['test_apk_sha256']
        if not already_installed:
            report['stage'] = 'install_test_apk'
            call('install', '-r', '-t', str(apk), timeout=60)
        report['test_apk_installation'] = 'unchanged_exact_hash' if already_installed else 'installed'
        installed = True
        report['stage'] = 'unused_gate_checks'
        absent = []
        present = []
        previous_scopes = list(SCOPES)[:list(SCOPES).index(args.consent)]
        for old_consent in previous_scopes:
            old_stem, old_marker = SCOPES[old_consent]
            present.append('no_backup/' + old_marker)
            previous = snapshot_state(call('exec-out', 'run-as', PACKAGE, 'cat', f'files/{old_stem}-status.json'))
            if (previous is None or previous['phase'] != 'COMPLETE' or
                    (old_consent != FIELD_ROLES_CONSENT and previous['records'] != 2)):
                raise ValueError('previous snapshot metadata')
            previous_states[(old_stem, old_marker)] = previous
            absent.extend([f'files/{old_stem}-consent', f'files/{old_stem}-raw.json',
                           f'files/{old_stem}-raw.json.pending', f'files/{old_stem}-status.json.pending'])
        absent.extend([f'files/{stem}-raw.json', f'files/{stem}-status.json',
                     f'files/{stem}-raw.json.pending', f'files/{stem}-status.json.pending',
                     f'files/{stem}-consent', 'no_backup/' + marker])
        checks = ['test -e ' + shlex.quote(name) for name in present]
        checks.extend('test ! -e ' + shlex.quote(name) for name in absent)
        shell('run-as', PACKAGE, 'sh', '-c', ' && '.join(checks))
        # Only this no-display test activity is launched; Alipay is untouched.
        report['stage'] = 'prepare_test_process'
        shell('am', 'start', '-W', '-n', PACKAGE + '/dev.epay.observer.sample.SnapshotPrepareActivity')
        prepared = True
        report['stage'] = 'prepare_consent'
        shell('run-as', PACKAGE, 'sh', '-c',
              'mkdir -p files && printf %s ' + args.consent + ' > files/' + stem + '-consent')
        report['stage'] = 'snapshot'
        shell('cmd', 'notification', 'allow_listener', COMPONENT)
        def read_status():
            result = subprocess.run(prefix + ['exec-out', 'run-as', PACKAGE, 'cat',
                                    'files/' + stem + '-status.json'], stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, timeout=8)
            return result.stdout if result.returncode == 0 else None
        state = wait_for_snapshot(read_status)
        # The collector's permanent marker already prevents any further snapshot;
        # posted callbacks are empty. Copy the completed private data before
        # cleanup commands, which can themselves transiently time out.
        if state is None or state.get('phase') != 'COMPLETE' or type(state.get('records')) is not int or not 0 <= state['records'] <= 2:
            raise RuntimeError('snapshot not complete')
        report['snapshot'] = state
        report['stage'] = 'private_processing'
        # Raw never crosses stdout/logs/model context. Only fixed-word shapes do.
        with raw.open('xb') as target:
            os.chmod(raw, 0o600)
            call('exec-out', 'run-as', PACKAGE, 'cat', 'files/' + stem + '-raw.json', output=target)
        if raw.stat().st_size > 32768:
            raise ValueError('raw size')
        rows = json.loads(raw.read_bytes())
        if not isinstance(rows, list) or len(rows) != state['records']:
            raise ValueError('raw count')
        templates = []
        for row in rows:
            if args.consent in {FIELD_ROLES_CONSENT, POST_PAYMENT_CONSENT}:
                templates.append(classify_field_roles(row))
                continue
            if args.consent == CLASSIFICATION_CONSENT:
                templates.append(classify_record(row))
                continue
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
                        f'files/{stem}-raw.json', f'files/{stem}-raw.json.pending',
                        f'files/{stem}-consent', f'files/{stem}-status.json.pending'),
                    'device_raw_absent': lambda: shell('run-as', PACKAGE, 'sh', '-c', ' && '.join(
                        'test ! -e ' + shlex.quote(f'files/{stem}-{suffix}')
                        for suffix in ['raw.json', 'raw.json.pending', 'consent', 'status.json.pending'])),
                })
            for label, action in actions.items():
                try:
                    action()
                    cleanup[label] = True
                except Exception:
                    cleanup[label] = False
        raw.unlink(missing_ok=True)
        cleanup['host_raw_deleted'] = not raw.exists()
        if before is None and not installed:
            cleanup['no_listener_mutation_started'] = True
        else:
            cleanup['listeners_restored'] = False
            for attempt in range(2):
                try:
                    after = listeners()
                    report['listeners_after'] = sorted(after)
                    cleanup['listeners_restored'] = after == before
                    break
                except subprocess.TimeoutExpired as error:
                    report.setdefault('listener_recheck_error_types', []).append(type(error).__name__)
                except Exception as error:
                    report.setdefault('listener_recheck_error_types', []).append(type(error).__name__)
                    break
        report['cleanup'] = cleanup
        if previous_states:
            try:
                for (old_stem, old_marker), previous in previous_states.items():
                    shell('run-as', PACKAGE, 'test', '-f', 'no_backup/' + old_marker)
                    current = snapshot_state(call('exec-out', 'run-as', PACKAGE, 'cat', f'files/{old_stem}-status.json'))
                    if current != previous:
                        raise ValueError('previous state changed')
                cleanup['previous_markers_and_states_preserved'] = True
            except Exception:
                cleanup['previous_markers_and_states_preserved'] = False
        report['cleanup_ok'] = all(cleanup.values())
        private_json(args.evidence / 'report.json', report)
    print(json.dumps({key: value for key, value in report.items()
                      if key not in {'listeners_before', 'listeners_after'}}, ensure_ascii=False))
    return 0 if report['result'] != 'failed' and report['cleanup_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
