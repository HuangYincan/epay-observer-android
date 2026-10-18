"""Private local template research; never payment eligibility or raw output."""
import re
import unicodedata

WORDS = ('支付宝', '收钱码', '二维码', '你已', '通过', '扫码', '成功', '收款', '付款',
         '转账', '到账', '收入', '人民币', '金额', '元')
TOKENS = re.compile('|'.join(map(re.escape, WORDS)) + r'|[0-9]+(?:[.．][0-9]+)?|[，。:：.,￥¥()（）\- ]')
CATEGORIES = {'none', 'other', 'msg', 'email', 'service', 'status', 'promo', 'sys',
              'progress', 'event', 'reminder', 'transport', 'call', 'alarm', 'err',
              'recommendation', 'social', 'navigation', 'workout', 'location',
              'stopwatch', 'missed_call'}
STYLES = {'none', 'other', 'BigTextStyle', 'BigPictureStyle', 'InboxStyle',
          'MessagingStyle', 'MediaStyle', 'DecoratedCustomViewStyle', 'CallStyle'}
BOOLEANS = {'channel_present', 'messages_present', 'grouped', 'group_summary', 'ongoing', 'auto_cancel'}

# Finite public-word research probes, never a claim that Alipay supports a rule.
PUBLIC_LABELS = frozenset({'扫码', '收钱码', '二维码', '扫码付款', '扫码收款', '二维码收款',
    '二维码付款', '收钱码收款', '个人收款', '商家收款', '商家服务', '当面付', '转账', '转账收款',
    '余额', '余额宝', '支付宝余额', '花呗', '银行卡', '信用卡', '储蓄卡', '红包', '集分宝',
    '网商银行', '余额付款', '余额宝付款', '花呗付款', '银行卡付款', '收款提示'})
ROLE_WORDS = tuple(sorted(set(WORDS) | PUBLIC_LABELS | {'付款方', '付款人', '收款方', '收款人',
    '来自', '对方', '留言', '备注', '实名', '姓名', '昵称', '匿名用户', '顾客', '买家', '向你',
    '转入', '通过'}, key=lambda word: (-len(word), word)))
ROLE_TOKENS = re.compile('|'.join(map(re.escape, ROLE_WORDS)) + r'|[0-9]+(?:[.．][0-9]+)?|[，。:：.,￥¥()（）\- ]')
TITLE_LAYOUT = re.compile(r'你已成功收款((?:0|[1-9][0-9]{0,9})(?:\.[0-9]{1,2})?)元（([^\r\n（）]{1,64})）')
MASKED_NAME_FORM = re.compile(r'[\u4e00-\u9fffA-Za-z·]{1,30}[*＊]{1,6}[\u4e00-\u9fffA-Za-z·]{0,30}')


def fixed_shape(value):
    return _shape(value, TOKENS)


def _shape(value, tokens):
    if value is None:
        return None
    pieces, end = [], 0
    for match in tokens.finditer(value):
        if match.start() > end:
            pieces.append('<text>')
        token = match.group()
        pieces.append('<number>' if token[0].isdigit() else token)
        end = match.end()
    if end < len(value):
        pieces.append('<text>')
    return ''.join(pieces)


def classify_record(row):
    if not isinstance(row, dict) or set(row) != {'title', 'text', 'big_text', 'metadata'}:
        raise ValueError('raw schema')
    for field, limit in [('title', 256), ('text', 1024), ('big_text', 1024)]:
        if row[field] is not None and (not isinstance(row[field], str) or len(row[field]) > limit):
            raise ValueError('raw field')
    meta = row['metadata']
    if not isinstance(meta, dict) or set(meta) != BOOLEANS | {'category', 'style'}:
        raise ValueError('metadata schema')
    if (not isinstance(meta['category'], str) or meta['category'] not in CATEGORIES or
            not isinstance(meta['style'], str) or meta['style'] not in STYLES or
            any(type(meta[key]) is not bool for key in BOOLEANS)):
        raise ValueError('metadata value')
    result = {key + '_template': fixed_shape(row[key]) for key in ['title', 'text', 'big_text']}
    result['metadata'] = meta.copy()
    # Full-string research probes only; never a candidate or authorization.
    result['title_pattern_matches'] = [
        suffix for suffix in ['扫码', '收钱码', '二维码', '扫码付款', '扫码收款', '转账']
        if row['title'] is not None and re.fullmatch(
            r'你已成功收款[0-9]+(?:\.[0-9]{1,2})?元（' + suffix + r'）', row['title'])]
    result['payment_eligibility'] = False
    return result


def classify_field_roles(row):
    result = classify_record(row)  # Preserve the exact field/type/privacy gate.
    result['text_template'] = _shape(row['text'], ROLE_TOKENS)
    result['big_text_template'] = _shape(row['big_text'], ROLE_TOKENS)
    result.update(field_role_assessment(row['title'], row['text']))
    return result


def field_role_assessment(title, text):
    for value, limit in [(title, 256), (text, 1024)]:
        if value is not None and (not isinstance(value, str) or len(value) > limit):
            raise ValueError('raw field')
    result = dict(title_template=fixed_shape(title), text_template=_shape(text, ROLE_TOKENS),
                  title_grammar_matches=False, canonical_positive_amount=False,
                  suffix_public_literal=None, suffix_shape='not_matching_title', payment_eligibility=False)
    match = TITLE_LAYOUT.fullmatch(title) if title is not None else None
    if match is not None:
        amount, suffix = match.groups()
        whole, _, fraction = amount.partition('.')
        positive = int(whole) * 100 + int(fraction.ljust(2, '0') or '0') > 0
        clean_suffix = not any(unicodedata.category(c).startswith('C') for c in suffix)
        result['canonical_positive_amount'] = positive
        result['title_grammar_matches'] = positive and clean_suffix
        if positive and clean_suffix:
            if suffix in PUBLIC_LABELS:
                result['suffix_public_literal'] = suffix
                result['suffix_shape'] = 'exact_public_literal'
            elif MASKED_NAME_FORM.fullmatch(suffix):
                result['suffix_shape'] = 'masked_name_form'
            else:
                result['suffix_shape'] = 'unknown_opaque_label'
    result['text_layout'] = ('absent' if text is None else
                            'two_nonempty_tokens' if len(text.split()) == 2 else 'other')
    result['text_contains_mask'] = text is not None and ('*' in text or '＊' in text)
    return result
