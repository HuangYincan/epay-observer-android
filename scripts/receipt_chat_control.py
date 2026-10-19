#!/usr/bin/env python3
"""SPEC-042: local user-started, finite non-financial receipt/chat comparison."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shlex
import signal
import subprocess
import time

from private_content_sample import BASELINE, Driver, REPO_ROOT, private_json
from probe_delivery_avd import DeliverySession, acquire_control_lock, make_server
from snapshot_alipay_active import SCOPES, snapshot_state
from snapshot_templates import field_role_assessment, fixed_shape, CATEGORIES, STYLES

PACKAGE = 'dev.epay.observer.sample.debug.test'
COMPONENT = PACKAGE + '/dev.epay.observer.sample.ReceiptChatListener'
STEM = 'receipt-chat-comparison'
MARKER = 'used-' + STEM + '-v1'
CONSENT = STEM + '-once-120s-max2-v1'
CONTENT_LISTENERS = {
    COMPONENT,
    PACKAGE + '/dev.epay.observer.sample.ActiveSnapshotListener',
    'dev.epay.observer.sample.debug/dev.epay.observer.sample.AlipayListener',
    'dev.epay.observer.debug/dev.epay.observer.capture.ObserverListener',
}


class ComparisonDriver(Driver):
    def shell(self, *args):
        # adb shell joins argv; quote every remote argument, especially sh -c.
        return self.adb('shell', shlex.join(args)).decode().strip()


class ComparisonSession(DeliverySession):
    def __init__(self, directory, runner, phrase=None, page_test=False):
        self.observed_live_at = None
        self.phrase = phrase or '观察校验' + secrets.token_hex(6)
        super().__init__(directory, runner, page_test)
        self.change(spec='SPEC-042', issues=[82, 84], scope='One historical child + first posted child, max120s; no payment',
                    posted_nonce_matches=False, controlled_chat=False, payment_eligibility=False)

    def sample(self, row):
        with self.lock:
            if row['phase'] == 'RUNNING':
                self.observed_live_at = time.monotonic()
            super().sample(row)

    def public(self):
        with self.lock:
            result = super().public()
            now = time.monotonic()
            can_confirm = bool(self.report['state'] in {'OBSERVING', 'CLEANING'} and
                self.observed_live_at is not None and now - self.observed_live_at <= 12 and
                self.delivery_deadline is not None and now < self.delivery_deadline and
                not self.report['user_send_confirmation'] and not self.cancelled.is_set() and
                not self.evidence_failed and not self.report.get('error_type'))
            result.update(phrase=self.phrase, controlled_chat=self.report.get('controlled_chat', False),
                          can_confirm=can_confirm, records=self.report.get('records', 0))
            return result

    def confirm_sent(self):
        with self.lock:
            if not self.public()['can_confirm']:
                return False
            self.change(user_send_confirmation={'host_utc_recorded': time.time(), 'within_host_budget': True},
                        controlled_chat=self.report['posted_nonce_matches'])
            return not self.evidence_failed


def audit(driver, lab):
    output = subprocess.check_output(['python3', str(REPO_ROOT / 'scripts/prototype/audit_recharge.py'), str(lab)],
                                     stderr=subprocess.DEVNULL, timeout=30)
    result = json.loads(output)
    if result != BASELINE:
        raise ValueError('baseline changed')
    return result


def listeners(driver):
    value = driver.shell('settings', 'get', 'secure', 'enabled_notification_listeners')
    return set(value.split(':')) if value and value != 'null' else set()


def run_as(driver, *args):
    return driver.shell('run-as', PACKAGE, *args)


def check_old(driver):
    result = {}
    for stem, marker in SCOPES.values():
        run_as(driver, 'test', '-f', 'no_backup/' + marker)
        data = driver.adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/' + stem + '-status.json')
        state = snapshot_state(data)
        expected_records = 0 if stem == 'active-snapshot-field-roles' else 2
        if state is None or state['phase'] != 'COMPLETE' or state['records'] != expected_records:
            raise ValueError('old state')
        result[stem] = hashlib.sha256(data).hexdigest()
        for suffix in ['raw.json', 'raw.json.pending', 'status.json.pending', 'consent']:
            run_as(driver, 'test', '!', '-e', 'files/' + stem + '-' + suffix)
    # Keep previous live scopes and their cleanup evidence; never reset a marker.
    for marker in ['used', 'used-scan-payment-v1', 'used-scan-payment-unpaid-retry-v1', 'used-receipt-template-v1']:
        driver.shell('run-as', 'dev.epay.observer.sample.debug', 'test', '-f', 'no_backup/' + marker)
    driver.shell('run-as', 'dev.epay.observer.sample.debug', 'test', '!', '-e', 'no_backup/samples.jsonl')
    return result


def check_unused(driver):
    run_as(driver, 'test', '!', '-e', 'no_backup/' + MARKER)
    for suffix in ['raw.json', 'raw.json.pending', 'status.json', 'status.json.pending', 'consent']:
        run_as(driver, 'test', '!', '-e', 'files/' + STEM + '-' + suffix)


def prepare(driver, lab, directory):
    before = None
    old = None
    touched = False
    result = {'ready': False, 'content_read': False, 'marker_used': False}
    cleanup = {}
    try:
        before = listeners(driver)
        private_json(directory / 'listeners-before.json', sorted(before))
        if CONTENT_LISTENERS.intersection(before):
            raise ValueError('content listener already enabled')
        audit(driver, lab)
        apk = REPO_ROOT / 'sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk'
        touched = True
        result['sample_apk'] = driver.ensure_installed(REPO_ROOT / 'sample/build/outputs/apk/debug/sample-debug.apk', 'dev.epay.observer.sample.debug')
        result['test_apk'] = driver.ensure_installed(apk, PACKAGE)
        old = check_old(driver)
        check_unused(driver)
        # Protected no-display activity has no notification/content path.
        driver.shell('am', 'start', '-W', '-n', PACKAGE + '/dev.epay.observer.sample.SnapshotPrepareActivity')
        result['ready'] = True
    except Exception as error:
        result['error_type'] = type(error).__name__
    finally:
        if touched:
            try:
                driver.shell('am', 'force-stop', PACKAGE)
                cleanup['test_process_stopped'] = True
            except Exception:
                cleanup['test_process_stopped'] = False
        try:
            after = listeners(driver)
            private_json(directory / 'listeners-after-prepare.json', sorted(after))
            cleanup['listeners_unchanged'] = before is not None and before == after
        except Exception:
            cleanup['listeners_unchanged'] = False
        try:
            cleanup['old_records_preserved'] = old is not None and check_old(driver) == old
            check_unused(driver)
            cleanup['new_scope_unused'] = True
        except Exception:
            cleanup['new_scope_unused'] = False
        try:
            audit(driver, lab)
            cleanup['four_order_audit'] = True
        except Exception:
            cleanup['four_order_audit'] = False
        result['cleanup'] = cleanup
        result['ready'] = result['ready'] and all(cleanup.values())
    return result


def state(data):
    row = json.loads(data)
    if (set(row) != {'phase', 'records', 'remaining_seconds'} or
            row['phase'] not in {'RUNNING', 'COMPLETE', 'EXPIRED', 'ERROR', 'DISCONNECTED', 'STOPPED'} or
            type(row['records']) is not int or not 0 <= row['records'] <= 2 or
            type(row['remaining_seconds']) is not int or not 0 <= row['remaining_seconds'] <= 120 or
            row['phase'] != 'RUNNING' and row['remaining_seconds'] != 0):
        raise ValueError('status schema')
    return row


def analyze_private(raw, phrase):
    if raw.stat().st_size > 32768:
        raise ValueError('raw size')
    rows = json.loads(raw.read_bytes())
    if not isinstance(rows, list) or len(rows) > 2:
        raise ValueError('raw count')
    expected = {'kind', 'title', 'text', 'big_text', 'flags', 'category', 'style', 'additional',
                'group_present', 'channel_present', 'channel_fingerprint', 'presentation_valid'}
    additional = {'android.subText', 'android.summaryText', 'android.textLines', 'android.messages',
                  'android.messages.historic', 'android.title.big'}
    for r in rows:
        if (not isinstance(r, dict) or set(r) != expected or r['category'] not in CATEGORIES or r['style'] not in STYLES or
                type(r['flags']) is not int or not -(2**31) <= r['flags'] < 2**31 or
                any(type(r[k]) is not bool for k in ['group_present', 'channel_present', 'presentation_valid']) or
                not isinstance(r['additional'], dict) or set(r['additional']) != additional or
                any(type(v) is not bool for v in r['additional'].values()) or
                r['channel_fingerprint'] is not None and
                (not isinstance(r['channel_fingerprint'], str) or len(r['channel_fingerprint']) != 64 or
                 any(c not in '0123456789abcdef' for c in r['channel_fingerprint']))):
            raise ValueError('raw metadata')
    kinds = [r['kind'] for r in rows]
    if len(set(kinds)) != len(kinds) or any(k not in {'historical', 'posted'} for k in kinds):
        raise ValueError('raw slots')
    templates = []
    nonce_matches = False
    for row in rows:
        for key in ['title', 'text', 'big_text']:
            if row[key] is not None and (not isinstance(row[key], str) or len(row[key]) > 512):
                raise ValueError('raw field')
        if row['kind'] == 'posted':
            nonce_matches = any(phrase in (row[key] or '') for key in ['title', 'text', 'big_text'])
        # All arbitrary text is passed through the finite-vocabulary redactor.
        templates.append(dict(kind=row['kind'], **field_role_assessment(row['title'], row['text']),
            big_text_template=fixed_shape(row['big_text']), presentation_valid=row['presentation_valid'],
            flags=row['flags'], category=row['category'], style=row['style'], additional=row['additional'],
            group_present=row['group_present'], channel_present=row['channel_present']))
    private_json(raw.parent / 'templates.json', templates)
    env = dict(os.environ, OBSERVER_COMPARISON_RAW=str(raw),
               JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home',
               ANDROID_HOME='/Users/acccan/Library/Android/sdk')
    with (raw.parent / 'parser.log').open('xb') as log:
        subprocess.run(['./gradlew', '--offline', '--no-daemon', '--dependency-verification', 'strict',
            ':app:testProductionDebugUnitTest', '--tests', 'dev.epay.observer.ReceiptFieldResearchTest', '--rerun-tasks'],
            cwd=REPO_ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
    parser = json.loads((raw.parent / 'parser-results.json').read_bytes())
    equal = None
    if len(rows) == 2:
        fingerprints = [r['channel_fingerprint'] for r in rows]
        equal = all(fingerprints) and fingerprints[0] == fingerprints[1]
    return {'records': len(rows), 'posted_nonce_matches': nonce_matches,
            'channels_equal': equal, 'parser': parser, 'payment_eligibility': False}


def run_comparison(args, session):
    driver = ComparisonDriver(args.adb, args.serial)
    directory = session.directory
    before = None
    old = None
    touched = False
    raw = directory / 'raw.json'
    success = False
    try:
        before = listeners(driver)
        private_json(directory / 'listeners-before.json', sorted(before))
        if CONTENT_LISTENERS.intersection(before):
            raise ValueError('content listener enabled')
        audit(driver, args.lab)
        old = check_old(driver)
        check_unused(driver)
        touched = True
        for apk, package in [(REPO_ROOT / 'sample/build/outputs/apk/debug/sample-debug.apk', 'dev.epay.observer.sample.debug'),
                             (REPO_ROOT / 'sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk', PACKAGE)]:
            driver.ensure_installed(apk, package)
        driver.shell('am', 'start', '-W', '-n', PACKAGE + '/dev.epay.observer.sample.SnapshotPrepareActivity')
        if session.cancelled.is_set():
            raise InterruptedError()
        touched = True
        run_as(driver, 'sh', '-c', 'mkdir -p files && printf %s ' + shlex.quote(CONSENT) + ' > files/' + STEM + '-consent')
        session.arm_budget()
        driver.shell('cmd', 'notification', 'allow_listener', COMPONENT)
        deadline = time.monotonic() + 145
        last = None
        while time.monotonic() < deadline:
            if session.cancelled.is_set() or session.evidence_failed:
                raise InterruptedError()
            try:
                data = driver.adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/' + STEM + '-status.json', timeout=8)
            except subprocess.CalledProcessError:
                session.cancelled.wait(0.5)
                continue
            current = state(data)
            if current != last:
                last = current
                session.sample(dict(phase=current['phase'], remaining_seconds=current['remaining_seconds'], callbacks=current['records']))
                session.change(records=current['records'])
            if current['phase'] != 'RUNNING':
                break
            session.cancelled.wait(0.5)
        else:
            raise TimeoutError('device deadline')
        session.change(state='CLEANING', device_result=last)
        if last['phase'] not in {'COMPLETE', 'EXPIRED'}:
            raise ValueError('collector failed')
        with raw.open('xb') as target:
            driver.adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/' + STEM + '-raw.json', stdout=target)
        # Revoke/stop before private JVM analysis, which can take longer than the window.
        driver.shell('cmd', 'notification', 'disallow_listener', COMPONENT)
        driver.shell('am', 'force-stop', PACKAGE)
        result = analyze_private(raw, session.phrase)
        if result['records'] != last['records']:
            raise ValueError('record mismatch')
        session.change(**result, controlled_chat=bool(result['posted_nonce_matches'] and session.report['user_send_confirmation']))
        success = True
    except Exception as error:
        session.change(error_type=type(error).__name__)
    finally:
        session.change(state='CLEANING')
        cleanup = {}
        actions = {}
        if touched:
            actions = {
                'listener_revoked': lambda: driver.shell('cmd', 'notification', 'disallow_listener', COMPONENT),
                'test_process_stopped': lambda: driver.shell('am', 'force-stop', PACKAGE),
                'device_raw_deleted': lambda: run_as(driver, 'rm', '-f', *['files/' + STEM + '-' + s for s in ['raw.json', 'raw.json.pending', 'consent', 'status.json.pending']]),
                'device_raw_absent': lambda: [run_as(driver, 'test', '!', '-e', 'files/' + STEM + '-' + s) for s in ['raw.json', 'raw.json.pending', 'consent', 'status.json.pending']],
            }
        actions['host_raw_deleted'] = lambda: raw.unlink(missing_ok=True)
        for name, action in actions.items():
            try:
                action(); cleanup[name] = True
            except Exception:
                cleanup[name] = False
        if touched:
            try:
                run_as(driver, 'test', '-f', 'no_backup/' + MARKER)
                session.change(marker_used=True)
                cleanup['consumed_marker_preserved'] = True
            except subprocess.CalledProcessError:
                session.change(marker_used=False)
                cleanup['consumed_marker_preserved'] = not bool(session.report['samples'])
            except Exception:
                cleanup['marker_state_known'] = False
        try:
            after = listeners(driver)
            private_json(directory / 'listeners-after.json', sorted(after))
            cleanup['listeners_restored'] = before is not None and before == after
        except Exception:
            cleanup['listeners_restored'] = False
        try:
            cleanup['old_records_preserved'] = old is not None and old == check_old(driver)
        except Exception:
            cleanup['old_records_preserved'] = False
        try:
            audit(driver, args.lab); cleanup['four_order_audit'] = True
        except Exception:
            cleanup['four_order_audit'] = False
        ok = all(cleanup.values())
        session.change(state='FINISHED' if success and ok else 'FAILED', completed=success and ok,
                       cleanup=cleanup, cleanup_ok=ok)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554', choices=['emulator-5554'])
    parser.add_argument('--adb', type=Path, default=Path('/Users/acccan/Library/Android/sdk/platform-tools/adb'))
    parser.add_argument('--lab', required=True, type=Path)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    if not str(args.directory.resolve()).startswith('/private/tmp/observer-comparison-'):
        parser.error('Fresh private /private/tmp/observer-comparison-* directory required')
    os.umask(0o077)
    control_lock = acquire_control_lock(args.serial)
    if args.prepare_only:
        args.directory.mkdir(mode=0o700)
        try:
            result = prepare(ComparisonDriver(args.adb, args.serial), args.lab, args.directory)
            private_json(args.directory / 'prepare.json', result)
            print(json.dumps(result))
        finally:
            control_lock.close()
        return 0 if result['ready'] else 1
    session = ComparisonSession(args.directory, lambda current: run_comparison(args, current))
    token = secrets.token_urlsafe(32)
    server = make_server(session, token, 'receipt_chat.html')
    private_json(args.directory / 'control.json', {'url': f'http://127.0.0.1:{server.server_port}/#{token}', 'pid': os.getpid()})
    print('Comparison page WAITING; no content collection started.', flush=True)
    def terminate(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        session.stop()
        if session.thread is not None:
            session.thread.join()
        control_lock.close()


if __name__ == '__main__':
    raise SystemExit(main())
