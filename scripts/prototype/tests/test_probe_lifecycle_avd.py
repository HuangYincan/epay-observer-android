"""Host driver failure/cleanup contracts; all ADB calls are simulated, never real notifications."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('probe_lifecycle_avd', Path(__file__).resolve().parents[2] / 'probe_lifecycle_avd.py')
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class DriverTest(unittest.TestCase):
    def invoke(self, directory, check_output, run, serial='emulator-5554'):
        argv = ['probe_lifecycle_avd.py', serial, '--lab', '/tmp/epay-observer-unit', '--directory', str(directory)]
        with patch('sys.argv', argv), patch.object(driver.subprocess, 'check_output', side_effect=check_output), \
                patch.object(driver.subprocess, 'run', side_effect=run), contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as caught:
                driver.main()
        return caught.exception.code

    def testPhysicalSerialRejectedBeforeAnyCommand(self):
        def unexpected(*args, **kwargs):
            self.fail('No subprocess allowed before explicit emulator validation')
        self.assertEqual(2, self.invoke('/tmp/observer-probe-auto-unused', unexpected, unexpected, serial='physical-device'))

    def testExistingEvidenceIsNotOverwritten(self):
        with tempfile.TemporaryDirectory(prefix='observer-probe-auto-test-', dir='/tmp') as root:
            path = Path(root) / 'report.json'; path.write_text('preserve')
            def unexpected(*args, **kwargs):
                self.fail('No subprocess allowed for an existing evidence directory')
            self.assertEqual(2, self.invoke(root, unexpected, unexpected))
            self.assertEqual('preserve', path.read_text())

    def environment(self, fail_at='offline', battery_override=False, lock_disabled=False):
        state = {'wifi': '1', 'mobile': '1', 'battery': battery_override, 'doze': False, 'access': True, 'awake': True}
        commands = []
        audit = {'audited_orders': 4, 'distinct_event_consumptions': 4,
                 'distinct_income_ledger_rows': 4, 'newapi_topups_success': 4, 'quota_added': 30000000}

        def check_output(parts, **kwargs):
            if parts[0] == 'python3':
                return json.dumps(audit)
            args = parts[3:]
            if args == ['reverse', '--list']:
                return 'emulator-5554 tcp:18443 tcp:18443\n'
            if args == ['reverse', 'tcp:18443', 'tcp:18443']:
                return ''
            self.assertEqual('shell', args[0])
            c = args[1]; commands.append(c)
            values = {
                'getprop ro.kernel.qemu': '1', 'am get-current-user': '0',
                'locksettings get-disabled --user 0': str(lock_disabled).lower(),
                'dumpsys battery': 'UPDATES STOPPED' if state['battery'] else 'normal battery',
                'dumpsys deviceidle': 'mForceIdle=' + str(state['doze']).lower(),
                'settings get global wifi_on': state['wifi'], 'settings get global mobile_data': state['mobile'],
                'settings get secure enabled_notification_listeners': driver.COMPONENT if state['access'] else 'null',
                'dumpsys power': 'mWakefulness=' + ('Awake' if state['awake'] else 'Asleep'),
                'pidof ' + driver.SOURCE + ' || true': '222',
                'dumpsys package ' + driver.SOURCE: 'User 0: installed=true stopped=false',
            }
            if c in values:
                return values[c]
            if c.startswith('svc wifi '): state['wifi'] = '1' if c.endswith('enable') else '0'
            elif c.startswith('svc data '): state['mobile'] = '1' if c.endswith('enable') else '0'
            elif c == 'dumpsys battery reset': state['battery'] = False
            elif c == 'dumpsys deviceidle unforce': state['doze'] = False
            elif c.startswith('cmd notification '): state['access'] = 'allow_listener ' in c and 'disallow_listener ' not in c
            elif c == 'input keyevent KEYCODE_WAKEUP': state['awake'] = True
            elif c == 'input keyevent KEYCODE_SLEEP': state['awake'] = False
            elif c.startswith(('am start -n ', 'am force-stop ')) or c == 'input keyevent KEYCODE_HOME': pass
            else: self.fail('Unexpected shell command: ' + c)
            return ''

        def run(parts, **kwargs):
            scenario = parts[parts.index('probeScenario') + 1]
            if scenario == fail_at:
                if scenario == 'offline': state['wifi'] = state['mobile'] = '0'
                if scenario == 'doze': state['battery'] = state['doze'] = True
                raise subprocess.TimeoutExpired(parts, 10)
            row = {'case': scenario, 'infrastructure': 'Passed', 'callbacks': 0, 'notification_delivery': 'Not exercised'}
            kwargs['stdout'].write(driver.RESULT_PREFIX + json.dumps(row) + '\nOK (1 test)\n')
            return SimpleNamespace(returncode=0)
        return state, commands, check_output, run

    def testStageTimeoutRestoresNetworkAndDozeBaseline(self):
        for scenario in ('offline', 'doze'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory(prefix='observer-probe-auto-test-', dir='/tmp') as root:
                state, commands, check_output, run = self.environment(fail_at=scenario)
                out = Path(root) / 'run'
                self.assertEqual(1, self.invoke(out, check_output, run))
                report = json.loads((out / 'report.json').read_text())
                self.assertFalse(report['completed'])
                self.assertEqual(scenario, report['failed_stage'])
                self.assertEqual('TimeoutExpired', report['failure_type'])
                self.assertTrue(report['audit_unchanged'])
                for key in ('network_settings_match', 'battery_reset_verified', 'doze_unforced_verified', 'probe_access_matches', 'screen_matches'):
                    self.assertIs(True, report['cleanup'][key])
                self.assertIn('am force-stop ' + driver.PROBE, commands)
                self.assertEqual({'wifi': '1', 'mobile': '1', 'battery': False, 'doze': False, 'access': True, 'awake': True}, state)

    def testExistingBatteryOverrideIsUntouched(self):
        with tempfile.TemporaryDirectory(prefix='observer-probe-auto-test-', dir='/tmp') as root:
            state, commands, check_output, run = self.environment(battery_override=True)
            out = Path(root) / 'run'
            self.assertEqual(1, self.invoke(out, check_output, run))
            report = json.loads((out / 'report.json').read_text())
            self.assertEqual('preflight', report['failed_stage'])
            self.assertEqual({}, report['cleanup'])
            self.assertTrue(state['battery'])
            self.assertNotIn('dumpsys battery reset', commands)

    def testDisabledLockscreenIsReportedSeparatelyFromScreenOff(self):
        with tempfile.TemporaryDirectory(prefix='observer-probe-auto-test-', dir='/tmp') as root:
            state, commands, check_output, run = self.environment(lock_disabled=True)
            out = Path(root) / 'run'
            self.assertEqual(1, self.invoke(out, check_output, run))
            report = json.loads((out / 'report.json').read_text())
            by_case = {r['case']: r for r in report['cases']}
            self.assertEqual('Blocked', by_case['keyguard_lock']['infrastructure'])
            self.assertEqual('Passed', by_case['screen_off']['infrastructure'])
            self.assertNotIn('locked', by_case)
            self.assertFalse(any('locksettings set-disabled' in c for c in commands))
            self.assertTrue(report['cleanup']['lockscreen_policy_matches'])

    def testOnlyStructuredProbeResultLinesAreParsed(self):
        row = {'case': 'locked_1', 'callbacks': 0, 'notification_delivery': 'Not exercised'}
        text = 'unrelated output\n' + driver.RESULT_PREFIX + json.dumps(row) + '\nOK (1 test)\n'
        self.assertEqual([row], driver.results_from_log(text))

    def testPartialLiveLineWaitsForCompletion(self):
        row = {'case': 'armed_for_fault', 'pid': 123}
        text = driver.RESULT_PREFIX + json.dumps(row)
        self.assertEqual([], driver.results_from_log(text))
        self.assertEqual([], driver.results_from_log(text[:30]))
        self.assertEqual([row], driver.results_from_log(text + '\n'))

    def testRebootRequiresChangedIdentityAndToleratesOfflineTransition(self):
        before = '00000000-0000-0000-0000-000000000001'
        after = '00000000-0000-0000-0000-000000000002'
        def read(boot, complete):
            return lambda command, **kwargs: boot if command.startswith('cat ') else complete
        self.assertFalse(driver.reboot_complete(read(before, '1'), before))
        self.assertFalse(driver.reboot_complete(read(after, ''), before))
        self.assertTrue(driver.reboot_complete(read(after, '1'), before))
        def offline(*args, **kwargs):
            raise subprocess.CalledProcessError(1, 'adb')
        self.assertFalse(driver.reboot_complete(offline, before))


if __name__ == '__main__':
    unittest.main()
