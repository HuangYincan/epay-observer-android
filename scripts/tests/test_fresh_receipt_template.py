"""Synthetic fresh-template control/privacy regressions; no AVD or payment."""
import json
import hashlib
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from private_content_sample import execute, process_raw, Driver, BASELINE, STATE_PREFIX
from scan_sample_control import ScanSession


class FreshReceiptTemplateTest(unittest.TestCase):
    def test_only_exact_installed_test_apk_hash_skips_reinstallation(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root) / 'synthetic.apk'; apk.write_bytes(b'synthetic-test-artifact')
            for exact in [True, False]:
                driver = Driver(Path('/existing/adb'), 'emulator-5554')
                driver.shell = Mock(side_effect=['package:/synthetic/path/base.apk',
                    (hashlib.sha256(apk.read_bytes()).hexdigest() if exact else 'wrong') + '  /synthetic/path/base.apk'])
                driver.adb = Mock()
                result = driver.ensure_installed(apk, 'dev.epay.observer.sample.debug')
                self.assertEqual(result, 'unchanged_exact_hash' if exact else 'installed')
                if exact: driver.adb.assert_not_called()
                else: driver.adb.assert_called_once_with('install', '-r', str(apk), timeout=60)

    def test_waiting_mode_cannot_collect_or_ack_payment(self):
        with tempfile.TemporaryDirectory() as root:
            runner = Mock()
            session = ScanSession(Path(root) / 'fresh', runner, page_test=True, receipt_template=True)
            self.assertEqual(session.snapshot()['spec'], 'SPEC-039')
            self.assertTrue(session.public()['receipt_template'])
            self.assertEqual(session.public()['state'], 'WAITING')
            self.assertFalse(session.snapshot()['start_claimed'])
            self.assertFalse(session.confirm_sent())
            runner.assert_not_called()

    def test_no_old_cli_or_conflicting_scope_can_start(self):
        for args in [dict(receipt_template=True, receipt_window=False),
                     dict(receipt_template=True, unpaid_retry=True, receipt_window=True)]:
            with self.assertRaises(ValueError):
                execute(SimpleNamespace(serial='emulator-5554', **args))

    def test_fixed_fresh_consent_is_the_only_selected_mode(self):
        with tempfile.TemporaryDirectory() as root:
            child = Mock(); child.poll.return_value = 0; child.returncode = 0
            cancel = Mock(); cancel.is_set.return_value = False
            def launch(arguments, **kwargs):
                self.assertIn('receipt-template-once-120s-max2-v1', arguments)
                self.assertNotIn('scan-payment-once-120s-v1', arguments)
                state = dict(event='armed', phase='RUNNING', callbacks=0, elapsed_ms=0,
                             remaining_seconds=120, connected=True, source_matches=True, instrumented_process=True)
                terminal = dict(state, event='finished', phase='LIMIT', callbacks=2, elapsed_ms=1000, remaining_seconds=0)
                kwargs['stdout'].write(''.join(STATE_PREFIX + json.dumps(r) + '\n' for r in [state, terminal]).encode())
                kwargs['stdout'].flush()
                return child
            with patch('private_content_sample.subprocess.Popen', side_effect=launch):
                Driver(Path('/existing/adb'), 'emulator-5554').observe_receipt(
                    Path(root) / 'log', Mock(), cancel, receipt_template=True)

    def test_prior_markers_required_before_grant_or_launch(self):
        for previous in ['used', 'used-scan-payment-v1', 'used-scan-payment-unpaid-retry-v1']:
            with self.subTest(previous=previous), tempfile.TemporaryDirectory() as root:
                driver = Mock()
                driver.ensure_installed.return_value = 'unchanged_exact_hash'
                driver.adb.return_value = b'List of devices attached\nemulator-5554\tdevice\n'
                def shell(*args):
                    if args == ('getprop', 'ro.kernel.qemu'): return '1'
                    if args == ('pidof', 'com.eg.android.AlipayGphone'): return '123'
                    if args[-3:] == ('test', '-e', 'no_backup/' + previous): raise ValueError('missing marker')
                    return ''
                driver.shell.side_effect = shell
                args = SimpleNamespace(serial='emulator-5554', adb=Path('/existing/adb'), evidence=Path(root) / 'evidence',
                    lab=Path(root), apk=Path('/existing/a.apk'), runner=Path('/existing/b.apk'), receipt_window=True, receipt_template=True)
                cancel = Mock(); cancel.is_set.return_value = False
                with patch('private_content_sample.Driver', return_value=driver), patch('private_content_sample.audit', return_value=BASELINE):
                    self.assertEqual(1, execute(args, Mock(), cancel))
                driver.shell.assert_any_call('run-as', 'dev.epay.observer.sample.debug', 'test', '-e', 'no_backup/' + previous)
                driver.observe_receipt.assert_not_called()
                self.assertFalse(any('allow_listener' in call.args for call in driver.shell.call_args_list))

    def test_fresh_extraction_uses_role_check_and_always_deletes_raw(self):
        for count in [2, 3]:
            with self.subTest(count=count), tempfile.TemporaryDirectory() as root:
                raw, output = Path(root) / 'raw', Path(root) / 'templates'
                raw.write_text((json.dumps({'title': '你已成功收款12.34元（余额宝）', 'text': '合成甲名'}, ensure_ascii=False) + '\n') * count)
                if count == 3:
                    with self.assertRaises(ValueError): process_raw(raw, output, receipt_template=True)
                    self.assertFalse(output.exists())
                else:
                    self.assertEqual(2, process_raw(raw, output, receipt_template=True))
                    rows = json.loads(output.read_text())
                    self.assertEqual(rows[0]['suffix_public_literal'], '余额宝')
                    self.assertFalse(rows[0]['payment_eligibility'])
                    self.assertNotIn('12.34', output.read_text())
                    self.assertNotIn('合成甲名', output.read_text())
                self.assertFalse(raw.exists())


if __name__ == '__main__':
    unittest.main()
