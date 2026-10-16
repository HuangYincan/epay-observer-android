# 领域术语与不变量

| 术语 | 含义 |
| --- | --- |
| NotificationObservation | Android 实际投递的一次通知观察，可包含更新和摘要；不是交易 |
| ParseResult.Candidate | 仅文案语义与金额匹配规则的候选；当前只支持 fixture，身份始终 uncertain，没有事件 ID 或支付资格 |
| ReceiptEvent | 解析器认可的到账候选，包含金额、来源、观察时间与本地稳定身份 |
| event_id | 同一设备持久化的逻辑事件标识；网络重试不得重建 |
| request_id | 一次传输尝试的唯一标识；不同重试使用不同 request_id |
| provider_transaction_id | 支付平台交易流水；只有真实取得时才存在，不能用 event_id 冒充 |
| receiver_id | 网关登记的稳定收款账户；不等于商户、设备或一次授权绑定。规范词汇见 [CONTEXT.md](../CONTEXT.md) |
| device_id | 网关登记的观察器实例；重装需重新绑定，不能接管旧身份 |
| binding_version | receiver 授权绑定代次；换账号/设备不能沿用旧事件授权 |
| identity_confidence | 候选观察身份的 distinct/uncertain 分类，不是平台交易真实性 |
| frozen body | ReceiptEvent 首次落盘的不可变发送字节；重试只重建认证封套 |
| amount claim | receiver/provider/金额到原订单的持久占用；测试 v1 不按 TTL 释放 |
| observer evidence | 通知观察型确认依据，独立于真实 provider_transaction_id |
| MatchingDecision | 事件到订单的唯一匹配、无匹配或歧义结果 |
| CreditConfirmation | 网关在事务内完成支付状态、余额、流水与事件消费 |
| MerchantNotification | 网关向 New API 发送易支付结果；和 APK 上报是不同方向 |
| SyntheticFixture | 测试通知，不是支付凭证，只能进入隔离测试信任域 |

## 不变量

- 一笔订单至多完成一次收入记账；一个 ReceiptEvent 至多消费一次。
- 一个事件不能归属多个订单；订单已有不同凭据时不得覆盖。
- 接收端的传输 ACK 不表示已经到账确认或已经为 New API 充值。
- 金额使用整数分／精确十进制，不能使用浮点；微信与支付宝各自绑定账户范围。
- 设备身份、支付账户身份和商户身份必须区分，不能共用密钥。
- 通知身份去重不能替代交易去重；缺少真实流水号时必须承认限制并执行保守策略。
- 测试凭据、测试事件、测试回调目标不能被生产信任。
