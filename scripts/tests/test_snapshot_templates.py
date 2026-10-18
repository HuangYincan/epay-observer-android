"""Synthetic privacy and shape tests, not real Alipay compatibility."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from snapshot_templates import classify_record


class TemplateClassificationTest(unittest.TestCase):
    def record(self):
        return dict(title='你已成功收款12.34元（扫码）', text='合成姓名 合成消息135790', big_text=None,
                    metadata=dict(category='msg', style='BigTextStyle', channel_present=True,
                                  messages_present=False, grouped=False, group_summary=False,
                                  ongoing=False, auto_cancel=True))

    def test_sensitive_text_masked_without_turning_snapshot_into_payment(self):
        result = classify_record(self.record())
        self.assertEqual(result['title_template'], '你已成功收款<number>元（扫码）')
        self.assertEqual(result['text_template'], '<text> <text><number>')
        self.assertEqual(result['title_pattern_matches'], ['扫码'])
        self.assertFalse(result['payment_eligibility'])
        for secret in ['合成姓名', '合成消息', '135790', '12.34']:
            self.assertNotIn(secret, str(result))

    def test_message_or_unrecognized_suffix_cannot_match_fixed_title(self):
        for title in ['有人说你已成功收款12.34元（扫码）', '你已成功收款12.34元（合成姓名）',
                      '你已成功收款12.34元（扫码）\n', None]:
            row = self.record()
            row['title'] = title
            result = classify_record(row)
            self.assertEqual(result['title_pattern_matches'], [])
            self.assertFalse(result['payment_eligibility'])

    def test_extra_fields_identifiers_unknown_metadata_or_bounds_rejected(self):
        base = self.record()
        mutations = [dict(title='x' * 257), dict(text=['private']), dict(notification_key='private'),
                     dict(metadata=dict(base['metadata'], channel_id='private')),
                     dict(metadata=dict(base['metadata'], category='private')),
                     dict(metadata=dict(base['metadata'], style='private')),
                     dict(metadata=dict(base['metadata'], grouped=1))]
        for change in mutations:
            row = copy.deepcopy(base)
            row.update(change)
            with self.assertRaises(ValueError):
                classify_record(row)


if __name__ == '__main__':
    unittest.main()
