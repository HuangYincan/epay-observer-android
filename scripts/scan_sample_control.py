#!/usr/bin/env python3
"""SPEC-032: user-started local content window; never creates an order or a payment."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import signal
from types import SimpleNamespace

from private_content_sample import execute, REPO_ROOT, utc
from probe_delivery_avd import DeliverySession, acquire_control_lock, make_server


class ScanSession(DeliverySession):
    def __init__(self, directory, runner, page_test=False, unpaid_retry=False):
        self.unpaid_retry = unpaid_retry
        super().__init__(directory, runner, page_test)
        self.change(spec='SPEC-032', issue=75,
                    scope='User-started single scan-payment title/text sample; local private processing',
                    user_payment_confirmation=None, real_payment='Not assessed',
                    automatic_recharge='Not implemented for real notifications')
        if unpaid_retry:
            self.change(spec='SPEC-034', issue=79, scope='User-consented additional unpaid scan title/text sample',
                        previous_payment_feedback='User reported not paid; not a platform record')

    def confirm_sent(self):
        with self.lock:
            if not self.public()['can_send']:
                return False
            if self.report['user_payment_confirmation'] is None:
                self.change(user_payment_confirmation={
                    'host_utc_recorded': utc(), 'window_live_at_ack': True,
                    'evidence': 'User feedback only; not a platform payment timestamp or receipt'})
            return not self.evidence_failed

    def public(self):
        with self.lock:
            result = super().public()
            result['user_confirmed'] = self.report.get('user_payment_confirmation') is not None
            result['notification_delivery'] = self.report['notification_delivery']
            result['unpaid_retry'] = self.unpaid_retry
            return result


def run_sample(args, session):
    def progress(row):
        if row['event'] == 'launching':
            session.arm_budget()
        else:
            session.sample(row)
            if row['phase'] != 'RUNNING':
                session.change(state='CLEANING')
    sample_args = SimpleNamespace(serial=args.serial, adb=args.adb, lab=args.lab,
        evidence=session.directory / 'sample', receipt_window=True,
        unpaid_retry=args.unpaid_retry,
        apk=REPO_ROOT / 'sample/build/outputs/apk/debug/sample-debug.apk',
        runner=REPO_ROOT / 'sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk')
    code = execute(sample_args, progress, session.cancelled)
    report = json.loads((sample_args.evidence / 'report.json').read_text())
    cleanup_ok = all(report.get('cleanup', {}).values()) and bool(report.get('cleanup')) and report.get('listeners_restored') is True
    state = 'FINISHED' if code == 0 and cleanup_ok else ('CANCELLED' if session.cancelled.is_set() and cleanup_ok else 'FAILED')
    session.change(state=state, completed=state == 'FINISHED', cleanup=report.get('cleanup', {}),
                   cleanup_ok=cleanup_ok, notification_delivery=report['notification_delivery'],
                   sample_result=report, utc_finished=utc())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--lab', required=True, type=Path)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--unpaid-retry', action='store_true', help='Prepare the fixed additional slot; user must consent locally')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Explicit emulator required')
    if args.directory.resolve().is_relative_to(REPO_ROOT) or not str(args.directory.resolve()).startswith('/private/tmp/observer-scan-'):
        parser.error('New private /private/tmp/observer-scan-* directory required')
    os.umask(0o077)
    control_lock = acquire_control_lock(args.serial)
    session = ScanSession(args.directory, lambda current: run_sample(args, current), unpaid_retry=args.unpaid_retry)
    token = secrets.token_urlsafe(32)
    server = make_server(session, token, 'scan_sample.html')
    control = session.directory / 'control.json'
    control.write_text(json.dumps({'url': f'http://127.0.0.1:{server.server_port}/#{token}', 'pid': os.getpid()}))
    control.chmod(0o600)
    print('Scan sampling page is waiting; no content window has started.', flush=True)
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
    main()
