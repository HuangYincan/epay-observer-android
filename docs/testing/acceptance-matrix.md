# 需求—规格—验收矩阵

当前验收范围为模拟器合成通知原型；[最终报告](M3-prototype-report.md)区分AVD、真实TLS与PostgreSQL证据。真实App/真机/生产准入Not run。

| 需求 | 规格 | 当前证据 |
| --- | --- | --- |
| FR-01双通道 | SPEC-008/009/025 | 116例语料、独立App通知及实际两渠道充值 |
| FR-02可观测 | SPEC-019/020 | 权限/服务/队列/ACK分层及独立health；不声称Android ACK表示支付 |
| FR-03可靠上报 | SPEC-010–018 | 50条Room积压、实际HTTPS/ACK丢失、进程与AVD恢复 |
| FR-04自动充值 | SPEC-021–025 | 普通订单、不含订单提示、双渠道New API一次增额 |
| FR-05幂等不串单 | SPEC-021–024 | PostgreSQL同额/迟到/并发/事务恢复，实际TLS并发回调及SIGKILL |
| FR-06可解释 | SPEC-026 | 管理HTTPS查询及逐笔跨库audit；暂停原因/候选/订单/通知/审计 |
| FR-07模拟器 | SPEC-007/024 | 干净复现、固定环境、Release/默认/显式CA检查 |
| FR-08设备安全 | SPEC-015–020 | KeyStore、实际激活/撤销/轮换、越权/重放/篡改、UNBOUND保持 |

以下是各历史阶段的独立证据，不覆盖其后续实现。

## M1 已实现子集

- SPEC-008 / CAP-01..05：通知输入与监听，见 [采集报告](M1-capture-report.md)。
- SPEC-009 / PARSE-01..05：两组合成解析、116 例正负语料、开关与 AVD 实际投递，见 [解析报告](M1-parsing-report.md)。
- SPEC-010 / PERSIST-01..05：持久身份，见 [身份报告](M1-identity-report.md)。
- SPEC-011 / QUEUE-01..05：50条离线候选、跨进程恢复、schema升级与租约状态机，见 [队列报告](M2-outbox-report.md)。ACK为本地存储契约输入，网络发送未实现。
- SPEC-012 / SIGN-01..02、RETRY-01：实际Kotlin/Android JCA签名与纯退避决策，见[核心报告](M2-signing-core-report.md)；HTTP/配对仍未实现。
- SPEC-014 / MODEL-01..04、SPEC-015 / VAULT-01..03：真实PostgreSQL模型/迁移和AndroidKeyStore，见[报告](M2-identity-vault-report.md)；不等于HTTP配对/授权/充值验证。
- 历史子批次不单独证明完整需求；后续网络/充值证据见上表，真实App证据仍缺失。

## M0 研究证据（2026-10-15）

| 项目 | 范围 | 结果 |
| --- | --- | --- |
| 协议签名字节 | 3 组 Python/JCA golden，12 项密码学篡改检查 | Passed，纯离线，不含 HTTP/Android/Go/数据库 |
| 信息不足反例 | 相同观察的不同交易事实、4 种有限冷却期 | 5/5 反例成立，不是匹配器测试 |
| 新旧源码与许可 | 固定 SHA、MIT/内嵌 Apache-2.0、现有事务/回调 worker | 静态审计完成，运行 Not run |
| New API NEW-02 | 合法签名但金额不符不得增额 | Blocked：固定源码没有业务金额比较，运行 Not run |
| 真实来源资格 | 文案、签名证书、账户/订单关联 | 未验证，不能开启生产自动确认 |

复现与限制见 [M0 报告](../research/M0-review.md)、[黄金向量](protocol-v1/README.md)。M3的NEW/MATCH及网络安全按后续SPEC-021–026和最终报告落实。公网NET-01端到端不属于本地授权；本地TLS正例由显式受限适配器验证，默认safehttp负例和DNS边界由Go测试验证，不宣称公网部署通过。

## 资金正确性必须额外检查

数据库真实并发事务与故障恢复测试，不可仅靠 mock。检查事件唯一约束、锁顺序、订单状态、余额、流水数量，以及 New API 原商户订单幂等结果。发生故障后不能留下“事件已消费但未入账”或“已入账但事件可再次消费”。

## 准入门槛

M3 允许声明“模拟器测试自动充值闭环完成”，前提是上述相关测试全通过且没有未解释账务变化。M4 需要真实收款设备与两个真实 App 的单独证据；未完成前不得声明“真实微信／支付宝收款已验证”。
