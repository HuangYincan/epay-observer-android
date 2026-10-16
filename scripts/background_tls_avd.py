#!/usr/bin/env python3
"""Explicit emulator-only process/reboot/force-stop/Doze integration faults.

Requires the observeravd bridge in background mode and localhost CA fixture APKs.
Gateway progress is checked before instrumentation can restart the client.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('serial')
parser.add_argument('directory', type=Path)
args = parser.parse_args()
if not args.serial.startswith('emulator-') or not args.serial[9:].isdigit():
    parser.error('emulator-NNNN required')
adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', args.serial]
client = 'dev.epay.observer.fixtureclient.debug'
runner = client + '.test/androidx.test.runner.AndroidJUnitRunner'

def run(*parts, timeout=30):
    return subprocess.check_output(adb + list(parts), timeout=timeout, stderr=subprocess.STDOUT).decode().strip()

def shell(command):
    return run('shell', command)

def wait_for(description, predicate, timeout=120):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(0.5)
    raise AssertionError(description)

def progress():
    return json.loads((args.directory / 'background-progress.json').read_text())

def phase(name, during=None):
    report = args.directory / ('background-' + name + '.txt')
    with report.open('w') as output:
        process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                             'dev.epay.observer.BackgroundNetworkTest', '-e', 'backgroundPhase', name, runner],
                       stdout=output, stderr=subprocess.STDOUT)
        try:
            if during is not None:
                during()
            assert process.wait(timeout=150) == 0
        finally:
            if process.poll() is None:
                process.kill(); process.wait(timeout=15)
    text = report.read_text()
    assert 'OK (1 test)' in text and all(bad not in text for bad in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=')), report

def online():
    shell('svc wifi enable'); shell('svc data enable')

wifi = shell('settings get global wifi_on')
mobile = shell('settings get global mobile_data')
evidence = []
try:
    for apk in ('fixture/build/outputs/apk/debug/fixture-debug.apk',
                'app/build/outputs/apk/fixture/debug/app-fixture-debug.apk',
                'app/build/outputs/apk/androidTest/fixture/debug/app-fixture-debug-androidTest.apk'):
        run('install', '-r', apk, timeout=180)
    shell('am force-stop ' + client)
    assert shell('pm clear ' + client) == 'Success'
    run('reverse', 'tcp:18443', 'tcp:18443')
    bootstrap = (args.directory / 'avd-grants.json').read_bytes()
    assert len(bootstrap) < 4096
    subprocess.run(adb + ['shell', '-T', 'run-as', client, 'sh', '-c',
                         shlex.quote('umask 077; mkdir -p no_backup; cat > no_backup/pairing-network-bootstrap.json')],
                   input=bootstrap, check=True, timeout=15)
    online()
    for index, stage in enumerate(('process', 'reboot', 'force', 'doze', 'orphan')):
        if stage == 'orphan':
            def restore_for_periodic_scan():
                wait_for('periodic-only queue not ready',
                         lambda: shell('run-as ' + client + ' sh -c "test -f files/avd-orphan-ready && echo ready || true"') == 'ready', 30)
                assert progress()['events'] == 8
                online()
                wait_for('periodic scanner did not recover without an app restart',
                         lambda: progress()['events'] == 10 and progress()['requests'] == 11)
            # Keep this instrumentation alive to prevent its teardown from
            # killing the app and masking recovery with Application.onCreate.
            phase('prepare-orphan', during=restore_for_periodic_scan)
            result = json.loads(run('exec-out', 'run-as', client, 'cat', 'files/avd-background-result.json'))
            assert result['acked_events'] == 10 and result['payment_verified'] is False
            result['test_prepared_periodic_only_recovery'] = True
            result['same_test_process'] = True
            evidence.append(result)
            print('orphan: periodic-only recovery verified', flush=True)
            continue
        phase('prepare-' + stage)
        before = index * 2
        assert progress()['events'] == before
        if stage == 'process':
            # Kill only this debug app UID's process; am kill can ignore cached
            # activities with framework importance, while force-stop changes
            # package scheduling semantics and belongs to its separate stage.
            shell('am start -W -n ' + client + '/dev.epay.observer.MainActivity')
            shell('input keyevent KEYCODE_HOME')
            pid = shell('pidof ' + client + ' || true')
            assert pid.isdigit(), 'exactly one client process required before process fault'
            shell('run-as ' + client + ' kill -9 ' + pid)
            wait_for('old client process survived', lambda: pid not in shell('pidof ' + client + ' || true').split(), 15)
            online()
        elif stage == 'reboot':
            run('reboot')
            run('wait-for-device', timeout=180)
            wait_for('boot did not finish', lambda: shell('getprop sys.boot_completed') == '1', 180)
            run('reverse', 'tcp:18443', 'tcp:18443')
            online()
        elif stage == 'force':
            shell('am force-stop ' + client)
            online()
            time.sleep(3)
            assert progress()['events'] == before, 'force-stopped app unexpectedly delivered'
            shell('am start -W -n ' + client + '/dev.epay.observer.MainActivity')
        elif stage == 'doze':
            shell('input keyevent KEYCODE_SLEEP')
            shell('dumpsys deviceidle force-idle')
            assert shell('dumpsys deviceidle get deep') == 'IDLE', 'deep idle state was not entered'
            online()
            time.sleep(3)
            assert progress()['events'] == before, 'deep idle did not defer this emulator job'
            shell('dumpsys deviceidle unforce')
            shell('input keyevent KEYCODE_WAKEUP')
        target = before + 2
        wait_for(stage + ' did not recover without instrumentation',
                 lambda: progress()['events'] == target and progress()['requests'] == target + 1)
        # Now it is safe to start instrumentation; automatic delivery is already proven.
        phase('verify-' + stage)
        result = json.loads(run('exec-out', 'run-as', client, 'cat', 'files/avd-background-result.json'))
        assert result['acked_events'] == target and result['payment_verified'] is False
        result['gateway_received_before_verify_process'] = True
        if stage in ('force', 'doze'):
            result['observed_deferred_seconds'] = 3
        evidence.append(result)
        print(stage + ': durable background recovery verified', flush=True)
    (args.directory / 'background-android-result.json').write_text(json.dumps(evidence, indent=2) + '\n')
    (args.directory / 'device-background').write_text('five synthetic recovery stages verified\n')
finally:
    # Restore only settings changed on this explicitly selected test emulator.
    for command in ('dumpsys deviceidle unforce', 'input keyevent KEYCODE_WAKEUP',
                    'svc wifi ' + ('enable' if wifi == '1' else 'disable'),
                    'svc data ' + ('enable' if mobile == '1' else 'disable')):
        subprocess.run(adb + ['shell', command], check=False, timeout=15)
    subprocess.run(adb + ['reverse', '--remove', 'tcp:18443'], check=False, timeout=15)
    subprocess.run(adb + ['shell', 'run-as', client, 'rm', '-f', 'no_backup/pairing-network-bootstrap.json'], check=False, timeout=15)
