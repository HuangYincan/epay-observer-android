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
runner = 'dev.epay.observer.fixtureclient.debug.test/androidx.test.runner.AndroidJUnitRunner'
process_class = 'dev.epay.observer.IdentityProcessTest'

def instrument(report_path, expected_count, extra):
    with report_path.open('w') as report:
        try:
            subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r'] + extra + [runner],
                stdout=report, stderr=subprocess.STDOUT, text=True, check=True, timeout=240)
        except subprocess.TimeoutExpired:
            raise SystemExit(f'Instrumentation timed out; partial report: {report_path}')
    output = report_path.read_text()
    if f'OK ({expected_count} tests)' not in output and not (expected_count == 1 and 'OK (1 test)' in output):
        raise SystemExit(f'Instrumentation did not pass; inspect {report_path}')
    if any(marker in output for marker in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=']):
        raise SystemExit(f'Instrumentation failure; inspect {report_path}')

instrument(args.report, 20, ['-e', 'notClass', process_class])
instrument(args.report.with_name(args.report.stem + '-process-prepare.txt'), 1,
    ['-e', 'class', process_class, '-e', 'identityPhase', 'prepare'])
subprocess.run(adb + ['shell', 'am', 'force-stop', 'dev.epay.observer.fixtureclient.debug'], check=True, timeout=15)
instrument(args.report.with_name(args.report.stem + '-process-verify.txt'), 1,
    ['-e', 'class', process_class, '-e', 'identityPhase', 'verify'])
print(f'AVD: 20/20 suite tests plus 2/2 cross-process phases passed; report {args.report}')
