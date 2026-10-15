# 文档索引

更新日期：2026-10-15。M0 研究已形成评审稿，Spec 为 **M0 review candidate / 待实现**；不代表全部决策已批准。先读 [M0 汇报](research/M0-review.md)，再查各规格。#8 的真实收款准入与 #10 的 New API 金额契约仍开放。

## 建议阅读顺序

1. [PRD：需求与成功标准](product/PRD.md)
2. [用户流程](product/user-flows.md) 与 [术语](DOMAIN.md)
3. [系统架构](architecture/overview.md)
4. [采集](specs/SPEC-001-notification-capture.md)、[上报协议](specs/SPEC-002-event-protocol.md)、[匹配](specs/SPEC-003-order-matching.md)、[自动入账](specs/SPEC-004-auto-credit.md)
5. [设备安全](specs/SPEC-005-device-security.md) 与 [后台可靠性](specs/SPEC-006-android-reliability.md)
6. [模拟器验收](testing/emulator-plan.md) 与 [验收矩阵](testing/acceptance-matrix.md)
7. [路线图](ROADMAP.md)、[任务索引](issues/BACKLOG.md)、[待决问题](OPEN-QUESTIONS.md)

## 集成与运行

- [epay-go 集成边界](integration/epay-go.md)
- [New API 对接与回调](integration/new-api.md)
- [兼容性记录模板](testing/compatibility-matrix.md)
- [运行与故障处置规划](operations/runbook.md)
- [参考资料与许可证核查](research/references.md)
- [固定源码与许可证审计](research/M0-source-audit.md)
- [威胁—防护—残余风险](research/M0-threat-model.md)
- [协议黄金向量与复现命令](testing/protocol-v1/README.md)

## 架构决策

- [ADR-0001：设备只上报事件](decisions/ADR-0001-event-only-client.md)
- [ADR-0002：先用模拟器，独立记录证据层次](decisions/ADR-0002-emulator-first.md)
- [ADR-0003：自动充值与同金额冲突策略](decisions/ADR-0003-auto-credit-policy.md)
- [ADR-0004：技术栈及仓库分工](decisions/ADR-0004-stack-and-repos.md)
- [ADR-0005：通知观察身份与事件身份](decisions/ADR-0005-notification-identity.md)

规格中的 MUST 表示待实现的验收要求，Proposed 表示设计建议，TBD 表示尚未决定。用户已确认双通道、自动充值、模拟器优先、公开仓库、规划先行，以及本仓库采用 MIT；允许参考旧 V免签系列业务流程，旧代码仅供研究。
