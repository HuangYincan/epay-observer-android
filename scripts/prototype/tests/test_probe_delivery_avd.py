"""Control-page and cleanup contracts. All source observations here are host-only simulations."""
import contextlib
import http.client
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('probe_delivery_avd', SCRIPTS / 'probe_delivery_avd.py')
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


def row(event='armed', callbacks=0, elapsed=0, remaining=120):
    return {'event': event, 'phase': 'EXPIRED' if event == 'finished' else 'RUNNING',
            'callbacks': callbacks, 'remaining_seconds': remaining, 'elapsed_ms': elapsed,
            'connected': True, 'source_matches': True, 'screen_interactive': True,
            'keyguard_locked': False, 'instrumented_process': True}


class SessionTest(unittest.TestCase):
    def testPartialLinesAndUnapprovedFieldsDoNotLeak(self):
        value = dict(row(), notification_text='must never be exported')
        text = driver.PREFIX + json.dumps(value)
        self.assertEqual([], driver.rows_from_log(text))
        self.assertNotIn('notification_text', driver.rows_from_log(text + '\n')[0])

    def testInvalidObservationsAreRejected(self):
        invalid = [dict(row(), callbacks=True), dict(row(), callbacks=501),
                   dict(row(), source_matches=False), dict(row(), keyguard_locked=True),
                   dict(row(), remaining_seconds=121), ['not a state']]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(ValueError):
                driver.rows_from_log(driver.PREFIX + json.dumps(value) + '\n')
        for end in [row(event='finished', elapsed=100, remaining=0), row(event='armed')]:
            with self.subTest(end=end), self.assertRaises(ValueError):
                driver.rows_from_log(''.join(driver.PREFIX + json.dumps(x) + '\n' for x in [row(), end]))

    def testStaleAndExpiredStatesCannotPromptSend(self):
        with tempfile.TemporaryDirectory() as root:
            s = driver.DeliverySession(Path(root) / 'run', lambda _: None, page_test=True)
            s.arm_budget()
            s.sample(row())
            self.assertTrue(s.public()['can_send'])
            with patch.object(driver.time, 'monotonic', return_value=s.last_sample_at + 13):
                self.assertFalse(s.public()['can_send'])
                self.assertEqual(0, s.public()['remaining_seconds'])
            s.sample(row('finished', elapsed=120001, remaining=0))
            self.assertFalse(s.public()['can_send'])

    def testSendAcknowledgementIsStableAndNotASendTimestamp(self):
        with tempfile.TemporaryDirectory() as root:
            s = driver.DeliverySession(Path(root) / 'run', lambda _: None)
            self.assertFalse(s.confirm_sent())
            s.change(start_claimed=True)
            s.arm_budget()
            s.sample(row())
            self.assertTrue(s.confirm_sent())
            first = s.snapshot()['user_send_confirmation']
            s.change(state='FINISHED')
            s.confirm_sent()
            self.assertEqual(first, s.snapshot()['user_send_confirmation'])
            self.assertTrue(first['window_live_at_ack'])
            self.assertIn('not independently measured', first['send_timestamp'])

    def testBufferedArmedRowCannotReopenExpiredHostSendBudget(self):
        with tempfile.TemporaryDirectory() as root:
            s = driver.DeliverySession(Path(root) / 'run', lambda _: None)
            with patch.object(driver.time, 'monotonic', return_value=0):
                s.arm_budget()
            with patch.object(driver.time, 'monotonic', return_value=121):
                s.sample(row())  # Freshly received, but possibly buffered in transit.
                self.assertFalse(s.public()['can_send'])
                self.assertEqual(0, s.public()['remaining_seconds'])
                with self.assertRaises(ValueError):s.arm_budget()

    def testExistingEvidenceCannotBeOverwritten(self):
        with tempfile.TemporaryDirectory() as root:
            p = Path(root) / 'run'; p.mkdir(); (p / 'report.json').write_text('preserve')
            with self.assertRaises(FileExistsError):
                driver.DeliverySession(p, lambda _: None)
            self.assertEqual('preserve', (p / 'report.json').read_text())

    def testEvidenceWriteFailurePreventsStart(self):
        with tempfile.TemporaryDirectory() as root:
            calls = []
            s = driver.DeliverySession(Path(root) / 'run', lambda _: calls.append('unexpected'))
            with patch.object(driver.Path, 'write_text', side_effect=OSError('disk unavailable')):
                self.assertFalse(s.start())
            self.assertTrue(s.evidence_failed)
            self.assertEqual('FAILED', s.public()['state'])
            self.assertEqual([], calls)

    def testOnlyOneLocalControllerCanOwnTheSameEmulator(self):
        with tempfile.TemporaryDirectory() as root:
            first = driver.acquire_control_lock('emulator-5554', root)
            try:
                with self.assertRaises(OSError):driver.acquire_control_lock('emulator-5554', root)
            finally:first.close()
            second = driver.acquire_control_lock('emulator-5554', root);second.close()


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.release = threading.Event()
        self.calls = 0
        def run(session):
            self.calls += 1
            self.release.wait(5)
            session.change(state='CANCELLED')
        self.session = driver.DeliverySession(Path(self.tmp.name) / 'run', run, page_test=True)
        self.token = 'test-control-token'
        self.server = driver.make_server(self.session, self.token)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.origin = 'http://127.0.0.1:' + str(self.server.server_port)

    def tearDown(self):
        self.release.set()
        self.session.stop()
        if self.session.thread:
            self.session.thread.join(5)
        self.server.shutdown(); self.server.server_close(); self.thread.join(5)
        self.tmp.cleanup()

    def request(self, path='/api/state', method='GET', headers=None, body=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        values = {'X-Probe-Token': self.token, 'Origin': self.origin}
        values.update(headers or {})
        connection.request(method, path, body=body, headers=values)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def testPageAndPollingNeverStartObservation(self):
        for path in ['/', '/', '/api/state', '/api/state']:
            code, headers, _ = self.request(path)
            self.assertEqual(200, code)
            self.assertEqual('no-store', headers['Cache-Control'])
            self.assertNotIn('Access-Control-Allow-Origin', headers)
        self.assertEqual(0, self.calls)
        self.assertFalse(self.session.snapshot()['start_claimed'])

    def testHostOriginTokenAndBodyGuardsPrecedeAnyStart(self):
        for headers, body, expected in [({'Host': 'evil.example'}, None, 403),
                ({'Origin': 'https://evil.example'}, None, 403),
                ({'X-Probe-Token': 'wrong'}, None, 403),
                ({'X-Probe-Token': '\u00ff'}, None, 403),
                ({}, '{}', 400), ({'Transfer-Encoding': 'chunked'}, None, 400)]:
            with self.subTest(headers=headers):
                self.assertEqual(expected, self.request('/api/start', 'POST', headers, body)[0])
        self.assertEqual(0, self.calls)

    def testConcurrentStartsHaveExactlyOneWinnerAndNoRearm(self):
        results = []
        threads = [threading.Thread(target=lambda: results.append(self.request('/api/start', 'POST')[0])) for _ in range(6)]
        for thread in threads: thread.start()
        for thread in threads: thread.join(5)
        self.assertEqual([202] + [409] * 5, sorted(results))
        self.assertEqual(1, self.calls)
        self.release.set(); self.session.thread.join(5)
        self.assertEqual(409, self.request('/api/start', 'POST')[0])
        self.assertEqual(1, self.calls)


class FakeAvd(driver.AvdDelivery):
    def __init__(self, initial=None, audit_bad=False, final_bad=False):
        super().__init__('emulator-5554', '/tmp/epay-observer-unit', '/existing/sdk')
        self.current = {'screen_awake': False, 'lockscreen_disabled': 'true', 'wifi': '1', 'mobile': '1',
            'probe_access': True, 'probe_stopped': True, 'probe_process_present': False,
            'source_stopped': False, 'source_process_present': True, 'battery_override': False, 'forced_doze': False,
            'source_notifications_granted': True, 'source_notifications_allowed': True}
        self.current.update(initial or {})
        self.commands = []; self.audits = 0; self.audit_bad = audit_bad; self.final_bad = final_bad

    def command(self, *args, cleanup=False):
        if args == ('get-state',):return 'device'
        self.commands.append(args[-1])
        c = args[-1]
        if c == 'getprop ro.kernel.qemu':return '1'
        if c == 'am get-current-user':return '0'
        if c == 'input keyevent KEYCODE_WAKEUP':self.current['screen_awake'] = True
        elif c == 'input keyevent KEYCODE_SLEEP':self.current['screen_awake'] = False
        elif c == 'am force-stop ' + driver.PROBE:self.current['probe_stopped'] = True
        return ''

    def state(self, cleanup=False):
        if cleanup and self.final_bad:raise ValueError('metadata unavailable')
        return dict(self.current)

    def audit(self, cleanup=False):
        self.audits += 1
        if self.audit_bad:raise ValueError('wrong stable baseline')
        return dict(driver.EXPECTED_AUDIT)


class Child:
    def __init__(self, args, stdout, stderr, alive=False):
        self.returncode = None if alive else 0
        self.terminated = False
        if not alive:
            for r in [row(), row('finished', elapsed=120002, remaining=0)]:
                stdout.write(driver.PREFIX + json.dumps(r) + '\n')
            stdout.write('OK (1 test)\n'); stdout.flush()
    def poll(self):return self.returncode
    def terminate(self):self.terminated = True; self.returncode = -15
    def kill(self):self.returncode = -9
    def wait(self, timeout):return self.returncode


class CleanupTest(unittest.TestCase):
    def invoke(self, avd, child_factory=Child):
        with tempfile.TemporaryDirectory() as root:
            session = driver.DeliverySession(Path(root) / 'run', avd, page_test=True)
            with patch.object(driver.subprocess, 'Popen', side_effect=child_factory):
                avd(session)
            return session.snapshot(), avd.commands

    def testSuccessfulWindowRestoresScreenAndResetsProbeWithAudit(self):
        avd = FakeAvd()
        report, commands = self.invoke(avd)
        self.assertEqual('FINISHED', report['state'])
        self.assertTrue(report['completed'] and report['cleanup_ok'])
        self.assertEqual('Not exercised', report['notification_delivery'])
        self.assertEqual(2, avd.audits)
        self.assertFalse(avd.current['screen_awake'])
        self.assertIn('am force-stop ' + driver.PROBE, commands)

    def testListenerRecreatedProcessDoesNotBlockExplicitNewWindow(self):
        class RebindingAvd(FakeAvd):
            def __init__(self):
                super().__init__(initial={'probe_stopped': False, 'probe_process_present': True})
                self.pid = 100
            def command(self, *args, cleanup=False):
                c = args[-1]
                if c == 'pidof ' + driver.PROBE + ' || true':return str(self.pid)
                if c == 'am force-stop ' + driver.PROBE:
                    self.commands.append(c);self.pid += 1
                    return ''  # Old process exits; system reconnect creates a fresh one.
                return super().command(*args, cleanup=cleanup)
        avd = RebindingAvd()
        report, _ = self.invoke(avd)
        self.assertEqual('FINISHED', report['state'])
        self.assertTrue(report['completed'] and report['cleanup_ok'])
        self.assertTrue(avd.current['probe_process_present'])

    def testUnsupportedSystemOrWrongBaselineDoesNotStart(self):
        for avd in [FakeAvd(initial={'forced_doze': True}), FakeAvd(audit_bad=True),
                    FakeAvd(initial={'source_notifications_granted': False}),
                    FakeAvd(initial={'source_notifications_allowed': False})]:
            with self.subTest(avd=avd):
                report, commands = self.invoke(avd, lambda *a, **k: self.fail('No instrumentation allowed'))
                self.assertEqual('FAILED', report['state'])
                self.assertNotIn('am force-stop ' + driver.PROBE, commands)
                self.assertNotIn('input keyevent KEYCODE_WAKEUP', commands)

    def testCancellationStopsChildRestoresAndAudits(self):
        avd = FakeAvd(); children = []
        def launch(*args, **kwargs):
            child = Child(*args, **kwargs, alive=True); children.append(child)
            avd.session.cancelled.set()
            return child
        report, _ = self.invoke(avd, launch)
        self.assertEqual('CANCELLED', report['state'])
        self.assertFalse(report['completed'])
        self.assertTrue(report['cleanup_ok'] and children[0].terminated)
        self.assertEqual(2, avd.audits)

    def testTimeoutStopsChildAndRestores(self):
        avd = FakeAvd(); children = []; clock = [0]
        def launch(*args, **kwargs):
            child = Child(*args, **kwargs, alive=True); children.append(child); clock[0] = 300
            return child
        with patch.object(driver.time, 'monotonic', side_effect=lambda: clock[0]):
            report, _ = self.invoke(avd, launch)
        self.assertEqual('FAILED', report['state'])
        self.assertEqual('TimeoutError', report['failure_type'])
        self.assertTrue(report['cleanup_ok'] and children[0].terminated)

    def testFinalMetadataFailureStillRunsAuditAndCannotPass(self):
        avd = FakeAvd(final_bad=True)
        report, _ = self.invoke(avd)
        self.assertEqual('FAILED', report['state'])
        self.assertFalse(report['completed'] or report['cleanup_ok'])
        self.assertTrue(report['cleanup']['audit_expected_unchanged'])
        self.assertEqual(2, avd.audits)

    def testCleanupEvidenceFailureDoesNotPreventDeviceRestoration(self):
        avd = FakeAvd()
        with tempfile.TemporaryDirectory() as root:
            s = driver.DeliverySession(Path(root) / 'run', avd, page_test=True)
            original = s.change
            def change(**fields):
                if fields.get('state') == 'CLEANING':
                    s.evidence_failed = True
                original(**fields)
            with patch.object(s, 'change', side_effect=change), patch.object(driver.subprocess, 'Popen', side_effect=Child):
                avd(s)
            self.assertEqual('FAILED', s.public()['state'])
            self.assertFalse(s.snapshot()['completed'])
            self.assertIn('am force-stop ' + driver.PROBE, avd.commands)
            self.assertFalse(avd.current['screen_awake'])
            self.assertEqual(2, avd.audits)

    def testFinalReportWriteFailureCannotAdvertiseCompletion(self):
        avd = FakeAvd()
        with tempfile.TemporaryDirectory() as root:
            s = driver.DeliverySession(Path(root) / 'run', avd, page_test=True)
            original = driver.Path.write_text
            def write(path, data, *args, **kwargs):
                if '"state": "FINISHED"' in data:raise OSError('final write failed')
                return original(path, data, *args, **kwargs)
            with patch.object(driver.Path, 'write_text', new=write), patch.object(driver.subprocess, 'Popen', side_effect=Child):
                avd(s)
            self.assertEqual('FAILED', s.public()['state'])
            self.assertFalse(s.public()['completed'])
            self.assertTrue(s.snapshot()['cleanup_ok'])

    def testPhysicalTargetRejectedBeforeAnyBackend(self):
        with patch('sys.argv', ['driver', 'physical', '--lab', '/tmp/epay-observer-unit',
                               '--directory', '/tmp/observer-probe-local-unused']), \
                patch.object(driver, 'DeliverySession') as session, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):driver.main()
            session.assert_not_called()


if __name__ == '__main__':
    unittest.main()
