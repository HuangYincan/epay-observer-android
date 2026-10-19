"""Synthetic historical-snapshot orchestration, never real receipt evidence."""
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import snapshot_alipay_active as snapshot

CONSENT = 'active-snapshot-post-payment-once-max2-v1'
STEM = 'active-snapshot-post-payment'
SAMPLE_COMPONENT = 'dev.epay.observer.sample.debug/dev.epay.observer.sample.AlipayListener'


class PostPaymentSnapshotTest(unittest.TestCase):
    def run_snapshot(self, previous_override=None, missing_live=False, active_sample=False,
                     restoration_timeouts=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'repo'
            apk = root / 'sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk'
            apk.parent.mkdir(parents=True); apk.write_bytes(b'synthetic-test-apk')
            evidence = Path(directory) / 'evidence'
            granted = False
            listener_reads = 0
            commands = []
            def run(arguments, **kwargs):
                nonlocal granted, listener_reads
                action = arguments[3:]
                command = shlex.split(action[1]) if action[0] == 'shell' else action[1:]
                commands.append(command)
                output = b''
                if command[:3] == ['settings', 'get', 'secure']:
                    listener_reads += 1
                    if 2 <= listener_reads <= restoration_timeouts + 1:
                        raise subprocess.TimeoutExpired('synthetic-listener-metadata', 15)
                    output = (SAMPLE_COMPONENT if active_sample else snapshot.COMPONENT if granted else 'null').encode()
                elif command[:3] == ['cmd', 'package', 'path']:
                    output = b'package:/synthetic/base.apk\n'
                elif command[0] == 'sha256sum':
                    output = hashlib.sha256(apk.read_bytes()).hexdigest().encode() + b' /synthetic/base.apk'
                elif command[:3] == ['cmd', 'notification', 'allow_listener']:
                    granted = True
                elif command[:3] == ['cmd', 'notification', 'disallow_listener']:
                    granted = False
                elif command[:2] == ['run-as', 'dev.epay.observer.sample.debug'] and missing_live:
                    raise subprocess.CalledProcessError(1, arguments)
                elif command[:3] == ['run-as', snapshot.PACKAGE, 'cat']:
                    filename = command[3]
                    if filename.endswith('-status.json'):
                        stem = filename.removeprefix('files/').removesuffix('-status.json')
                        count = 0 if stem == 'active-snapshot-field-roles' else 2
                        if previous_override and stem == previous_override[0]: count = previous_override[1]
                        output = json.dumps(dict(phase='COMPLETE', records=count, limit=2,
                            historical_snapshot=True, new_notifications_read=False)).encode()
                    elif filename == f'files/{STEM}-raw.json':
                        meta = dict(category='none', style='none', channel_present=True,
                            messages_present=False, grouped=True, group_summary=False,
                            ongoing=False, auto_cancel=True)
                        rows = [dict(title=None, text=None, big_text=None,
                                     metadata=dict(meta, group_summary=True)),
                                dict(title='你已成功收款12.34元（余额宝）', text='合成甲名',
                                     big_text=None, metadata=meta)]
                        output = json.dumps(rows, ensure_ascii=False).encode()
                    else: self.fail('unexpected private file')
                target = kwargs.get('stdout')
                if hasattr(target, 'write'):
                    target.write(output); output = None
                return subprocess.CompletedProcess(arguments, 0, stdout=output)
            with patch.object(snapshot, 'ROOT', root), patch.object(sys, 'argv',
                    ['snapshot', '--consent', CONSENT, '--evidence', str(evidence)]), \
                    patch.object(snapshot.subprocess, 'run', side_effect=run), patch('builtins.print'):
                code = snapshot.main()
            report = json.loads((evidence / 'report.json').read_text())
            templates = (evidence / 'templates.json').read_text() if (evidence / 'templates.json').exists() else None
            self.assertFalse((evidence / 'raw.json').exists())
            return code, report, templates, commands

    def test_two_records_extract_after_zero_predecessor_without_reusing_old_scope(self):
        code, report, templates, commands = self.run_snapshot()
        self.assertEqual(code, 0)
        self.assertEqual(report['snapshot']['records'], 2)
        self.assertTrue(report['historical_only'])
        self.assertFalse(report['payment_or_settlement'])
        self.assertTrue(report['cleanup_ok'])
        self.assertTrue(report['cleanup']['previous_markers_and_states_preserved'])
        self.assertEqual(report['listeners_before'], [])
        self.assertEqual(report['listeners_after'], [])
        self.assertEqual(len(json.loads(templates)), 2)
        self.assertEqual(json.loads(templates)[1]['suffix_public_literal'], '余额宝')
        self.assertFalse(json.loads(templates)[1]['payment_eligibility'])
        self.assertNotIn('12.34', templates)
        self.assertNotIn('合成甲名', templates)
        self.assertTrue(any(CONSENT in ' '.join(command) and 'printf' in ' '.join(command) for command in commands))
        self.assertFalse(any('install' == command[0] for command in commands))

    def test_first_three_predecessors_still_require_two(self):
        for stem in ['active-snapshot', 'active-snapshot-recovery', 'active-snapshot-classification']:
            with self.subTest(stem=stem):
                code, report, templates, commands = self.run_snapshot(previous_override=(stem, 0))
                self.assertEqual(code, 1)
                self.assertEqual(report['stage'], 'unused_gate_checks')
                self.assertIsNone(templates)
                self.assertFalse(any('allow_listener' in command for command in commands))

    def test_one_metadata_timeout_can_recheck_without_recollecting_raw(self):
        code, report, templates, commands = self.run_snapshot(restoration_timeouts=1)
        self.assertEqual(code, 0)
        self.assertTrue(report['cleanup']['listeners_restored'])
        self.assertEqual(report['listener_recheck_error_types'], ['TimeoutExpired'])
        self.assertEqual(sum(command[:3] == ['cmd', 'notification', 'allow_listener'] for command in commands), 1)

    def test_persistent_restoration_timeout_is_failed_without_losing_templates(self):
        code, report, templates, commands = self.run_snapshot(restoration_timeouts=2)
        self.assertEqual(code, 1)
        self.assertFalse(report['cleanup_ok'])
        self.assertFalse(report['cleanup']['listeners_restored'])
        self.assertEqual(report['listeners_before'], [])
        self.assertEqual(report['listener_recheck_error_types'], ['TimeoutExpired', 'TimeoutExpired'])
        self.assertEqual(len(json.loads(templates)), 2)
        self.assertTrue(report['cleanup']['host_raw_deleted'])

    def test_live_scope_must_be_closed_and_consumed_before_snapshot(self):
        for options in [dict(missing_live=True), dict(active_sample=True)]:
            with self.subTest(options=options):
                code, report, templates, commands = self.run_snapshot(**options)
                self.assertEqual(code, 1)
                self.assertIsNone(templates)
                self.assertFalse(any('allow_listener' in command for command in commands))


if __name__ == '__main__':
    unittest.main()
