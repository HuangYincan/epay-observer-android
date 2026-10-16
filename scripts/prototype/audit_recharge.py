#!/usr/bin/env python3
"""Read-only, allowlisted cross-database audit; emits no credentials or device IDs."""
import argparse
from decimal import Decimal
import json
from pathlib import Path
import subprocess
from lab_state import load_runtime, require_container

p=argparse.ArgumentParser();p.add_argument('directory',type=Path);a=p.parse_args();lab=a.directory.resolve()
meta=load_runtime(lab);container=require_container(meta,'postgres')
def query(database, sql):
    raw=subprocess.check_output(['docker','exec',container,'psql','-v','ON_ERROR_STOP=1','-U','observer_lab','-d',database,'-Atc',sql],text=True)
    return json.loads(raw)
links=query(meta['gateway_database'],"""SELECT COALESCE(json_agg(x),'[]'::json) FROM (
SELECT o.trade_no AS gateway_order,o.out_trade_no AS newapi_order,o.pay_type AS provider,
(o.amount*100)::bigint AS amount_minor,(o.fee*100)::bigint AS fee_minor,
e.id AS event_pk,e.processing_status,d.outcome,d.reason,c.ledger_id,
(l.amount*100)::bigint AS ledger_income_minor,o.status AS order_status,
o.notify_status,o.notify_count
FROM orders o JOIN observer_consumptions c ON c.order_id=o.id
JOIN observer_events e ON e.id=c.event_pk JOIN observer_match_decisions d ON d.event_pk=e.id AND d.order_id=o.id
JOIN balance_records l ON l.id=c.ledger_id AND l.trade_no=o.trade_no AND l.merchant_id=o.merchant_id
WHERE l.type='order_income' AND l.action=1 ORDER BY o.id
) x""")
topups=query(meta['newapi_database'],"""SELECT COALESCE(json_agg(x),'[]'::json) FROM (
SELECT t.trade_no,t.amount,t.money,t.status,t.payment_provider,t.payment_method,u.quota,u.id AS user_id
FROM top_ups t JOIN users u ON u.id=t.user_id ORDER BY t.id
) x""")
assert links, 'no committed settlement audit links'
assert len({x['event_pk'] for x in links})==len(links)==len({x['ledger_id'] for x in links})
assert {x['provider'] for x in links}=={'wxpay','alipay'}
assert len({x['newapi_order'] for x in links})==len(links)
by_trade={x['trade_no']:x for x in topups}
for link in links:
    assert link['order_status']==1 and link['notify_status']==2
    assert link['processing_status']=='credited' and link['outcome']=='matched' and link['reason']=='eligible'
    assert link['ledger_income_minor']==link['amount_minor']-link['fee_minor']
    topup=by_trade[link['newapi_order']]
    assert topup['status']=='success' and topup['payment_provider']=='epay'
    assert topup['payment_method']==link['provider']
    assert Decimal(str(topup['money']))*100==link['amount_minor']
    link['newapi_topup_status']=topup['status']
    link['newapi_credit_quota']=topup['amount']*500000
successful=[t for t in topups if t['status']=='success']
assert len(successful)==len(links) and len({t['user_id'] for t in successful})==1
assert {x['newapi_order'] for x in links}=={x['trade_no'] for x in successful}
baseline=json.loads((lab/'recharge-result.json').read_text())['quota_before']
quota=successful[0]['quota'];assert quota==baseline+sum(x['newapi_credit_quota'] for x in links)
review=query(meta['gateway_database'],"SELECT COALESCE(json_agg(x),'[]'::json) FROM (SELECT outcome,reason,count(*) AS events FROM observer_match_decisions WHERE outcome<>'matched' GROUP BY outcome,reason) x")
result={'layer':'isolated PostgreSQL read-only cross-system audit','orders':links,'original_quota':baseline,'current_quota':quota,'total_quota_added':quota-baseline,'noncredited_decisions':review,'contains_device_ids_or_credentials':False}
(lab/'audit-result.json').write_text(json.dumps(result,indent=2))
print(json.dumps({'audited_orders':len(links),'distinct_event_consumptions':len(links),'distinct_income_ledger_rows':len(links),'newapi_topups_success':len(successful),'quota_added':quota-baseline}))
