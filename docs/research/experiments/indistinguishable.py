"""Construct counterexamples: different truths can yield identical observable data.
This is not an implementation/test of the proposed production matcher.
"""
from copy import deepcopy

pairs = []
visible = {'receiver': 'synthetic-r', 'provider': 'wxpay', 'amount_minor': 1001,
           'notification_key': 'synthetic-k', 'post_time': 100,
           'title': '合成收款', 'text': '10.01元'}
pairs.append(('identical receipts vs repeated observation',
              {'observed': [visible, deepcopy(visible)], 'truth': 'one payment'},
              {'observed': [visible, deepcopy(visible)], 'truth': 'two payments'}))
for cooldown in [0, 600, 86400, 31536000]:
    event_time = cooldown + 100
    common = {'event': {'amount_minor': 1001, 'observed_at': event_time},
              'orders': [{'id': 'old', 'expired_at': 1},
                         {'id': 'new', 'created_at': event_time-1, 'status': 'pending'}]}
    pairs.append((f'late static QR after cooldown {cooldown}s',
                  {'observed': common, 'truth': 'old order payment'},
                  {'observed': deepcopy(common), 'truth': 'new order payment'}))
for name, a, b in pairs:
    assert a['observed'] == b['observed']
    assert a['truth'] != b['truth']
    print('COUNTEREXAMPLE:', name)
print('5/5 observation collisions demonstrated; no matcher/database/payment executed')
