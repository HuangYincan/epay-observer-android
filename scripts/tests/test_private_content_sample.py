"""Synthetic tests only: these are not Alipay receipt or notification evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

SPEC = importlib.util.spec_from_file_location('content_sample', Path(__file__).parents[1] / 'private_content_sample.py')
sample = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sample)


class ContentSampleTests(unittest.TestCase):
    def test_unknown_and_falsely_successful_window_reports_rejected(self):
        with self.assertRaises(ValueError):
            sample.execute(SimpleNamespace(serial='emulator-5554', evidence=sample.REPO_ROOT / 'forbidden-private-evidence'))
        self.assertFalse((sample.REPO_ROOT / 'forbidden-private-evidence').exists())
        good = dict(phase='EXPIRED', samples=0, elapsed_ms=120001, started_epoch_ms=100,
                    ended_epoch_ms=120101, instrumented_process=True)
        sample.validate_window(good)
        for changes in [dict(extra='private'), dict(phase='ERROR'), dict(elapsed_ms=119999),
                        dict(samples=True), dict(samples=33), dict(phase='LIMIT', samples=31),
                        dict(started_epoch_ms='private'), dict(instrumented_process=False)]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                sample.validate_window(dict(good, **changes))

    def test_unknown_prose_names_accounts_unicode_and_digits_are_masked(self):
        text = '小明(abc@example.test) 给 13800138000 账户ＡＢＣ 发了验证码１２３４５６🔒'
        shaped = sample.shape(text)
        for secret in ['小明', 'abc', 'example', 'test', '138', 'ＡＢＣ', '１２３', '🔒']:
            self.assertNotIn(secret, shaped)
        self.assertIn('<number>', shaped)

    def test_public_words_preserved_without_promoting_payment(self):
        self.assertEqual('支付宝收款成功，金额<number>元<text>', sample.shape('支付宝收款成功，金额1.23元小明'))
        self.assertEqual('<text>收款<text>', sample.shape('聊聊收款这个词'))
        self.assertIsNone(sample.shape(None))

    def test_export_is_private_and_raw_removed_after_success(self):
        with tempfile.TemporaryDirectory() as directory:
            raw = Path(directory) / 'raw'
            raw.write_text(json.dumps({'title': '某人', 'text': '合成收款1元'}) + '\n')
            output = Path(directory) / 'templates.json'
            self.assertEqual(1, sample.process_raw(raw, output))
            self.assertFalse(raw.exists())
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            self.assertNotIn('某人', output.read_text())

    def test_raw_is_deleted_on_invalid_json_schema_size_and_field_type(self):
        cases = ['not-json', json.dumps({'title': 'private', 'text': 'private', 'id': 'private'}),
                 json.dumps({'title': {}, 'text': None}), json.dumps({'title': 'a' * 257, 'text': None}),
                 'x' * (sample.MAX_RAW + 1)]
        for body in cases:
            with self.subTest(size=len(body)), tempfile.TemporaryDirectory() as directory:
                raw = Path(directory) / 'raw'
                raw.write_text(body)
                with self.assertRaises((ValueError, TypeError)):
                    sample.process_raw(raw, Path(directory) / 'templates')
                self.assertFalse(raw.exists())

    def test_more_than_32_samples_rejected_and_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            raw = Path(directory) / 'raw'
            raw.write_text((json.dumps({'title': None, 'text': None}) + '\n') * 33)
            with self.assertRaises(ValueError):
                sample.process_raw(raw, Path(directory) / 'templates')
            self.assertFalse(raw.exists())

    def test_existing_output_not_overwritten_and_raw_still_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            raw = Path(directory) / 'raw'
            raw.write_text('{"title": null, "text": null}\n')
            output = Path(directory) / 'templates'
            output.write_text('previous evidence')
            with self.assertRaises(FileExistsError):
                sample.process_raw(raw, output)
            self.assertFalse(raw.exists())
            self.assertEqual('previous evidence', output.read_text())

    def test_cleanup_attempts_all_actions_on_failure(self):
        calls = []
        class BrokenDriver:
            def shell(self, *args):
                calls.append(args)
                if args[0] == 'cmd':
                    raise RuntimeError('private error text must never be reported')
            def remove_device_raw(self):
                calls.append('delete')
        with tempfile.TemporaryDirectory() as directory:
            raw = Path(directory) / 'raw'
            raw.write_text('sensitive')
            result = sample.cleanup(BrokenDriver(), raw)
            self.assertFalse(result['permission_revoked'])
            self.assertTrue(result['process_stopped'])
            self.assertTrue(result['device_raw_deleted'])
            self.assertTrue(result['host_raw_deleted'])
            self.assertEqual(3, len(calls))
            self.assertFalse(raw.exists())

    def test_partial_export_failure_cleans_raw_restores_access_and_never_retries_window(self):
        calls = []
        class FakeDriver:
            def __init__(self, *_): pass
            def shell(self, *args):
                calls.append(args)
                if args[:2] == ('getprop', 'ro.kernel.qemu'): return '1'
                if args[:4] == ('settings', 'get', 'secure', 'enabled_notification_listeners'): return 'original'
                return ''
            def adb(self, *args, **kwargs):
                calls.append(args)
                if args == ('devices',): return b'List of devices attached\nemulator-5554\tdevice\n'
                if args[:3] == ('shell', 'am', 'instrument'):
                    kwargs['stdout'].write(b'OK (1 test)\n')
                return b''
            def copy_private(self, name, destination):
                if name == 'window.json':
                    destination.write_text(json.dumps(dict(phase='EXPIRED', samples=1, elapsed_ms=120001,
                        started_epoch_ms=100, ended_epoch_ms=120101, instrumented_process=True)))
                else:
                    destination.write_text('partial sensitive body')
                    raise RuntimeError('private error')
            def remove_device_raw(self): calls.append(('delete-device-raw',))
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / 'run1'
            args = SimpleNamespace(serial='emulator-5554', adb=Path('/existing/adb'), evidence=evidence,
                lab=Path('/private/lab'), apk=Path('/private/sample.apk'), runner=Path('/private/runner.apk'))
            with patch.object(sample, 'Driver', FakeDriver), patch.object(sample, 'audit', return_value=sample.BASELINE):
                self.assertEqual(1, sample.execute(args))
            self.assertFalse((evidence / 'raw.jsonl').exists())
            report = json.loads((evidence / 'report.json').read_text())
            self.assertEqual('Failed', report['status'])
            self.assertTrue(all(report['cleanup'].values()))
            self.assertTrue(report['listeners_restored'])
            self.assertNotIn('private error', json.dumps(report))
            self.assertEqual(1, sum(c[:3] == ('shell', 'am', 'instrument') for c in calls))
            self.assertIn(('delete-device-raw',), calls)


if __name__ == '__main__':
    unittest.main()
