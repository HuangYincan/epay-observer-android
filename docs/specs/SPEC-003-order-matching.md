# SPEC-003 事件与订单匹配

状态：M0 review candidate。FR-04、FR-05；#8/#18/#22。以下为可实现的隔离测试策略，真实收款门槛尚未满足，见 ADR-0003。

## 约束

个人静态码通常不能携带网关订单号。一个通知中的金额和观察时间不足以唯一证明属于哪笔订单。首版仍以自动充值为目标，但仅对满足明确策略的唯一候选自动处理。

## 候选过滤

必须同时限定：授权 receiver／支付账户、provider、订单原通道、精确金额、订单状态、创建与支付有效期、观察时间可接受范围，以及所属测试／生产环境。通道删除归档或禁用后历史事件的处理需要与现有网关规则一致，不因新单禁用就自动丢弃历史付款。

不能只查未支付订单然后选择第一条；已经支付的相近历史订单可能表明重复通知。结果必须为 matched、unmatched、ambiguous、expired、clock_error、duplicate_suspected 或 unsupported，并记录策略版本、候选范围与原因。

## 严格测试策略 `synthetic-exclusive-v1`

服务端必须同时确认独立测试环境、测试 receiver/商户、fixture 凭据及核准 parser、identity_confidence=distinct、单活跃设备与 binding_version 一致。fixture 不包含订单信息，不从 event_id 或可选交易号编码/推导订单。生产服务不能加载该策略；notification 来源未获资格时保存 needs_review/evidence_insufficient。

金额占用键为 `(environment,receiver_id,provider,amount_minor)`，有永久唯一约束并指向原订单；channel 的不同别名不能为同一实际 receiver 绕过占用。绑定新 generation、换设备、删除／恢复通道均不清空历史。数据库 INSERT 唯一冲突时拒绝新可支付窗口，页面明确“此金额当前不可使用”，不展示二维码、不换金额、不默默选另一通道。已有相同 merchant/out_trade_no 的幂等下单可返回原订单。

支付窗口候选为创建后 **10 分钟**，保存独立不可自动续期的 observer_pay_expires_at；只允许 `created_at <= first_received_at < expires_at`。到期后回访旧收银台显示过期，但不能阻止用户对静态码继续付款，所以历史 claim 永不因 TTL 释放。已付／退款同样不释放。这限制吞吐和金额可重用性，是明确代价；不能作为无限可运营的个人码方案宣传。

首次接收时冻结候选集截止时间（不得匹配之后创建的订单）；只处理观察时已存在的原订单。测试时 `created_at <= observed_at < expires_at`，`-2000 <= first_received_at-observed_at <= 120000 ms`；超界分别 clock_error/stale_observation。系统时钟跳变、恢复扫描、未知身份、历史同额证据均停止自动确认。observed_at 来自设备，不因满足时间窗就变成可信平台时间。

超过 120 秒的离线事件仍可 accepted 并保留，不自动匹配；离线 50 条验收是持久性要求，不保证全部自动充值。回调投递可在订单支付完成后继续重试；支付到期不撤销已提交的账务。网关处理延迟不改 first_received_at。

迟到付款可能在旧单过期后命中新单，因此“只允许一笔待支付订单”不足以解决问题。保护期结束也不意味着旧付款不可能发生。找不到可信区分依据时不得自动匹配，应保留事件以核对。

尾数分配不在 v1；Amount、RealAmount、收银台显示和回调金额保持原订单一致。需要改变金额时必须重新设计 New API 契约并另行评审。

## 数据与并发草图

`amount_claims` 唯一键如上；`observer_events` 以 SPEC-002 的事件键唯一；`observer_consumptions` 分别 UNIQUE(event_pk)、UNIQUE(order_id)，observer evidence 独立于 api_trade_no。receiver 配置锁序先于 claim，再 event、order、merchant；同层多行按 ID 排序，所有结算入口统一 order→merchant 顺序。接收事务不锁订单；匹配和记账在同一结算事务内重新检查条件，不能把预选结果留到事务外直接确认。创建订单与 claim 同一事务，claim 不能在已向用户显示码后丢失。

## 决策反例表

| ID | 条件 | 结果／自动确认 |
| --- | --- | --- |
| MATCH-01 | 合成来源、首次金额、唯一单、绑定/身份/窗口全满足 | matched，自动一次 |
| MATCH-02 | 无候选；之后才创建新单 | unmatched，不等待未来新单 |
| MATCH-03 | 历史导入导致两个同额候选 | ambiguous，不按最近／最早选 |
| MATCH-04 | 两个并发同额下单 | 只允许一个 claim，另一个拒绝支付窗口 |
| MATCH-05 | 旧单过期后同额新单 | 新单 claim 失败，旧付款 needs_review/expired |
| MATCH-06 | 有限冷却期后旧付款到账 | 不解除历史保护；迁移遗留新单也不能匹配 |
| MATCH-07 | 旧事件新封套／离线超过 120 秒 | accepted 后 stale_observation，零自动确认 |
| MATCH-08 | 已付事件以新 event_id/新通知 key 再来 | duplicate_suspected，无第二笔收入 |
| MATCH-09 | 跨 receiver/provider/channel/env | scope_denied 或 unmatched，零串单 |
| MATCH-10 | 时钟异常／恢复扫描／身份冲突 | clock_error 或 needs_review |
| MATCH-11 | 退款单再收到通知 | 不恢复 paid，不重新记账 |
| MATCH-12 | 真实通知、唯一待支付单，但无关联证据 | evidence_insufficient，不能仅因唯一而 matched |

M0 只执行信息不可区分的纯数据反例实验；上述数据库／匹配器／端到端用例均 Not run，留给 #18/#22/#32。

## 禁止行为

- 不根据“最近一单”“最早一单”“同金额就行”自动充值。
- 不把观察器 event_id 冒充支付平台流水来绕过重复校验。
- 不在事件无匹配后持续等待任意未来订单出现并绑定。
- 不把模拟器中的已知 order_id 传给生产匹配器或用其证明匹配正确。

## 验收场景

唯一候选、零候选、两个同额候选、并发下单、跨微信／支付宝、跨 receiver、迟到通知、旧付款晚于新单、重复更新不同 event_id、时钟向前／向后、已支付／已退款订单、未知账户。每个场景必须给出预期状态以及“是否允许自动确认”。
