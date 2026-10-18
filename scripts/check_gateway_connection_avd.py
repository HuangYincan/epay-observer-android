#!/usr/bin/env python3
"""SPEC-029: anonymous HTTPS entrance check using an existing productionDebug AVD install."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit

PACKAGE = 'dev.epay.observer.debug'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
TEST = 'dev.epay.observer.GatewayConnectionNetworkTest'
PREFIX = 'INSTRUMENTATION_STATUS: gateway_connection_result='
EXPECTED = {'schema_version': 1, 'passed': True, 'http_status': 401, 'json': True,
            'code': 'invalid_grant', 'anonymous': True, 'notification_access': False,
            'payment_verified': False}


def diagnostic_result(output):
    rows = [json.loads(line[len(PREFIX):]) for line in output.splitlines() if line.startswith(PREFIX)]
    if len(rows) != 1 or not isinstance(rows[0], dict) or set(rows[0]) != set(EXPECTED):
        raise ValueError('Missing or duplicate connection result')
    row = rows[0]
    if any(type(row[key]) is not type(value) for key, value in EXPECTED.items()):
        raise ValueError('Invalid result types')
    if row['code'] not in {'not_attempted', 'invalid_grant', 'unexpected_response', 'tls_failure',
                          'timeout', 'dns_failure', 'connection_failure', 'precondition_failure',
                          'transport_failure'} or not 0 <= row['http_status'] <= 599:
        raise ValueError('Invalid redacted diagnostic')
    return row


def parse_result(output, returncode):
    row = diagnostic_result(output)
    if returncode != 0 or output.count('OK (1 test)') != 1 or any(
            marker in output for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=')):
        raise ValueError('Instrumentation failed')
    if row != EXPECTED:
        raise ValueError('Unsuccessful connection result')
    return row


def canonical_origin(value):
    try:
        uri = urlsplit(value)
        port = uri.port
        expected = 'https://' + (uri.hostname or '') + ('' if port is None else ':' + str(port))
        if (value != expected or uri.scheme != 'https' or not uri.hostname or uri.username is not None
                or uri.password is not None or uri.path or uri.query or uri.fragment
                or not re.fullmatch(r'[a-z0-9.-]+', uri.hostname)
                or port is not None and not 1 <= port <= 65535):
            raise ValueError()
    except ValueError:
        raise argparse.ArgumentTypeError('Canonical HTTPS DNS origin without path or credentials required') from None
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('--origin', required=True, type=canonical_origin)
    parser.add_argument('--directory', required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('Explicit existing emulator-NNNN required')
    directory = args.directory.resolve()
    if not str(directory).startswith(('/tmp/observer-connection-', '/private/tmp/observer-connection-')):
        parser.error('New private /tmp/observer-connection-* directory required')
    if directory.exists():
        parser.error('Previous evidence must not be overwritten')
    os.umask(0o077)
    directory.mkdir(mode=0o700)
    sdk = Path(os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk')
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
    report = {'schema_version': 1, 'utc_started': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'passed': False, 'scope': 'Anonymous Android HTTPS authentication entrance only',
              'payment_verified': False, 'settings_changed': False}
    try:
        def shell(*parts):
            return subprocess.check_output(adb + ['shell', *parts], text=True, stderr=subprocess.PIPE, timeout=15).strip()
        if shell('getprop', 'ro.kernel.qemu') != '1' or shell('am', 'get-current-user') != '0':
            raise ValueError('Existing AVD user 0 required')
        # This driver never installs APKs, grants access or changes system settings.
        with (directory / 'instrumentation.txt').open('x') as log:
            result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r',
                '-e', 'observerConnectionProbe', 'true', '-e', 'observerOrigin', args.origin,
                '-e', 'class', TEST, RUNNER], stdout=log, stderr=subprocess.STDOUT, timeout=75)
        output = (directory / 'instrumentation.txt').read_text()
        report['result'] = diagnostic_result(output)
        parse_result(output, result.returncode)
        report['passed'] = True
    except (OSError, ValueError, subprocess.SubprocessError):
        report['failure'] = 'Precondition, transport or instrumentation failed; inspect private log'
    finally:
        report['utc_finished'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (directory / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
