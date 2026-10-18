"""SPEC-032 synthetic contracts only. No emulator, notification or payment is exercised."""
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from private_content_sample import Driver, STATE_PREFIX, state_rows
from scan_sample_control import ScanSession
from probe_delivery_avd import make_server


def row(event='armed', phase='RUNNING', elapsed=0, remaining=120, callbacks=0):
    return dict(event=event, phase=phase, callbacks=callbacks, elapsed_ms=elapsed,
                remaining_seconds=remaining, connected=True, source_matches=True, instrumented_process=True)


def line(value):
    return STATE_PREFIX + json.dumps(value) + '\n'


class ScanContracts(unittest.TestCase):
    def test_fixed_states_half_lines_and_private_fields_rejected(self):
        armed = row()
        self.assertEqual([armed], state_rows(line(armed) + line(row('sample', elapsed=1000, remaining=119))[:-1]))
        for changes in [dict(text='private'), dict(callbacks=True), dict(phase='unknown'),
                        dict(source_matches=False), dict(connected=False), dict(remaining_seconds=121)]:
            with self.subTest(changes=changes), self.assertRaises((ValueError, TypeError)):
                state_rows(line(dict(armed, **changes)))

    def test_state_monotonicity_and_terminal_window(self):
        initial = line(row())
        finished = row('finished', 'EXPIRED', elapsed=120001, remaining=0)
        self.assertEqual(2, len(state_rows(initial + line(finished))))
        for later in [row('armed'), row('sample', elapsed=-1), row('sample', remaining=121),
                      row('finished'), row('sample', 'EXPIRED', elapsed=120001, remaining=1)]:
            with self.subTest(later=later), self.assertRaises(ValueError):
                state_rows(initial + line(later))
        with self.assertRaises(ValueError):
            state_rows(initial + line(finished) + line(row('sample', elapsed=120002, remaining=0)))

    def test_wait_refresh_and_concurrent_start_cannot_open_two_windows(self):
        runs = []
        release = threading.Event()
        def worker(current):
            runs.append(1); release.wait(2)
        with tempfile.TemporaryDirectory() as root:
            session = ScanSession(Path(root) / 'session', worker, page_test=True)
            self.assertEqual('WAITING', session.public()['state'])
            self.assertEqual([], runs)
            self.assertFalse(session.confirm_sent())
            threads = [threading.Thread(target=session.start) for _ in range(5)]
            for thread in threads: thread.start()
            for thread in threads: thread.join()
            release.set(); session.thread.join()
            self.assertEqual([1], runs)
            self.assertFalse(session.start())

    def test_payment_ack_requires_live_state_and_is_feedback_only(self):
        with tempfile.TemporaryDirectory() as root:
            session = ScanSession(Path(root) / 'session', lambda _: None, page_test=True)
            self.assertFalse(session.confirm_sent())
            session.arm_budget(); session.sample(row())
            self.assertTrue(session.confirm_sent())
            self.assertTrue(session.public()['user_confirmed'])
            ack = session.snapshot()['user_payment_confirmation']
            self.assertIn('User feedback only', ack['evidence'])
            session.change(state='FINISHED')
            self.assertFalse(session.confirm_sent())
            self.assertFalse(session.public()['can_send'])
            self.assertEqual('Not assessed', session.snapshot()['real_payment'])

    def test_http_auth_origin_body_and_content_template(self):
        with tempfile.TemporaryDirectory() as root:
            session = ScanSession(Path(root) / 'session', lambda _: None, page_test=True)
            server = make_server(session, 'local-secret', 'scan_sample.html')
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            origin = 'http://127.0.0.1:' + str(server.server_port)
            def request(method, path, headers=None, body=None):
                conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
                conn.request(method, path, body=body, headers=headers or {})
                response = conn.getresponse(); result = response.status, response.read(); conn.close(); return result
            try:
                code, html = request('GET', '/')
                self.assertEqual(200, code)
                self.assertIn('不承诺 New API 自动充值', html.decode())
                self.assertEqual(403, request('GET', '/api/state')[0])
                headers = {'X-Probe-Token': 'local-secret', 'Origin': origin}
                self.assertEqual(403, request('POST', '/api/start', {'X-Probe-Token': 'local-secret'})[0])
                self.assertEqual(400, request('POST', '/api/start', headers, 'unexpected')[0])
                self.assertFalse(session.snapshot()['start_claimed'])
                self.assertEqual(202, request('POST', '/api/start', headers)[0])
                self.assertEqual(409, request('POST', '/api/start', headers)[0])
            finally:
                server.shutdown(); server.server_close(); thread.join()
                if session.thread is not None: session.thread.join()

    def test_cancel_and_timeout_terminate_owned_child(self):
        for mode in ['before_launch', 'after_launch', 'timeout']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as root:
                child = Mock(); child.poll.return_value = None; child.wait.return_value = 0
                cancel = Mock()
                if mode == 'before_launch': cancel.is_set.return_value = True
                elif mode == 'after_launch': cancel.is_set.side_effect = [False, False, True]
                else: cancel.is_set.return_value = False
                driver = Driver(Path('/existing/adb'), 'emulator-5554')
                expected = TimeoutError if mode == 'timeout' else InterruptedError
                with patch('private_content_sample.subprocess.Popen', return_value=child) as spawn, \
                        patch('private_content_sample.time.monotonic', side_effect=[0, 181]):
                    with self.assertRaises(expected):
                        driver.observe_receipt(Path(root) / 'log', lambda _: None, cancel)
                if mode == 'before_launch':
                    spawn.assert_not_called()
                else:
                    child.terminate.assert_called_once()
                    child.wait.assert_called_once()


if __name__ == '__main__':
    unittest.main()
