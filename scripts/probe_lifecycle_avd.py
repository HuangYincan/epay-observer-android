#!/usr/bin/env python3
"""Run SPEC-028 lifecycle experiments on an explicit existing AVD, without message/payment triggers."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import time

PROBE = 'dev.epay.observer.probe.debug'
SOURCE = 'com.eg.android.AlipayGphone'
COMPONENT = PROBE + '/dev.epay.observer.probe.AlipayListener'
ACTIVITY = PROBE + '/dev.epay.observer.probe.ProbeActivity'
SOURCE_ACTIVITY = SOURCE + '/com.eg.android.AlipayGphone.AlipayLogin'
RUNNER = PROBE + '.test/androidx.test.runner.AndroidJUnitRunner'
RESULT_PREFIX = 'INSTRUMENTATION_STATUS: probe_automation_result='


def results_from_log(text):
    return [json.loads(line[len(RESULT_PREFIX):]) for line in text.splitlines() if line.startswith(RESULT_PREFIX)]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('serial')
    p.add_argument('--lab', type=Path, required=True, help='Existing isolated lab; read-only audit before/after')
    p.add_argument('--directory', type=Path, required=True, help='New /tmp/observer-probe-auto-* evidence directory')
    args = p.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        p.error('Explicit emulator-NNNN required; physical devices are not supported')
    directory = args.directory.resolve()
    if not str(directory).startswith(('/tmp/observer-probe-auto-', '/private/tmp/observer-probe-auto-')):
        p.error('Dedicated private temporary evidence directory required')
    if directory.exists():
        p.error('Use a new evidence directory; previous results must not be overwritten')
    lab = args.lab.resolve()
    if not str(lab).startswith(('/tmp/epay-observer-', '/private/tmp/epay-observer-')):
        p.error('Existing isolated lab directory required')
    os.umask(0o077)
    directory.mkdir(mode=0o700)
    sdk = Path(os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk')
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
    repo = Path(__file__).resolve().parent.parent
    report = {'schema_version': 1, 'utc_started': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'scope': 'Alipay metadata-only probe lifecycle; no message or payment trigger',
              'cases': [], 'cleanup': {}, 'completed': False, 'instrumentation_priority_limitation': True,
              'planned_stages': ['fresh', 'locked', 'offline', 'doze', 'idle', 'probe_process',
                                 'probe_force_stop', 'alipay_force_stop_reopen', 'reboot']}

    def save():
        temporary = directory / 'report.json.tmp'
        temporary.write_text(json.dumps(report, indent=2, ensure_ascii=False))
        temporary.replace(directory / 'report.json')

    def run(*parts, timeout=30):
        return subprocess.check_output(adb + list(parts), text=True, stderr=subprocess.STDOUT, timeout=timeout).strip()

    def shell(command, timeout=30):
        return run('shell', command, timeout=timeout)

    def wait_for(label, predicate, timeout=45):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if predicate():
                return
            time.sleep(0.5)
        raise RuntimeError(label)

    def audit():
        output = subprocess.check_output(['python3', str(repo / 'scripts/prototype/audit_recharge.py'), str(lab)],
                                         text=True, stderr=subprocess.PIPE, timeout=30)
        return json.loads(output)

    def stage(name, timeout=120):
        report['active_stage'] = name
        save()
        path = directory / (name + '.txt')
        with path.open('w') as stream:
            result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'probeAutomation', 'true',
                '-e', 'probeScenario', name, '-e', 'class', 'dev.epay.observer.probe.ProbeLifecycleTest', RUNNER],
                stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        text = path.read_text()
        rows = results_from_log(text)
        if result.returncode != 0 or 'OK (1 test)' not in text or not rows or any(
                bad in text for bad in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=')):
            raise RuntimeError('instrumentation did not pass; inspect private stage log')
        report['cases'].extend(rows)
        save()
        print(name + ': infrastructure passed; actual callback counts ' + str([r['callbacks'] for r in rows]), flush=True)

    def package_stopped(package):
        # Report only the boolean from current Android user 0, never the full package dump.
        user = next((s for s in shell('dumpsys package ' + package).splitlines() if 'User 0:' in s), '')
        match = re.search(r'\bstopped=(true|false)\b', user)
        if match is None:
            raise RuntimeError('package stopped state unavailable')
        return match[1] == 'true'

    def launch_source():
        shell('am start -n ' + SOURCE_ACTIVITY)
        wait_for('source process did not start', lambda: bool(shell('pidof ' + SOURCE + ' || true')))
        if package_stopped(SOURCE):
            raise RuntimeError('source still stopped after normal launch')
        shell('input keyevent KEYCODE_HOME')

    def process_fault(kind):
        report['active_stage'] = 'probe_' + kind
        save()
        path = directory / ('arm-' + kind + '.txt')
        with path.open('w') as stream:
            child = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'probeAutomation', 'true',
                '-e', 'probeScenario', 'arm_fault', '-e', 'class', 'dev.epay.observer.probe.ProbeLifecycleTest', RUNNER],
                stdout=stream, stderr=subprocess.STDOUT)
            try:
                wait_for('probe did not arm for fault', lambda: any(r.get('case') == 'armed_for_fault'
                    for r in results_from_log(path.read_text())), timeout=60)
                pid = shell('pidof ' + PROBE)
                if not re.fullmatch(r'[0-9]+', pid):
                    raise RuntimeError('exactly one probe process required')
                armed = next(r for r in results_from_log(path.read_text()) if r.get('case') == 'armed_for_fault')
                if str(armed.get('pid')) != pid:
                    raise RuntimeError('armed probe process already changed before fault')
                if kind == 'process':
                    shell('run-as ' + PROBE + ' kill -9 ' + pid)
                else:
                    shell('am force-stop ' + PROBE)
                    if not package_stopped(PROBE):
                        raise RuntimeError('probe force-stop flag not set')
                wait_for('old probe process survived', lambda: pid not in shell('pidof ' + PROBE + ' || true').split())
            finally:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.kill(); child.wait(timeout=5)
        shell('am start -n ' + ACTIVITY)
        stage('fresh')
        report['cases'][-1]['case'] = 'probe_' + kind + '_fresh_process'
        save()

    initial = None
    try:
        if shell('getprop ro.kernel.qemu') != '1' or shell('am get-current-user') != '0':
            raise RuntimeError('Dedicated primary-user emulator required')
        if 'UPDATES STOPPED' in shell('dumpsys battery'):
            raise RuntimeError('Existing battery override; no tests started')
        if re.search(r'mForceIdle=true', shell('dumpsys deviceidle')):
            raise RuntimeError('Existing forced Doze; no tests started')
        wifi = shell('settings get global wifi_on')
        mobile = shell('settings get global mobile_data')
        if wifi not in {'0', '1'} or mobile not in {'0', '1'}:
            raise RuntimeError('Unknown network setting; no tests started')
        mappings = []
        for line in run('reverse', '--list').splitlines():
            parts = line.split()
            if len(parts) != 3 or any(not re.fullmatch(r'tcp:[0-9]+', part) for part in parts[1:]):
                raise RuntimeError('Unsupported existing reverse mapping; no reboot attempted')
            mappings.append(parts[1:])
        power = shell('dumpsys power')
        wakefulness = re.search(r'mWakefulness=(Awake|Asleep|Dozing|Dreaming)\b', power)
        if wakefulness is None:
            raise RuntimeError('Unknown screen state; no tests started')
        initial = {'wifi': wifi, 'mobile': mobile,
                   'probe_access': COMPONENT in shell('settings get secure enabled_notification_listeners').split(':'),
                   'screen_awake': wakefulness[1] in {'Awake', 'Dreaming'}, 'reverse': mappings}
        report['initial'] = initial
        report['audit_before'] = audit()
        save()
        launch_source()
        for name, timeout in [('fresh', 90), ('locked', 180), ('offline', 150), ('doze', 120), ('idle', 210)]:
            stage(name, timeout)
        process_fault('process')
        process_fault('force_stop')
        report['active_stage'] = 'alipay_force_stop_reopen'
        shell('am force-stop ' + SOURCE)
        if not package_stopped(SOURCE) or shell('pidof ' + SOURCE + ' || true'):
            raise RuntimeError('source force-stop was not observed')
        launch_source()
        report['cases'].append({'case': 'alipay_force_stop_reopen', 'infrastructure': 'Passed',
                                'notification_delivery': 'Not exercised', 'login_after_reopen': 'Not inspected'})
        save()
        report['active_stage'] = 'reboot'
        save()
        run('reboot')
        run('wait-for-device', timeout=150)
        wait_for('boot incomplete', lambda: shell('getprop sys.boot_completed', timeout=10) == '1', 150)
        for mapping in mappings:
            run('reverse', *mapping)
        stage('fresh', 120)
        report['cases'][-1]['case'] = 'reboot_fresh_process'
        report['completed'] = True
    except (RuntimeError, subprocess.SubprocessError, OSError, ValueError) as error:
        report['completed'] = False
        report['failure_type'] = type(error).__name__
        report['failed_stage'] = report.get('active_stage', 'preflight')
        # Full logs stay private. Do not copy arbitrary ADB exception output into public reports.
        print('Automation stopped; inspect the private report and stage log. Restoring baseline.', flush=True)
    finally:
        if initial is not None:
            cleanup = {
                'stop_probe': 'am force-stop ' + PROBE,
                'unforce_doze': 'dumpsys deviceidle unforce',
                'reset_battery': 'dumpsys battery reset',
                'wifi': 'svc wifi ' + ('enable' if initial['wifi'] == '1' else 'disable'),
                'mobile': 'svc data ' + ('enable' if initial['mobile'] == '1' else 'disable'),
                'probe_access': 'cmd notification ' + ('allow_listener ' if initial['probe_access'] else 'disallow_listener ') + COMPONENT,
                'reopen_probe': 'am start -n ' + ACTIVITY,
                'screen': 'input keyevent ' + ('KEYCODE_WAKEUP' if initial['screen_awake'] else 'KEYCODE_SLEEP'),
            }
            for name, command in cleanup.items():
                try:
                    shell(command, timeout=15)
                    report['cleanup'][name] = 'command_completed'
                except (subprocess.SubprocessError, OSError):
                    report['cleanup'][name] = 'FAILED'
            try:
                launch_source()
                shell('am start -n ' + ACTIVITY)
                shell('input keyevent ' + ('KEYCODE_WAKEUP' if initial['screen_awake'] else 'KEYCODE_SLEEP'))
                report['cleanup']['source_reopened'] = True
                for mapping in initial['reverse']:
                    run('reverse', *mapping)
                actual_mappings = {tuple(line.split()[-2:]) for line in run('reverse', '--list').splitlines()}
                report['cleanup']['reverse_restored'] = all(tuple(mapping) in actual_mappings for mapping in initial['reverse'])
                report['cleanup']['network_settings_match'] = (
                    shell('settings get global wifi_on') == initial['wifi'] and
                    shell('settings get global mobile_data') == initial['mobile'])
                report['cleanup']['probe_access_matches'] = (COMPONENT in shell(
                    'settings get secure enabled_notification_listeners').split(':')) == initial['probe_access']
                report['cleanup']['battery_reset_verified'] = 'UPDATES STOPPED' not in shell('dumpsys battery')
                report['cleanup']['doze_unforced_verified'] = not bool(re.search(r'mForceIdle=true', shell('dumpsys deviceidle')))
                final_power = re.search(r'mWakefulness=(Awake|Asleep|Dozing|Dreaming)\b', shell('dumpsys power'))
                report['cleanup']['screen_matches'] = final_power is not None and (final_power[1] in {'Awake', 'Dreaming'}) == initial['screen_awake']
                report['cleanup']['source_stopped_flag_clear'] = not package_stopped(SOURCE)
                report['audit_after'] = audit()
                report['audit_unchanged'] = 'audit_before' in report and report['audit_before'] == report['audit_after']
            except (subprocess.SubprocessError, OSError, RuntimeError, ValueError):
                report['cleanup']['final_check'] = 'FAILED'
        save()
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    verified = ['network_settings_match', 'probe_access_matches', 'battery_reset_verified',
                'doze_unforced_verified', 'screen_matches', 'source_stopped_flag_clear', 'reverse_restored']
    if not report.get('completed') or not report.get('audit_unchanged') or 'FAILED' in report['cleanup'].values() or any(report['cleanup'].get(k) is not True for k in verified):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
