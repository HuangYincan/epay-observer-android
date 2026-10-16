#!/usr/bin/env python3
"""Run only the isolated client and synthetic sender on an explicitly selected emulator."""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('serial', help='Explicit emulator-NNNN target; never a physical device')
parser.add_argument('--report', type=Path, required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-') or not args.serial[9:].isdigit():
    parser.error('Only an emulator-NNNN target is allowed')
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
root = Path(__file__).resolve().parents[1]
for relative in [
    'fixture/build/outputs/apk/debug/fixture-debug.apk',
    'app/build/outputs/apk/fixture/debug/app-fixture-debug.apk',
    'app/build/outputs/apk/androidTest/fixture/debug/app-fixture-debug-androidTest.apk',
]:
    subprocess.run(adb + ['install', '-r', str(root / relative)], check=True, timeout=180)
args.report.parent.mkdir(parents=True, exist_ok=True)
with args.report.open('w') as report:
    try:
        subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r',
            'dev.epay.observer.fixtureclient.debug.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=report, stderr=subprocess.STDOUT, text=True, check=True, timeout=240)
    except subprocess.TimeoutExpired:
        raise SystemExit(f'Instrumentation timed out; partial report: {args.report}')
output = args.report.read_text()
# Android's shell command can exit zero even if JUnit fails.
if 'OK (6 tests)' not in output or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output:
    raise SystemExit(f'Instrumentation did not pass; inspect {args.report}')
print(f'AVD capture + foundation: 6/6 tests passed; report {args.report}')
