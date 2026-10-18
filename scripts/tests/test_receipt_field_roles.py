"""Synthetic private-role research regressions; no live payment assertion."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from snapshot_templates import classify_field_roles


class ReceiptFieldRolesTest(unittest.TestCase):
    def row(self, title):
        return dict(title=title, text='合成甲名 付款备注：合成私信135790', big_text=None,
                    metadata=dict(category='none', style='none', channel_present=True,
                                  messages_present=False, grouped=True, group_summary=False,
                                  ongoing=False, auto_cancel=True))

    def test_finite_public_literal_and_fixed_words_without_private_amount_or_prose(self):
        result = classify_field_roles(self.row('你已成功收款12.34元（余额宝）'))
        self.assertTrue(result['title_grammar_matches'])
        self.assertEqual(result['suffix_public_literal'], '余额宝')
        self.assertEqual(result['text_template'], '<text> 付款备注：<text><number>')
        self.assertFalse(result['payment_eligibility'])
        for secret in ['12.34', '合成甲名', '合成私信', '135790']:
            self.assertNotIn(secret, str(result))

    def test_masked_name_form_is_only_a_shape_not_a_person_or_payment_identity(self):
        result = classify_field_roles(self.row('你已成功收款7.89元（合*名）'))
        self.assertEqual(result['suffix_shape'], 'masked_name_form')
        self.assertIsNone(result['suffix_public_literal'])
        self.assertNotIn('合*名', str(result))
        self.assertFalse(result['payment_eligibility'])

    def test_unknown_label_cannot_be_exported_or_promoted_to_a_public_literal(self):
        for label in ['合成姓名', '余额宝合成姓名', '余额宝 ']:
            result = classify_field_roles(self.row(f'你已成功收款7.89元（{label}）'))
            self.assertEqual(result['suffix_shape'], 'unknown_opaque_label')
            self.assertIsNone(result['suffix_public_literal'])
            self.assertNotIn('合成姓名', str(result))
            self.assertFalse(result['payment_eligibility'])

    def test_noncanonical_amount_control_or_appended_message_never_matches(self):
        for title in ['你已成功收款0元（余额宝）', '你已成功收款-1元（余额宝）',
                      '你已成功收款01元（余额宝）', '你已成功收款1.001元（余额宝）',
                      '你已成功收款１元（余额宝）', '你已成功收款10000000000元（余额宝）',
                      '你已成功收款1元（余\u202e额宝）', '你已成功收款1元（余额宝）\n',
                      '私信：你已成功收款1元（余额宝）']:
            result = classify_field_roles(self.row(title))
            self.assertFalse(result['title_grammar_matches'])
            self.assertIsNone(result['suffix_public_literal'])
            self.assertFalse(result['payment_eligibility'])


if __name__ == '__main__':
    unittest.main()
