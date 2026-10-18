"""Private local template research; never payment eligibility or raw output."""
import re

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


def fixed_shape(value):
    if value is None:
        return None
    pieces, end = [], 0
    for match in TOKENS.finditer(value):
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
