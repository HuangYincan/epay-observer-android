#!/usr/bin/env python3
"""SPEC-031: exactly one private emulator window; never print notification content."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

STATE_PREFIX = 'INSTRUMENTATION_STATUS: content_sample_state='
SCAN_MARKER = 'used-scan-payment-v1'
UNPAID_RETRY_MARKER = 'used-scan-payment-unpaid-retry-v1'


def state_rows(text):
    rows = []
    for line in text.splitlines(keepends=True):
        if not line.endswith('\n') or not line.startswith(STATE_PREFIX):
            continue
        if len(line) > 4096:
            raise ValueError('state size')
        row = json.loads(line[len(STATE_PREFIX):])
        expected = {'event', 'phase', 'callbacks', 'elapsed_ms', 'remaining_seconds',
                    'connected', 'source_matches', 'instrumented_process'}
        if not isinstance(row, dict) or set(row) != expected:
            raise ValueError('state schema')
        if row['event'] not in {'armed', 'sample', 'finished'} or row['phase'] not in {
                'RUNNING', 'EXPIRED', 'STOPPED', 'DISCONNECTED', 'SOURCE_CHANGED', 'LIMIT', 'ERROR'}:
            raise ValueError('state phase')
        for key, maximum in [('callbacks', 32), ('elapsed_ms', 180000), ('remaining_seconds', 120)]:
            if type(row[key]) is not int or not 0 <= row[key] <= maximum:
                raise ValueError('state aggregate')
        for key in ['connected', 'source_matches', 'instrumented_process']:
            if type(row[key]) is not bool:
                raise ValueError('state boolean')
        if not row['instrumented_process']:
            raise ValueError('instrumentation required')
        if row['phase'] == 'RUNNING' and not (row['connected'] and row['source_matches']):
            raise ValueError('running condition changed')
        if row['phase'] != 'RUNNING' and row['remaining_seconds'] != 0:
            raise ValueError('terminal countdown')
        if not rows:
            if row['event'] != 'armed' or row['phase'] != 'RUNNING':
                raise ValueError('missing armed state')
        else:
            previous = rows[-1]
            if previous['event'] == 'finished' or row['event'] == 'armed' or any([
                    row['callbacks'] < previous['callbacks'], row['elapsed_ms'] < previous['elapsed_ms'],
                    row['remaining_seconds'] > previous['remaining_seconds'],
                    previous['phase'] != 'RUNNING' and row['phase'] == 'RUNNING']):
                raise ValueError('nonmonotonic state')
        if row['event'] == 'finished' and row['phase'] == 'RUNNING':
            raise ValueError('unfinished window')
        rows.append(row)
    return rows

PACKAGE = 'dev.epay.observer.sample.debug'
COMPONENT = PACKAGE + '/dev.epay.observer.sample.AlipayListener'
REPO_ROOT = Path(__file__).resolve().parents[1]
BASELINE = dict(audited_orders=4, distinct_event_consumptions=4,
                distinct_income_ledger_rows=4, newapi_topups_success=4, quota_added=30_000_000)
MAX_RAW = 262144
WORDS = ('支付宝', '收钱码', '人民币', '收款', '到账', '成功', '付款', '收入', '转账', '金额', '元')
TOKENS = re.compile('|'.join(map(re.escape, WORDS)) + r'|\d+(?:[.．]\d+)?|[，。:：.,￥¥()（）\- ]')


def shape(value):
    """Allow public fixed words only. Arbitrary names, prose and Unicode are masked."""
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError('field type')
    pieces = []
    end = 0
    for match in TOKENS.finditer(value):
        if match.start() > end:
            pieces.append('<text>')
        token = match.group()
        pieces.append('<number>' if token[0].isdigit() else token)
        end = match.end()
    if end < len(value):
        pieces.append('<text>')
    return ''.join(pieces)


def extract(raw_path):
    if raw_path.stat().st_size > MAX_RAW:
        raise ValueError('raw size limit')
    rows = []
    with raw_path.open(encoding='utf-8') as source:
        for line in source:
            if len(rows) >= 32:
                raise ValueError('sample count limit')
            item = json.loads(line)
            if not isinstance(item, dict) or set(item) != {'title', 'text'}:
                raise ValueError('raw schema')
            for field, limit in [('title', 256), ('text', 1024)]:
                value = item[field]
                if value is not None and (not isinstance(value, str) or len(value) > limit):
                    raise ValueError('field bound')
            rows.append({key + '_template': shape(value) for key, value in item.items()})
    return rows


def private_json(path, value):
    with path.open('x', encoding='utf-8') as target:
        os.chmod(path, 0o600)
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write('\n')


def process_raw(raw_path, templates_path):
    try:
        rows = extract(raw_path)
        private_json(templates_path, rows)
        return len(rows)
    finally:
        raw_path.unlink(missing_ok=True)


class Driver:
    def __init__(self, adb, serial):
        self.prefix = [str(adb), '-s', serial]

    def adb(self, *args, timeout=30, stdout=subprocess.PIPE):
        result = subprocess.run(self.prefix + list(args), stdout=stdout, stderr=subprocess.DEVNULL,
                                timeout=timeout, check=True)
        return result.stdout

    def shell(self, *args):
        return self.adb('shell', *args).decode().strip()

    def copy_private(self, name, destination):
        # No raw content enters captured stdout, exception text, terminal or model output.
        with destination.open('xb') as target:
            os.chmod(destination, 0o600)
            self.adb('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/' + name, stdout=target)

    def remove_device_raw(self):
        self.shell('run-as', PACKAGE, 'rm', '-f', 'no_backup/samples.jsonl')
        self.shell('run-as', PACKAGE, 'test', '!', '-e', 'no_backup/samples.jsonl')

    def observe_receipt(self, log_path, progress, cancel, unpaid_retry=False):
        arguments = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'contentSampleConsent',
                     'scan-payment-unpaid-retry-once-120s-v1' if unpaid_retry else 'scan-payment-once-120s-v1', '-e', 'class',
                     'dev.epay.observer.sample.ContentSampleTest#sampleOnce',
                     PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        with log_path.open('xb') as log:
            if cancel.is_set():
                raise InterruptedError('cancelled before launch')
            progress({'event': 'launching'})  # Conservative host budget starts before Android startup.
            if cancel.is_set():
                raise InterruptedError('cancelled before launch')
            child = subprocess.Popen(self.prefix + arguments, stdout=log, stderr=subprocess.DEVNULL)
            deadline = time.monotonic() + 180
            seen = 0
            try:
                while True:
                    if cancel.is_set():
                        raise InterruptedError('cancelled')
                    if time.monotonic() > deadline:
                        raise TimeoutError('instrumentation timeout')
                    if log_path.stat().st_size > 262144:
                        raise ValueError('instrumentation size')
                    rows = state_rows(log_path.read_text())
                    for row in rows[seen:]:
                        progress(row)
                    seen = len(rows)
                    if child.poll() is not None:
                        if child.returncode:
                            raise ValueError('instrumentation process failed')
                        if not rows or rows[-1]['event'] != 'finished':
                            raise ValueError('missing terminal status')
                        return
                    cancel.wait(0.2)
            finally:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.kill(); child.wait(timeout=5)


def cleanup(driver, raw_path):
    """Each action runs despite earlier cleanup failures; only fixed labels are reported."""
    results = {}
    actions = {
        'permission_revoked': lambda: driver.shell('cmd', 'notification', 'disallow_listener', COMPONENT),
        'process_stopped': lambda: driver.shell('am', 'force-stop', PACKAGE),
        'device_raw_deleted': driver.remove_device_raw,
        'host_raw_deleted': lambda: raw_path.unlink(missing_ok=True),
    }
    for label, action in actions.items():
        try:
            action()
            results[label] = True
        except Exception:
            results[label] = False
    return results


def validate_window(window):
    expected = {'phase', 'samples', 'elapsed_ms', 'started_epoch_ms', 'ended_epoch_ms', 'instrumented_process'}
    if not isinstance(window, dict) or set(window) != expected:
        raise ValueError('window schema')
    if window['phase'] not in {'EXPIRED', 'LIMIT'} or window['instrumented_process'] is not True:
        raise ValueError('window failed')
    for key in ['samples', 'elapsed_ms', 'started_epoch_ms', 'ended_epoch_ms']:
        if type(window[key]) is not int or window[key] < 0:
            raise ValueError('window field type')
    if not 0 <= window['samples'] <= 32 or window['ended_epoch_ms'] < window['started_epoch_ms']:
        raise ValueError('window bounds')
    if window['phase'] == 'EXPIRED' and window['elapsed_ms'] < 120000:
        raise ValueError('short window')
    if window['phase'] == 'LIMIT' and window['samples'] != 32:
        raise ValueError('capacity mismatch')


def audit(lab):
    result = subprocess.run([sys.executable, str(REPO_ROOT / 'scripts/prototype/audit_recharge.py'), str(lab)],
                            capture_output=True, timeout=60, check=True)
    value = json.loads(result.stdout)
    # audit helper has a stable aggregate-only schema. Reject unexpected accounting.
    if value != BASELINE:
        raise ValueError('accounting baseline')
    return value


def execute(args, progress=None, cancel=None):
    if not re.fullmatch(r'emulator-\d+', args.serial):
        raise ValueError('emulator required')
    unpaid_retry = getattr(args, 'unpaid_retry', False)
    if unpaid_retry and not getattr(args, 'receipt_window', False):
        raise ValueError('retry requires local scan control')
    os.umask(0o077)
    if args.evidence.resolve().is_relative_to(REPO_ROOT):
        raise ValueError('private evidence must be outside repository')
    args.evidence.mkdir(mode=0o700)  # exclusive, never replace a previous attempt
    driver = Driver(args.adb, args.serial)
    raw = args.evidence / 'raw.jsonl'
    report = {'spec': 'SPEC-031', 'issue': 73, 'started_utc': utc(), 'status': 'Failed',
              'real_payment': 'Not assessed', 'notification_delivery': 'Not assessed'}
    before_listeners = None
    touched = False
    failed = False
    receipt_window = getattr(args, 'receipt_window', False)
    if receipt_window:
        report.update(spec='SPEC-032', issue=75, scope='user-started-scan-payment-title-text')
    if unpaid_retry:
        report.update(spec='SPEC-034', issue=79, scope='user-consented-unpaid-scan-retry-title-text',
                      previous_payment_feedback='User reported not paid; not a platform record')
    try:
        if driver.shell('getprop', 'ro.kernel.qemu') != '1':
            raise ValueError('emulator required')
        devices = driver.adb('devices').decode().splitlines()[1:]
        if [line.split()[0] for line in devices if line.strip()] != [args.serial]:
            raise ValueError('unique device required')
        before_listeners = driver.shell('settings', 'get', 'secure', 'enabled_notification_listeners')
        if COMPONENT in before_listeners.split(':'):
            raise ValueError('unexpected existing sample access')
        # Existing app data and used marker are preserved. Reinstalling cannot reset the window.
        report['audit_before'] = audit(args.lab)
        driver.adb('install', '-r', str(args.apk), timeout=60)
        driver.adb('install', '-r', str(args.runner), timeout=60)
        touched = True
        # Verify absence without listing names or printing contents.
        marker = UNPAID_RETRY_MARKER if unpaid_retry else (SCAN_MARKER if receipt_window else 'used')
        driver.shell('run-as', PACKAGE, 'test', '!', '-e', 'no_backup/' + marker)
        if unpaid_retry:
            for previous in ['used', SCAN_MARKER]:
                driver.shell('run-as', PACKAGE, 'test', '-e', 'no_backup/' + previous)
        if receipt_window:
            if progress is None or cancel is None or cancel.is_set():
                raise ValueError('local control required')
            driver.shell('run-as', PACKAGE, 'test', '!', '-e', 'no_backup/samples.jsonl')
            driver.shell('input', 'keyevent', 'KEYCODE_HOME')
            report['alipay_background_requested'] = True
        driver.shell('cmd', 'notification', 'allow_listener', COMPONENT)
        if receipt_window:
            driver.observe_receipt(args.evidence / 'instrumentation.txt', progress, cancel, unpaid_retry=unpaid_retry)
        else:
            with (args.evidence / 'instrumentation.txt').open('xb') as log:
                driver.adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'contentSampleConsent', 'local-once-120s',
                           '-e', 'class', 'dev.epay.observer.sample.ContentSampleTest#sampleOnce',
                           PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=180, stdout=log)
        if 'OK (1 test)' not in (args.evidence / 'instrumentation.txt').read_text():
            raise ValueError('instrumentation did not pass')
        # Stop callbacks before reading files; raw has never been printed.
        driver.shell('cmd', 'notification', 'disallow_listener', COMPONENT)
        driver.shell('am', 'force-stop', PACKAGE)
        driver.copy_private('window.json', args.evidence / 'window.json')
        window = json.loads((args.evidence / 'window.json').read_text())
        validate_window(window)
        report['window'] = window
        if window['samples']:
            driver.copy_private('samples.jsonl', raw)
            count = process_raw(raw, args.evidence / 'templates.json')
            if count != window['samples']:
                raise ValueError('sample count mismatch')
        else:
            count = 0
            private_json(args.evidence / 'templates.json', [])
        report['samples'] = count
        report['notification_delivery'] = 'Observed' if count else 'Not exercised'
        report['status'] = 'Passed'
    except Exception as error:
        failed = True
        report['failure_type'] = type(error).__name__  # never expose exception content
    finally:
        if touched:
            report['cleanup'] = cleanup(driver, raw)
            if not all(report['cleanup'].values()):
                report['status'] = 'Failed'
        try:
            report['audit_after'] = audit(args.lab)
            report['listeners_restored'] = before_listeners is not None and driver.shell(
                'settings', 'get', 'secure', 'enabled_notification_listeners') == before_listeners
            if not report['listeners_restored']:
                report['status'] = 'Failed'
        except Exception as error:
            report['status'] = 'Failed'
            report['final_audit_error_type'] = type(error).__name__
        report['ended_utc'] = utc()
        private_json(args.evidence / 'report.json', report)
    return 1 if failed or report['status'] != 'Passed' else 0


def utc():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--lab', required=True, type=Path)
    parser.add_argument('--apk', type=Path, default=Path('sample/build/outputs/apk/debug/sample-debug.apk'))
    parser.add_argument('--runner', type=Path, default=Path('sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk'))
    args = parser.parse_args()
    try:
        code = execute(args)
    except Exception:
        print('Private sample preflight failed; no content printed.')
        return 1
    print('Private sample finished; see private aggregate report. No content printed.')
    return code


if __name__ == '__main__':
    sys.exit(main())
