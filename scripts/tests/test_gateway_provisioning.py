import argparse
import base64
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provision_gateway_avd as provision


def bootstrap():
    return {'scope': {'schema_version': 1, 'grant_id': 'grant_example', 'origin': 'https://gateway.example',
            'environment': 'example', 'receiver_id': 'designated_receiver', 'provider': 'alipay',
            'source_class': 'notification', 'mode': 'replace', 'expected_binding_version': 0,
            'device_id': '', 'expires_at_ms': 2000000000000},
            'token': base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip('=')}


class ProvisioningContractTest(unittest.TestCase):
    def test_only_designated_initial_notification_scope(self):
        value = bootstrap()
        self.assertEqual(provision.load_bootstrap(json.dumps(value).encode()), value)
        for key, changed in [('provider', 'wxpay'), ('source_class', 'synthetic'), ('mode', 'rotate'),
                             ('expected_binding_version', 1), ('expected_binding_version', False),
                             ('device_id', 'other'), ('origin', 'http://gateway.example'), ('origin', 1)]:
            value = bootstrap(); value['scope'][key] = changed
            with self.subTest(key=key), self.assertRaises((ValueError, argparse.ArgumentTypeError)):
                provision.load_bootstrap(json.dumps(value).encode())

    def test_duplicate_and_noncanonical_secret_rejected(self):
        raw = json.dumps(bootstrap()).replace('"schema_version": 1', '"schema_version": 1, "schema_version": 1')
        with self.assertRaises(ValueError):
            provision.load_bootstrap(raw.encode())
        value = bootstrap(); value['token'] = value['token'][:-1] + 'B'
        with self.assertRaises(ValueError):
            provision.load_bootstrap(json.dumps(value).encode())

    def test_secret_uses_only_stdin_and_verify_contains_no_token(self):
        calls = []
        def capture(args, **kwargs):
            calls.append((args, kwargs))
        value = bootstrap()
        for phase in ['pair', 'verify']:
            provision.write_private_input(['adb', '-s', 'emulator-5554'], value, phase, run=capture)
        for args, kwargs in calls:
            self.assertNotIn(value['token'], ' '.join(args))
            self.assertIn('run-as', args)
            self.assertTrue(kwargs['capture_output']); self.assertTrue(kwargs['check'])
        self.assertEqual(json.loads(calls[0][1]['input']), value)
        self.assertEqual(json.loads(calls[1][1]['input']), {'scope': value['scope']})

    def test_verify_accepts_only_a_nonsecret_scope_descriptor(self):
        value = {'scope': bootstrap()['scope']}
        self.assertEqual(provision.load_bootstrap(json.dumps(value).encode(), pairing=False), value)
        with self.assertRaises(ValueError):
            provision.load_bootstrap(json.dumps(bootstrap()).encode(), pairing=False)

    def test_exact_phase_and_success_result_required(self):
        row = provision.expected_result('pair')
        output = provision.PREFIX + json.dumps(row) + '\nOK (1 test)\n'
        self.assertEqual(provision.parse_result(output, 0, 'pair'), row)
        for text, code, phase in [(output, 1, 'pair'), (output, 0, 'verify'),
                                  (output + output, 0, 'pair'), (output + 'FAILURES!!!', 0, 'pair'),
                                  ('OK (1 test)', 0, 'pair')]:
            with self.subTest(code=code, phase=phase), self.assertRaises(ValueError):
                provision.parse_result(text, code, phase)

    def test_forged_boolean_failure_or_sensitive_field_never_passes(self):
        for key, changed in [('passed', 1), ('authorized', False), ('nonexportable_key', False),
                             ('notification_access', True), ('payment_verified', True), ('raw_secret', 'private')]:
            row = dict(provision.expected_result('verify'), **{key: changed})
            output = provision.PREFIX + json.dumps(row) + '\nOK (1 test)\n'
            with self.subTest(key=key), self.assertRaises(ValueError):
                provision.parse_result(output, 0, 'verify')


if __name__ == '__main__':
    unittest.main()
