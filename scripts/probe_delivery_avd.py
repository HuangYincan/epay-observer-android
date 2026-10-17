#!/usr/bin/env python3
"""Local, single-use start page for SPEC-028; never sends messages or reads their content."""
import argparse
import copy
import datetime
import fcntl
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import threading
import time

from probe_lifecycle_avd import PROBE, SOURCE, COMPONENT, RUNNER, EXPECTED_AUDIT

PREFIX = 'INSTRUMENTATION_STATUS: probe_live_state='


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def rows_from_log(text):
    rows = []
    for line in text.splitlines(keepends=True):
        if not line.endswith('\n') or not line.startswith(PREFIX):
            continue
        value = json.loads(line[len(PREFIX):])
        if type(value) is not dict:
            raise ValueError('Invalid state object')
        row = {}
        for key, options in {'event': {'armed', 'sample', 'finished'}, 'phase': {'RUNNING', 'EXPIRED'}}.items():
            if value.get(key) not in options:
                raise ValueError('Invalid fixed state')
            row[key] = value[key]
        for key, limit in {'callbacks': 500, 'remaining_seconds': 120, 'elapsed_ms': 180000}.items():
            number = value.get(key)
            if type(number) is not int or not 0 <= number <= limit:
                raise ValueError('Invalid aggregate')
            row[key] = number
        for key in ['connected', 'source_matches', 'screen_interactive', 'keyguard_locked', 'instrumented_process']:
            if type(value.get(key)) is not bool:
                raise ValueError('Invalid boolean')
            row[key] = value[key]
        if not all(row[k] for k in ['connected', 'source_matches', 'screen_interactive', 'instrumented_process']) or row['keyguard_locked']:
            raise ValueError('Observation condition changed')
        if not rows:
            if row['event'] != 'armed' or row['phase'] != 'RUNNING' or row['callbacks'] != 0:
                raise ValueError('Missing zero baseline')
        elif row['event'] == 'armed' or rows[-1]['event'] == 'finished' or row['callbacks'] < rows[-1]['callbacks'] or row['elapsed_ms'] < rows[-1]['elapsed_ms'] or row['remaining_seconds'] > rows[-1]['remaining_seconds'] or (rows[-1]['phase'] == 'EXPIRED' and row['phase'] != 'EXPIRED'):
            raise ValueError('Non-monotonic observation')
        if row['event'] == 'finished' and (row['phase'] != 'EXPIRED' or row['remaining_seconds'] != 0 or row['elapsed_ms'] < 119000):
            raise ValueError('Window did not expire')
        rows.append(row)
    return rows


class DeliverySession:
    def __init__(self, directory, runner, page_test=False):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700)  # Existing evidence is never reused.
        self.lock = threading.RLock()
        self.cancelled = threading.Event()
        self.runner = runner
        self.thread = None
        self.last_sample_at = None
        self.delivery_deadline = None
        self.evidence_failed = False
        self.report = {'schema_version': 1, 'utc_created': utc(), 'state': 'WAITING', 'revision': 0,
                       'scope': 'User-started single Alipay metadata-only window; no message/payment trigger',
                       'layer': 'page_test' if page_test else 'emulator_real_app',
                       'samples': [], 'cleanup': {}, 'completed': False, 'start_claimed': False,
                       'user_send_confirmation': None, 'notification_delivery': 'Not assessed',
                       'instrumentation_priority_limitation': True}
        self.change()
        if self.evidence_failed:
            raise OSError('Cannot create private evidence')

    def change(self, **fields):
        with self.lock:
            self.report.update(fields)
            self.report['revision'] += 1
            temporary = self.directory / 'report.json.tmp'
            try:
                temporary.write_text(json.dumps(self.report, indent=2, ensure_ascii=False))
                temporary.chmod(0o600)
                temporary.replace(self.directory / 'report.json')
            except OSError:
                # A broken evidence disk must never prevent device cleanup.
                self.evidence_failed = True
                self.report['evidence_write_failed'] = True

    def snapshot(self):
        with self.lock:
            return copy.deepcopy(self.report)

    def sample(self, row):
        with self.lock:
            row = dict(row, host_utc_received=utc())
            self.last_sample_at = time.monotonic()
            self.report['samples'].append(row)
            self.change(state='OBSERVING')
            if self.evidence_failed:
                raise OSError('Cannot preserve observation')

    def arm_budget(self):
        with self.lock:
            if self.delivery_deadline is not None:
                raise ValueError('Host send budget cannot be renewed')
            # Starting before the child launches makes this a conservative cutoff,
            # even if ADB buffers an old armed/status row before delivering it.
            self.delivery_deadline = time.monotonic() + 120

    def public(self):
        with self.lock:
            r = self.report
            row = r['samples'][-1] if r['samples'] else None
            now = time.monotonic()
            age = now - self.last_sample_at if self.last_sample_at is not None else math.inf
            budget = max(0, math.floor(self.delivery_deadline - now)) if self.delivery_deadline is not None else 0
            remaining = min(budget, max(0, row['remaining_seconds'] - math.ceil(age))) if row else 0
            live = bool(r['state'] == 'OBSERVING' and row and row['phase'] == 'RUNNING' and
                        age <= 12 and remaining > 0 and not self.cancelled.is_set() and not self.evidence_failed)
            return {'state': r['state'], 'revision': r['revision'], 'layer': r['layer'],
                    'can_start': not r['start_claimed'] and r['state'] == 'WAITING',
                    'can_send': live, 'remaining_seconds': remaining if live else 0,
                    'callbacks': row['callbacks'] if row else 0,
                    'user_confirmed': r['user_send_confirmation'] is not None,
                    'notification_delivery': r['notification_delivery'],
                    'cleanup_ok': r.get('cleanup_ok'), 'completed': r['completed']}

    def start(self):
        with self.lock:
            if self.report['start_claimed'] or self.report['state'] != 'WAITING':
                return False
            self.change(start_claimed=True, state='PREPARING', utc_requested=utc())
            if self.evidence_failed:
                self.change(state='FAILED')
                return False
            self.thread = threading.Thread(target=self._work, name='probe-delivery')
            self.thread.start()
            return True

    def _work(self):
        try:
            self.runner(self)
        except Exception as error:
            # Browser and public report never receive arbitrary subprocess output.
            self.change(state='FAILED', failure_type=type(error).__name__, completed=False)

    def confirm_sent(self):
        with self.lock:
            if not self.report['start_claimed']:
                return False
            if self.report['user_send_confirmation'] is None:
                self.change(user_send_confirmation={'host_utc_recorded': utc(), 'user_reported_count': 1,
                    'window_live_at_ack': self.public()['can_send'],
                    'send_timestamp': 'User report only; not independently measured'})
                if self.evidence_failed:
                    self.cancelled.set()
            return True

    def stop(self):
        with self.lock:
            self.cancelled.set()
            if self.report['state'] == 'WAITING':
                self.change(state='CANCELLED', start_claimed=True)


class AvdDelivery:
    def __init__(self, serial, lab, sdk):
        self.adb = [str(Path(sdk) / 'platform-tools/adb'), '-s', serial]
        self.lab = Path(lab)
        self.deadline = None
        self.session = None

    def command(self, *args, cleanup=False):
        if not cleanup and self.session.cancelled.is_set():
            raise InterruptedError('Cancelled')
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Host deadline')
        return subprocess.check_output(self.adb + list(args), text=True, stderr=subprocess.PIPE,
                                       timeout=min(12, remaining)).strip()

    def shell(self, command, cleanup=False):
        return self.command('shell', command, cleanup=cleanup)

    def audit(self, cleanup=False):
        if not cleanup and self.session.cancelled.is_set():
            raise InterruptedError('Cancelled')
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Host deadline')
        output = subprocess.check_output(['python3', str(Path(__file__).parent / 'prototype/audit_recharge.py'),
            str(self.lab)], text=True, stderr=subprocess.PIPE, timeout=min(30, remaining))
        result = json.loads(output)
        if result != EXPECTED_AUDIT:
            raise ValueError('Four-order baseline changed')
        return result

    def reset_probe(self, cleanup=False):
        def pids():
            values = set(self.shell('pidof ' + PROBE + ' || true', cleanup=cleanup).split())
            if any(not value.isdecimal() for value in values):
                raise ValueError('Unknown probe process metadata')
            return values
        previous = pids()  # Compare only in memory; never export process identifiers.
        self.shell('am force-stop ' + PROBE, cleanup=cleanup)
        end = time.monotonic() + 5
        while True:
            if not previous.intersection(pids()):
                return True
            if time.monotonic() >= end:
                raise TimeoutError('Old probe process survived')
            time.sleep(0.1)

    def state(self, cleanup=False):
        def sh(command):
            return self.shell(command, cleanup=cleanup)
        power = re.search(r'mWakefulness=(Awake|Asleep|Dozing|Dreaming)\b', sh('dumpsys power'))
        if power is None:
            raise ValueError('Unknown screen state')
        packages = {}
        def package_dump(package):
            if package not in packages:
                packages[package] = sh('dumpsys package ' + package)
            return packages[package]
        def stopped(package):
            user = next((line for line in package_dump(package).splitlines() if 'User 0:' in line), '')
            match = re.search(r'\bstopped=(true|false)\b', user)
            if match is None:
                raise ValueError('Unknown package state')
            return match[1] == 'true'
        result = {'screen_awake': power[1] in {'Awake', 'Dreaming'},
            'lockscreen_disabled': sh('locksettings get-disabled --user 0'),
            'wifi': sh('settings get global wifi_on'), 'mobile': sh('settings get global mobile_data'),
            'probe_access': COMPONENT in sh('settings get secure enabled_notification_listeners').split(':'),
            'probe_stopped': stopped(PROBE), 'probe_process_present': bool(sh('pidof ' + PROBE + ' || true')),
            'source_stopped': stopped(SOURCE), 'source_process_present': bool(sh('pidof ' + SOURCE + ' || true')),
            'battery_override': 'UPDATES STOPPED' in sh('dumpsys battery'),
            'forced_doze': bool(re.search(r'mForceIdle=true', sh('dumpsys deviceidle')))}
        permission = re.search(r'android.permission.POST_NOTIFICATIONS: granted=(true|false)', package_dump(SOURCE))
        appop = re.search(r'(?:POST_NOTIFICATION|Default mode): (allow|ignore|deny|default|foreground)\b',
                          sh('cmd appops get ' + SOURCE + ' POST_NOTIFICATION'))
        result['source_notifications_granted'] = permission is not None and permission[1] == 'true'
        result['source_notifications_allowed'] = appop is not None and appop[1] == 'allow'
        if result['wifi'] not in {'0', '1'} or result['mobile'] not in {'0', '1'}:
            raise ValueError('Unknown network setting')
        return result

    def __call__(self, session):
        self.session = session
        self.deadline = time.monotonic() + 90  # Preparation deadline, before any observation.
        initial = None
        touched = False
        child = None
        passed = False
        cleanup = {}
        try:
            if self.command('get-state') != 'device' or self.shell('getprop ro.kernel.qemu') != '1' or self.shell('am get-current-user') != '0':
                raise ValueError('Dedicated primary-user emulator required')
            initial = self.state()
            session.change(initial=initial)
            if not initial['probe_access']:
                raise ValueError('Probe permission changed')
            if initial['lockscreen_disabled'] != 'true' or initial['battery_override'] or initial['forced_doze']:
                raise ValueError('Existing system condition is not modified')
            if initial['source_stopped'] or not initial['source_process_present']:
                raise ValueError('Source is not running; no business UI opened')
            if not initial['source_notifications_granted'] or not initial['source_notifications_allowed']:
                raise ValueError('Source notification permission is not allowed')
            session.change(audit_before=self.audit())
            if session.evidence_failed:
                raise OSError('Cannot preserve preflight')
            touched = True
            session.change(probe_reset_before_start=self.reset_probe())
            if session.evidence_failed:
                raise OSError('Cannot preserve probe reset')
            self.shell('input keyevent KEYCODE_WAKEUP')
            log_path = session.directory / 'instrumentation.txt'
            self.deadline = time.monotonic() + 180
            with log_path.open('x') as log:
                session.arm_budget()
                child = subprocess.Popen(self.adb + ['shell', 'am', 'instrument', '-w', '-r', '-e',
                    'probeUserMessage', 'true', '-e', 'class', 'dev.epay.observer.probe.ProbeLiveDeliveryTest', RUNNER],
                    stdout=log, stderr=subprocess.STDOUT)
                consumed = 0
                while True:
                    if session.cancelled.is_set():
                        raise InterruptedError('Cancelled')
                    if time.monotonic() >= self.deadline:
                        raise TimeoutError('Observation deadline')
                    if log_path.stat().st_size > 1024 * 1024:
                        raise ValueError('Unexpected log size')
                    rows = rows_from_log(log_path.read_text())
                    for row in rows[consumed:]:
                        session.sample(row)
                    consumed = len(rows)
                    if child.poll() is not None:
                        break
                    session.cancelled.wait(0.2)
                text = log_path.read_text()
                if len(text) > 1024 * 1024:
                    raise ValueError('Unexpected final log size')
                rows = rows_from_log(text)
                for row in rows[consumed:]:
                    session.sample(row)
                if child.returncode != 0 or 'OK (1 test)' not in text or any(
                        bad in text for bad in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=']):
                    raise ValueError('Instrumentation failed')
                if not rows or rows[-1]['event'] != 'finished':
                    raise ValueError('Missing final window state')
                count = rows[-1]['callbacks']
                session.change(condition_callbacks=count,
                    notification_delivery='Observed' if count else 'Not exercised')
                passed = True
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            session.change(failure_type=type(error).__name__)
        finally:
            session.change(state='CLEANING')
            cleanup['driver_exit_confirmed'] = True
            try:
                if child is not None and child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)
            except (OSError, subprocess.SubprocessError):
                cleanup['driver_exit_confirmed'] = False
            if touched:
                self.deadline = time.monotonic() + 75
                try:
                    cleanup['probe_reset_verified'] = self.reset_probe(cleanup=True)
                except (OSError, ValueError, subprocess.SubprocessError):
                    cleanup['probe_reset_verified'] = False
                try:
                    self.shell('input keyevent ' + ('KEYCODE_WAKEUP' if initial['screen_awake'] else 'KEYCODE_SLEEP'), cleanup=True)
                    final = self.state(cleanup=True)
                    session.change(final=final)
                    cleanup['settings_match'] = all(final[k] == initial[k] for k in
                        ['screen_awake', 'lockscreen_disabled', 'wifi', 'mobile', 'probe_access', 'battery_override', 'forced_doze',
                         'source_notifications_granted', 'source_notifications_allowed'])
                    cleanup['source_running_not_stopped'] = not final['source_stopped'] and final['source_process_present']
                except (OSError, ValueError, subprocess.SubprocessError):
                    cleanup['final_state_check'] = False
                try:
                    session.change(audit_after=self.audit(cleanup=True))
                    cleanup['audit_expected_unchanged'] = True
                except (OSError, ValueError, subprocess.SubprocessError):
                    cleanup['audit_expected_unchanged'] = False
            cleanup_ok = touched and all(cleanup.get(k) is True for k in ['driver_exit_confirmed', 'probe_reset_verified',
                'settings_match', 'source_running_not_stopped', 'audit_expected_unchanged'])
            terminal = 'FAILED' if session.evidence_failed else ('CANCELLED' if session.cancelled.is_set() and (not touched or cleanup_ok) else (
                'FINISHED' if passed and cleanup_ok else 'FAILED'))
            session.change(state=terminal, cleanup=cleanup, cleanup_ok=cleanup_ok if touched else None,
                           completed=terminal == 'FINISHED', utc_finished=utc())
            if session.evidence_failed:
                session.change(state='FAILED', completed=False)


def make_server(session, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # No URL/header/token request logs.

        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def reply(self, code, value, html=False, nonce=None):
            body = value.encode() if html else json.dumps(value).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'text/html; charset=utf-8' if html else 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'none'; connect-src 'self'; "
                + (f"script-src 'nonce-{nonce}'; style-src 'nonce-{nonce}'; " if nonce else '')
                + "base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
            self.end_headers()
            self.wfile.write(body)

        def allowed(self, auth=True, mutation=False):
            origin = 'http://127.0.0.1:' + str(self.server.server_port)
            if self.headers.get_all('Host', []) != [origin[len('http://'):]]:
                self.reply(403, {'error': 'forbidden'})
                return False
            origins = self.headers.get_all('Origin', [])
            if (mutation and origins != [origin]) or (origins and origins != [origin]):
                self.reply(403, {'error': 'forbidden'})
                return False
            tokens = self.headers.get_all('X-Probe-Token', [])
            if auth and (len(tokens) != 1 or not hmac.compare_digest(tokens[0].encode(), token.encode())):
                self.reply(403, {'error': 'forbidden'})
                return False
            return True

        def do_GET(self):
            if not self.allowed(auth=self.path != '/'):
                return
            if self.path == '/':
                nonce = secrets.token_urlsafe(24)
                html = (Path(__file__).parent / 'prototype/probe_delivery.html').read_text().replace('{{NONCE}}', nonce)
                self.reply(200, html, html=True, nonce=nonce)
            elif self.path == '/api/state':
                self.reply(200, session.public())
            else:
                self.reply(404, {'error': 'not found'})

        def do_POST(self):
            if not self.allowed(mutation=True):
                return
            if self.headers.get('Transfer-Encoding') is not None or self.headers.get_all('Content-Length', []) not in ([], ['0']):
                self.close_connection = True
                self.reply(400, {'error': 'empty operation required'})
                return
            if self.path == '/api/start':
                self.reply(202 if session.start() else 409, session.public())
            elif self.path == '/api/sent':
                self.reply(200 if session.confirm_sent() else 409, session.public())
            elif self.path == '/api/stop':
                session.stop()
                self.reply(200, session.public())
            else:
                self.reply(404, {'error': 'not found'})

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    return server


def acquire_control_lock(serial, directory=None):
    directory = Path(directory) if directory else Path(__file__).resolve().parent.parent / 'build/probe-control'
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = directory / (serial + '.lock')
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    stream = os.fdopen(descriptor, 'a')
    try:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        stream.close()
        raise
    return stream


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('--lab', type=Path, required=True)
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('Explicit emulator-NNNN required')
    directory, lab = args.directory.resolve(), args.lab.resolve()
    if not str(directory).startswith(('/tmp/observer-probe-local-', '/private/tmp/observer-probe-local-')) or directory.exists():
        parser.error('New private /tmp/observer-probe-local-* directory required')
    if not str(lab).startswith(('/tmp/epay-observer-', '/private/tmp/epay-observer-')) or not lab.is_dir():
        parser.error('Existing isolated lab required')
    os.umask(0o077)
    try:
        control_lock = acquire_control_lock(args.serial)
    except OSError:
        parser.error('Another local control owns this emulator, or control lock unavailable')
    sdk = os.environ.get('ANDROID_HOME') or str(Path.home() / 'Library/Android/sdk')
    session = DeliverySession(directory, AvdDelivery(args.serial, lab, sdk))
    token = secrets.token_urlsafe(32)
    server = make_server(session, token)
    (directory / 'control.json').write_text(json.dumps({'url': f'http://127.0.0.1:{server.server_port}/#{token}',
                                                     'pid': os.getpid()}))
    print('Local control ready; private URL in ' + str(directory / 'control.json'), flush=True)
    def terminate(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, terminate)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        session.stop()
        if session.thread is not None:
            session.thread.join()  # Keep emulator ownership until bounded cleanup has finished.
        control_lock.close()


if __name__ == '__main__':
    main()
