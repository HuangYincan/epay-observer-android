"""SPEC-042 host contracts; synthetic data, no real payment compatibility claim."""
import json
from pathlib import Path
import sys
import tempfile
import subprocess
import unittest
from unittest.mock import Mock, patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from receipt_chat_control import ComparisonSession, ComparisonDriver, analyze_private, state, run_comparison, prepare
from types import SimpleNamespace


def record(kind='historical', **changes):
    r = dict(kind=kind, title='你已成功收款12.34元（合成）', text='甲转入余额宝 乙1丙2丁', big_text=None,
        flags=16, category='none', style='none', group_present=True, channel_present=True,
        channel_fingerprint='a'*64, presentation_valid=True,
        additional=dict.fromkeys(['android.subText','android.summaryText','android.textLines',
            'android.messages','android.messages.historic','android.title.big'], False))
    return dict(r, **changes)


class ComparisonContracts(unittest.TestCase):
    def test_waiting_does_not_read_and_start_is_once(self):
        with tempfile.TemporaryDirectory() as d:
            runner = Mock()
            session = ComparisonSession(Path(d)/'session', runner, page_test=True)
            for _ in range(3):
                self.assertEqual('WAITING', session.public()['state'])
            self.assertFalse(session.confirm_sent())
            runner.assert_not_called()
            self.assertTrue(session.start())
            session.thread.join()
            self.assertFalse(session.start())
            self.assertEqual(1, runner.call_count)

    def test_nonce_and_valid_user_ack_both_required(self):
        with tempfile.TemporaryDirectory() as d:
            session = ComparisonSession(Path(d)/'session', Mock(), phrase='nonce', page_test=True)
            session.change(start_claimed=True, posted_nonce_matches=True)
            self.assertFalse(session.confirm_sent())
            session.arm_budget()
            self.assertFalse(session.confirm_sent())
            session.sample(dict(phase='RUNNING',remaining_seconds=119,callbacks=0))
            self.assertTrue(session.confirm_sent())
            self.assertTrue(session.report['controlled_chat'])
            self.assertFalse(session.confirm_sent())
            session.delivery_deadline = 0
            self.assertFalse(session.public()['can_confirm'])

    def test_ack_closes_on_stale_cancelled_failed_or_unobserved_window(self):
        for condition in ['stale','cancelled','failed','evidence_failed','terminal']:
            with self.subTest(condition=condition), tempfile.TemporaryDirectory() as d:
                session=ComparisonSession(Path(d)/'session', Mock(),page_test=True)
                session.change(start_claimed=True)
                session.arm_budget()
                session.sample(dict(phase='RUNNING',remaining_seconds=119,callbacks=0))
                if condition=='stale':session.observed_live_at-=13
                elif condition=='cancelled':session.cancelled.set()
                elif condition=='failed':session.change(error_type='ValueError')
                elif condition=='evidence_failed':session.evidence_failed=True
                else:session.change(state='FINISHED')
                self.assertFalse(session.confirm_sent())

    def test_private_field_research_calls_actual_kotlin_and_redacts(self):
        with tempfile.TemporaryDirectory() as d:
            raw=Path(d)/'raw.json'
            raw.write_text(json.dumps([record(),record('posted',title='秘密姓名',text='nonce')]))
            def parser(*args, **kwargs):
                self.assertEqual(str(raw),kwargs['env']['OBSERVER_COMPARISON_RAW'])
                self.assertIn('dev.epay.observer.ReceiptFieldResearchTest', args[0])
                (raw.parent/'parser-results.json').write_text('[]')
            with patch('receipt_chat_control.subprocess.run', side_effect=parser):
                result=analyze_private(raw,'nonce')
            self.assertTrue(result['posted_nonce_matches'])
            self.assertTrue(result['channels_equal'])
            templates=(raw.parent/'templates.json').read_text()
            self.assertNotIn('秘密姓名',templates)
            self.assertNotIn('nonce',templates)
            self.assertNotIn('a'*64,templates)
            self.assertFalse(result['payment_eligibility'])

    def test_rejects_private_metadata_or_duplicate_slots_before_parser(self):
        for rows in [[record(),record()], [record(style='private-name')], [record(flags=True)],
                     [record(additional={'private':True})], [record(channel_fingerprint='private')]]:
            with self.subTest(rows=len(rows)), tempfile.TemporaryDirectory() as d:
                raw=Path(d)/'raw.json';raw.write_text(json.dumps(rows))
                with patch('receipt_chat_control.subprocess.run') as parser, self.assertRaises(ValueError):
                    analyze_private(raw,'nonce')
                parser.assert_not_called()

    def test_cleanup_runs_after_failure_and_never_starts_on_old_state_failure(self):
        for touched in [False, True]:
            with tempfile.TemporaryDirectory() as d:
                session=ComparisonSession(Path(d)/'session', Mock(),page_test=True)
                driver=Mock()
                if touched:
                    driver.shell.side_effect=lambda *a: (_ for _ in ()).throw(ValueError()) if 'allow_listener' in a else ''
                with patch('receipt_chat_control.ComparisonDriver',return_value=driver), \
                     patch('receipt_chat_control.listeners',return_value=set()), \
                     patch('receipt_chat_control.audit'), \
                     patch('receipt_chat_control.check_old',side_effect=[{'old':'hash'},{'old':'hash'}] if touched else ValueError()), \
                     patch('receipt_chat_control.check_unused'):
                    run_comparison(SimpleNamespace(adb=Path('/adb'),serial='emulator-5554',lab=Path(d)),session)
                calls=[c.args for c in driver.shell.call_args_list]
                if touched:
                    self.assertTrue(any('disallow_listener' in c for c in calls))
                    self.assertTrue(any('force-stop' in c for c in calls))
                else:
                    self.assertFalse(any('allow_listener' in c for c in calls))
                self.assertEqual('FAILED',session.public()['state'])
                self.assertFalse((session.directory/'raw.json').exists())

    def test_host_delete_failure_does_not_skip_restore_or_accounting(self):
        with tempfile.TemporaryDirectory() as d:
            session=ComparisonSession(Path(d)/'session',Mock(),page_test=True)
            with patch('receipt_chat_control.ComparisonDriver',return_value=Mock()), \
                 patch('receipt_chat_control.listeners',return_value=set()) as ls, \
                 patch('receipt_chat_control.check_old',side_effect=ValueError()), \
                 patch('receipt_chat_control.audit') as audit, \
                 patch.object(Path,'unlink',side_effect=OSError('synthetic')):
                run_comparison(SimpleNamespace(adb=Path('/adb'),serial='emulator-5554',lab=Path(d)),session)
            self.assertFalse(session.report['cleanup']['host_raw_deleted'])
            self.assertTrue(session.report['cleanup']['four_order_audit'])
            self.assertEqual(2,ls.call_count)
            self.assertEqual(2,audit.call_count)

    def test_prepare_stops_test_process_even_after_failure_without_granting(self):
        for fails in [True,False]:
            with self.subTest(fails=fails), tempfile.TemporaryDirectory() as d:
                driver=Mock()
                def shell(*a):
                    if fails and 'start' in a:raise TimeoutError()
                    return ''
                driver.shell.side_effect=shell
                with patch('receipt_chat_control.listeners',return_value=set()), \
                     patch('receipt_chat_control.audit') as audit, \
                     patch('receipt_chat_control.check_old',return_value={'old':'hash'}), \
                     patch('receipt_chat_control.check_unused'):
                    result=prepare(driver,Path(d),Path(d))
                self.assertEqual(not fails,result['ready'])
                self.assertFalse(result['content_read'])
                self.assertTrue(result['cleanup']['test_process_stopped'])
                self.assertFalse(any('allow_listener' in c.args for c in driver.shell.call_args_list))
                self.assertEqual(2,audit.call_count)

    def test_both_install_failure_paths_still_stop_test_package(self):
        for side_effect in [[TimeoutError()],['installed',TimeoutError()]]:
            with tempfile.TemporaryDirectory() as d:
                session=ComparisonSession(Path(d)/'session',Mock(),page_test=True)
                driver=Mock();driver.ensure_installed.side_effect=side_effect
                with patch('receipt_chat_control.ComparisonDriver',return_value=driver), \
                     patch('receipt_chat_control.listeners',return_value=set()), \
                     patch('receipt_chat_control.check_old',return_value={'old':'hash'}), \
                     patch('receipt_chat_control.check_unused'),patch('receipt_chat_control.audit'):
                    run_comparison(SimpleNamespace(adb=Path('/adb'),serial='emulator-5554',lab=Path(d)),session)
                self.assertTrue(any('force-stop' in c.args for c in driver.shell.call_args_list))
                self.assertFalse(any('allow_listener' in c.args for c in driver.shell.call_args_list))
                self.assertEqual('FAILED',session.public()['state'])

    def test_consumed_marker_loss_fails_even_when_other_cleanup_succeeds(self):
        for missing in [False,True]:
            with self.subTest(missing=missing),tempfile.TemporaryDirectory() as d:
                session=ComparisonSession(Path(d)/'session',Mock(),page_test=True)
                driver=Mock()
                def shell(*args):
                    if missing and args[-2:]==('-f','no_backup/used-receipt-chat-comparison-v1'):
                        raise subprocess.CalledProcessError(1,['synthetic-marker-check'])
                    return ''
                driver.shell.side_effect=shell
                def adb(*args,**kwargs):
                    if 'stdout' in kwargs:
                        kwargs['stdout'].write(json.dumps([record('posted')]).encode());return b''
                    return json.dumps(dict(phase='COMPLETE',records=1,remaining_seconds=0)).encode()
                driver.adb.side_effect=adb
                with patch('receipt_chat_control.ComparisonDriver',return_value=driver), \
                     patch('receipt_chat_control.listeners',return_value=set()), \
                     patch('receipt_chat_control.check_old',return_value={'old':'hash'}), \
                     patch('receipt_chat_control.check_unused'),patch('receipt_chat_control.audit'), \
                     patch('receipt_chat_control.analyze_private',return_value={'records':1,'posted_nonce_matches':False}):
                    run_comparison(SimpleNamespace(adb=Path('/adb'),serial='emulator-5554',lab=Path(d)),session)
                self.assertEqual(not missing,session.report['cleanup_ok'])
                self.assertEqual(not missing,session.report['marker_used'])
                self.assertEqual('FAILED' if missing else 'FINISHED',session.report['state'])
                self.assertFalse((session.directory/'raw.json').exists())

    def test_remote_command_quoting_preserves_run_as_scope(self):
        driver=ComparisonDriver(Path('/adb'),'emulator-5554')
        driver.adb=Mock(return_value=b'')
        driver.shell('run-as','package','sh','-c','mkdir -p files && printf x > files/consent')
        self.assertIn("'mkdir -p files && printf x > files/consent'",driver.adb.call_args.args[1])

    def test_terminal_state_cannot_keep_green_countdown(self):
        for row in [dict(phase='COMPLETE',records=2,remaining_seconds=1),
                    dict(phase='RUNNING',records=3,remaining_seconds=100),
                    dict(phase='RUNNING',records=True,remaining_seconds=100)]:
            with self.assertRaises(ValueError):state(json.dumps(row))


if __name__=='__main__':unittest.main()
