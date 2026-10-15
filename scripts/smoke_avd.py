#!/usr/bin/env python3
"""Run only the isolated foundation client on an explicitly selected emulator."""
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
    'app/build/outputs/apk/fixture/debug/app-fixture-debug.apk',
    'app/build/outputs/apk/androidTest/fixture/debug/app-fixture-debug-androidTest.apk',
]:
    subprocess.run(adb + ['install', '-r', str(root / relative)], check=True)
result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r',
    'dev.epay.observer.fixtureclient.debug.test/androidx.test.runner.AndroidJUnitRunner'],
    capture_output=True, text=True, check=True)
args.report.parent.mkdir(parents=True, exist_ok=True)
args.report.write_text(result.stdout + result.stderr)
# Android's shell command can exit zero even if JUnit fails.
if 'OK (3 tests)' not in result.stdout or 'FAILURES!!!' in result.stdout or 'INSTRUMENTATION_FAILED' in result.stdout:
    raise SystemExit(f'Instrumentation did not pass; inspect {args.report}')
print(f'AVD foundation: 3/3 tests passed; report {args.report}')
