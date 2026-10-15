# SPEC-001 通知采集与双通道解析

状态：M0 review candidate。#4；FR-01、FR-02、FR-07；依赖 SPEC-006、SPEC-005。

本批 #11/#12 的监听与合成输入已细化为 [SPEC-008](SPEC-008-notification-fixture-listener.md)；合成解析 #13/#14 见 [SPEC-009](SPEC-009-synthetic-receipt-parsing.md)；真实规则仍为空，ReceiptEvent 输出等待 #16。

## 输入与输出

输入为系统投递的通知快照：package、user/profile 范围、notification key、postTime、channelId、flags、title、text、bigText、可用的结构化 extras。具体字段可用性须按 Android 官方文档核对。

当前输出为 `Ignored(reason)`、`Unsupported(reason)` 或无交易身份的 `ParseResult.Candidate`；后续持久身份裁决后才能创建 ReceiptEvent。微信、支付宝解析器相互独立；解析规则有版本与测试样本，未知内容不能套用最相近规则。

真实来源候选包名为 `com.tencent.mm` 和 `com.eg.android.AlipayGphone`，实施时通过样本与安装信息核对；包名相符仅表示应用来源，不足以证明通知是实际收款。工作资料、应用分身、多账号变化必须单独识别或明确不支持。

## 解析要求

- 禁止仅靠“含有收款／到账”及任意金额正则触发事件。
- 来源、通知结构、已验证标题／文案模式和语义必须共同通过；聊天、群摘要、支出、退款、红包、推广、聚合多笔消息默认拒绝自动识别。
- title/text/bigText 可能包含同一金额，也可能表达不同金额；重复表示可规范化，冲突金额必须 unsupported。
- 金额转换为整数分，正数、至多两位小数、固定上限；不能四舍五入接受更多小数。
- observation 时间不是平台实际交易时间；不能因字段名叫 timestamp 就当作可信交易时间。
- 不把通知 key、付款人昵称或 event_id 伪造为 provider_transaction_id。

## 身份与重复

一次观察可以更新同一条通知。身份方案须研究 key／postTime／内容变化的实际行为，先保存稳定本地身份，再入发送队列。单纯哈希通知全文会把同额真实付款合并，单纯随机 UUID 会把更新当新付款，二者都不能直接作为完整方案。

身份规则及 ID-01..11 场景已在 [ADR-0005](../decisions/ADR-0005-notification-identity.md) 定义为评审候选。同 key 更新不产生新的可自动确认事件；不同 key 同文案不按全文 hash 合并；不可区分的输入保留 uncertain。服务端仍须防止重复消费和错误选单。

包名之外检查 opPkg、签名证书、user/profile 和 App 版本；真实签名允许集合未采样，不能从旧 README 编造指纹。extras 按 CharSequence 安全读取，不强转 String；读取异常视为 unsupported。真实规则注册表初始为空，fixture 两套规则单独命名，不把历史文案命名为“真实已支持”。

## 验收

两个来源各有合成正例和负例；每个结果包含可解释原因。模拟包只在测试构建允许，不可在 Release 通过改配置启用。真实文案不得标为“已支持”，直到兼容性表附对应环境与样本证据。
