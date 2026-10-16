import contextlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import manual_pairing_grant as grant_tool

SERVER_SECONDS = 1792191000
SERVER_DATE = 'Fri, 16 Oct 2026 22:50:00 GMT'


class Response(io.BytesIO):
    def __init__(self, body, date):
        super().__init__(json.dumps(body).encode())
        self.headers = {} if date is None else {'Date': date}


class FakeGateway:
    def __init__(self, environment, date):
        self.environment, self.date = environment, date
        self.paths = []

    def open(self, request, timeout):
        path = request.full_url.removeprefix('https://localhost:18443')
        self.paths.append(path)
        if path == '/api/admin/auth/login':
            return Response({'code': 0, 'data': {'token': 'synthetic-admin-token'}}, self.date)
        if path == '/api/admin/observer/grants':
            return Response({'schema_version': 1, 'origin': 'https://localhost:18443',
                'environment': self.environment, 'source_class': 'synthetic', 'provider': 'wxpay',
                'receiver_id': 'lab-receiver-wx', 'mode': 'rotate', 'expires_at_ms': (SERVER_SECONDS + 300) * 1000,
                'token': 'synthetic-grant-value'}, self.date)
        raise AssertionError('Unexpected request path')


class ManualPairingClockTest(unittest.TestCase):
    def run_tool(self, skew_seconds, date=SERVER_DATE, serial=None, adb_error=None):
        with tempfile.TemporaryDirectory(prefix='epay-observer-clock-test-', dir='/tmp') as temporary:
            lab = Path(temporary).resolve()
            prefix = 'epay-observer-clock-test'
            (lab / 'runtime.json').write_text(json.dumps({'prefix': prefix, 'environment': prefix}))
            (lab / 'private-accounts.json').write_text(json.dumps({'gateway_admin': {'username': 'synthetic', 'password': 'test-only'}}))
            sdk = lab / 'sdk'
            (sdk / 'platform-tools').mkdir(parents=True)
            (sdk / 'platform-tools/adb').touch()
            gateway = FakeGateway(prefix, date)
            argv = ['manual_pairing_grant.py', str(lab), 'wxpay']
            if serial is not None:
                argv += ['--serial', serial]
            output = io.StringIO()
            error = None
            with patch.object(sys, 'argv', argv), patch.dict(os.environ, {'ANDROID_HOME': str(sdk)}), \
                patch.object(grant_tool, 'require_container'), \
                patch.object(grant_tool.ssl, 'create_default_context'), \
                patch.object(grant_tool.urllib.request, 'build_opener', return_value=gateway), \
                patch('subprocess.check_output', side_effect=adb_error, return_value=str(SERVER_SECONDS + skew_seconds) + '\n'), \
                patch('time.monotonic', return_value=100.0), contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
                try:
                    grant_tool.main()
                except (RuntimeError, SystemExit) as failure:
                    error = failure
            file = lab / 'manual-pairing-wxpay.json'
            saved = json.loads(file.read_text()) if file.exists() else None
            mode = stat.S_IMODE(file.stat().st_mode) if file.exists() else None
            return error, gateway.paths, saved, mode, output.getvalue()

    def test_drift_rejects_before_creating_any_grant(self):
        for skew in [-433, -4, 4, 433]:
            with self.subTest(skew=skew):
                error, paths, saved, _, output = self.run_tool(skew)
                self.assertIsInstance(error, RuntimeError)
                self.assertEqual(['/api/admin/auth/login'], paths)
                self.assertIsNone(saved)
                self.assertNotIn('synthetic-admin-token', output)

    def test_missing_or_invalid_server_date_fails_closed(self):
        for date in [None, 'invalid']:
            with self.subTest(date=date):
                error, paths, saved, _, _ = self.run_tool(0, date)
                self.assertIsInstance(error, RuntimeError)
                self.assertNotIn('/api/admin/observer/grants', paths)
                self.assertIsNone(saved)

    def test_aligned_clock_preserves_private_grant_contract(self):
        for skew in [-3, -2, 0, 2, 3]:
            with self.subTest(skew=skew):
                error, paths, saved, mode, output = self.run_tool(skew)
                self.assertIsNone(error)
                self.assertEqual(2, len(paths))
                self.assertEqual('rotate', saved['mode'])
                self.assertEqual(0o600, mode)
                self.assertNotIn('synthetic-grant-value', output)

    def test_unavailable_adb_does_not_create_grant(self):
        error, paths, saved, _, _ = self.run_tool(0, adb_error=subprocess.TimeoutExpired('adb', 5))
        self.assertIsInstance(error, RuntimeError)
        self.assertNotIn('/api/admin/observer/grants', paths)
        self.assertIsNone(saved)

    def test_physical_device_is_rejected(self):
        error, paths, saved, _, _ = self.run_tool(0, serial='physical-device')
        self.assertIsNotNone(error)
        self.assertEqual([], paths)
        self.assertIsNone(saved)


if __name__ == '__main__':
    unittest.main()
