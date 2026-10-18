"""Synthetic metadata regressions. No device, notification body or live acceptance."""
import json
from pathlib import Path
import sys
import unittest
from unittest import mock
import subprocess

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from snapshot_alipay_active import snapshot_state, wait_for_snapshot


class SnapshotStateTest(unittest.TestCase):
    def test_partial_file_waits_for_complete_state(self):
        complete = json.dumps(dict(phase='COMPLETE', records=2, limit=2,
                                   historical_snapshot=True, new_notifications_read=False)).encode()
        # Every prefix previously risked premature cleanup and loss of raw data.
        for end in range(len(complete)):
            self.assertIsNone(snapshot_state(complete[:end]))
        self.assertEqual(snapshot_state(complete)['records'], 2)

    def test_invalid_or_expanded_scope_is_not_accepted(self):
        good = dict(phase='COMPLETE', records=2, limit=2,
                    historical_snapshot=True, new_notifications_read=False)
        for patch in [dict(records=3), dict(records=True), dict(limit=3), dict(limit=True),
                      dict(new_notifications_read=True), dict(historical_snapshot=False),
                      dict(phase='RUNNING'), dict(title='synthetic-private-body')]:
            with self.assertRaises(ValueError):
                snapshot_state(json.dumps(dict(good, **patch)))

    def test_error_state_does_not_become_success(self):
        state = dict(phase='ERROR', records=0, limit=2,
                     historical_snapshot=True, new_notifications_read=False)
        self.assertEqual(snapshot_state(json.dumps(state))['phase'], 'ERROR')

    def test_transient_adb_timeout_does_not_discard_completed_snapshot(self):
        complete = json.dumps(dict(phase='COMPLETE', records=2, limit=2,
                                   historical_snapshot=True, new_notifications_read=False)).encode()
        read = mock.Mock(side_effect=[subprocess.TimeoutExpired('synthetic-metadata', 8), b'{', complete])
        with mock.patch('snapshot_alipay_active.time.sleep'):
            self.assertEqual(wait_for_snapshot(read, timeout=1)['records'], 2)
        self.assertEqual(read.call_count, 3)


if __name__ == '__main__':
    unittest.main()
