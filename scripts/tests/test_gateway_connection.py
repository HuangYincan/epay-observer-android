import argparse
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('connection', Path(__file__).resolve().parents[1] / 'check_gateway_connection_avd.py')
connection = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(connection)


class ResultContractTest(unittest.TestCase):
    def log(self, row=None):
        return connection.PREFIX + json.dumps(connection.EXPECTED if row is None else row) + '\nOK (1 test)\n'

    def test_only_exact_anonymous_authentication_result_passes(self):
        self.assertEqual(connection.parse_result(self.log(), 0), connection.EXPECTED)

    def test_missing_duplicate_and_failed_instrumentation_rejected(self):
        for output, code in [('OK (1 test)', 0), (self.log() + self.log(), 0),
                             (self.log() + 'FAILURES!!!', 0), (self.log(), 1),
                             (self.log().replace('OK (1 test)', 'OK (2 tests)'), 0)]:
            with self.subTest(output=output, code=code), self.assertRaises(ValueError):
                connection.parse_result(output, code)

    def test_proxy_error_and_forged_boolean_do_not_pass(self):
        for key, value in [('http_status', 403), ('json', False), ('code', 'scope_denied'),
                           ('notification_access', True), ('payment_verified', True),
                           ('anonymous', False), ('passed', 1), ('schema_version', True)]:
            row = dict(connection.EXPECTED, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                connection.parse_result(self.log(row), 0)

    def test_canonical_dns_origin_and_unsafe_arguments(self):
        self.assertEqual(connection.canonical_origin('https://gateway.example:8443'), 'https://gateway.example:8443')
        for value in ['http://gateway.example', 'https://GATEWAY.example', 'https://gateway.example/',
                      'https://a@b.example', 'https://b.example?x=1', 'https://b.example#fragment',
                      'https://b.example:0', 'https://b.example:65536', 'https://b.example:0443',
                      'https://b.example;id', 'https://b.example\n', 'https://b.example:']:
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                connection.canonical_origin(value)

    def test_failed_transport_is_diagnostic_but_never_success(self):
        row = dict(connection.EXPECTED, passed=False, http_status=0, json=False, code='dns_failure')
        self.assertEqual(connection.diagnostic_result(self.log(row)), row)
        with self.assertRaises(ValueError):
            connection.parse_result(self.log(row), 0)

    def test_diagnostic_never_forwards_unknown_fields_or_raw_values(self):
        for row in [dict(connection.EXPECTED, secret='private'),
                    dict(connection.EXPECTED, code='untrusted response body'),
                    dict(connection.EXPECTED, http_status=900)]:
            with self.subTest(row=row), self.assertRaises(ValueError):
                connection.diagnostic_result(self.log(row))


if __name__ == '__main__':
    unittest.main()
