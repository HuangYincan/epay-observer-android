# 文档索引
- [SPEC-046：既有EPay商户接口与通知准入分层](specs/SPEC-046-existing-epay-merchant-contract.md)：New API保持原样，严格v2交接，ACK与实际增额分开；[验证](testing/existing-epay-contract.md)。

- [SPEC-045：网关schema升级与兼容回退](specs/SPEC-045-gateway-schema-upgrade.md)及[实际休眠升级](testing/gateway-schema-upgrade.md)：schema8/零rule/pilot，既有签名health通过；真实自动充值仍未通过。
- [SPEC-044：New API 实际版本回调契约私有修复](specs/SPEC-044-newapi-live-contract-backport.md)及[验证/部署范围](testing/newapi-live-contract-backport.md)：独立AGPL候选，缓存模式写前拒绝，未修改线上。
- [SPEC-043：本地试验operator与Android交接](specs/SPEC-043-pilot-operator-handoff.md)及[验证](testing/pilot-operator-handoff.md)：补实际操作入口，不自动批准规则/政策，不上线。

- [SPEC-042：有限收款/聊天对照](specs/SPEC-042-receipt-chat-comparison.md)与[准备报告](testing/receipt-chat-comparison.md)：用户本地开始、最多现存1条+posted1条，不付款、不自动填规则核验位。

- [SPEC-041：有界真实通知联调接线](specs/SPEC-041-bounded-notification-pilot.md)（#82 / epay-go #26）：补齐默认停止的debug接线与只读准入检查；[验证报告](testing/bounded-notification-pilot.md)区分真实核验与启用。

- [SPEC-040：付款反馈后的现存通知检查](specs/SPEC-040-post-payment-template-snapshot.md)与[实际结果](testing/post-payment-template-snapshot.md)：COMPLETE/2，清理与全监听集合恢复分开记录；保留SPEC-039实际0回调及全部旧标记，不续窗或补确认订单。

- [SPEC-039：用户本地开始的新模板窗口](specs/SPEC-039-fresh-receipt-template-window.md)与[准备状态](testing/fresh-receipt-template-preparation.md)：最多120秒/2条、明确不充值；准备/刷新不采样。

- [SPEC-038：已标注扫码收款样本的本机字段核验](specs/SPEC-038-alipay-receipt-field-roles.md)（#82/#84）：用户确认实际扫码来源；[字段核验工具与实际0条结果](testing/alipay-receipt-field-roles.md)，不升级历史订单。

- [SPEC-036：真实付款单订单闭环](specs/SPEC-036-real-payment-pilot.md)（#82 / epay-go #24）：真实notification事务结算能力与实际启用分别验收；模板和真实增额证据仍缺。
- [真实付款闭环开发进度](testing/real-payment-pilot-progress.md)：网关隔离验证已完成，真实收款模板和实际充值仍缺证据。
- [现存两条通知检查结果](testing/alipay-active-snapshot.md)与[SPEC-037](specs/SPEC-037-active-notification-snapshot.md)：追加类型核验已完成并清理；固定前缀有证据，括号/正文和聊天伪装边界仍未知，真实充值未验收。

更新日期：2026-10-16。M1采集、M2可靠认证上报和M3双渠道模拟器合成充值已验证；[最终原型报告](testing/M3-prototype-report.md)、[演示手册](operations/prototype-demo.md)和[验收账本](development/PROTOTYPE-GOAL.md)为当前状态。各阶段历史报告只表示当时范围；New API本地金额补丁已验证，#8真实收款准入仍阻塞。

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
- [构建与模拟器开发指南](development/build.md)
- [首批工程基线验证报告](testing/M1-foundation-report.md)
- [通知采集验证报告](testing/M1-capture-report.md)
- [双通道解析报告](testing/M1-parsing-report.md) 与 [116 例合成语料](testing/parsing-v1/README.md)

- [持久身份报告](testing/M1-identity-report.md) 与 [队列恢复／迁移报告](testing/M2-outbox-report.md)

- [签名／退避核心报告](testing/M2-signing-core-report.md)、[网关纯协议报告](testing/M2-gateway-protocol-report.md)；SPEC-012/013历史子批次，后续实际上传见SPEC-017。

- [身份模型与KeyStore验证](testing/M2-identity-vault-report.md)：[SPEC-014](specs/SPEC-014-gateway-identity-model.md)、[SPEC-015](specs/SPEC-015-pairing-and-key-vault.md)

## 架构决策

- [ADR-0001：设备只上报事件](decisions/ADR-0001-event-only-client.md)
- [ADR-0002：先用模拟器，独立记录证据层次](decisions/ADR-0002-emulator-first.md)
- [ADR-0003：自动充值与同金额冲突策略](decisions/ADR-0003-auto-credit-policy.md)
- [ADR-0004：技术栈及仓库分工](decisions/ADR-0004-stack-and-repos.md)
- [ADR-0005：通知观察身份与事件身份](decisions/ADR-0005-notification-identity.md)

规格中的 MUST 表示待实现的验收要求，Proposed 表示设计建议，TBD 表示尚未决定。用户已确认双通道、自动充值、模拟器优先、公开仓库、规划先行，以及本仓库采用 MIT；允许参考旧 V免签系列业务流程，旧代码仅供研究。

- [ADR-0006：稳定receiver与证据历史](decisions/ADR-0006-stable-receiver-and-evidence-history.md)

- [SPEC-016：实际配对HTTP与恢复](specs/SPEC-016-pairing-http.md)：#17/#25跨库实现，激活、撤销、轮换与本地受保护日志；验收逐项跟踪。

- [实际配对与恢复报告](testing/M2-pairing-report.md)、[长期原型验收账本](development/PROTOTYPE-GOAL.md)。

- [SPEC-017：事务接收、实际上传与ACK](specs/SPEC-017-receive-and-upload.md)、[上传验证报告](testing/M2-receive-upload-report.md)。

- [SPEC-018后台调度](specs/SPEC-018-background-delivery.md)、[后台恢复报告](testing/M2-background-report.md)：38+8主套件/进程阶段及5类实际隔离恢复；fixture网络条件与production边界单独记录。

- [SPEC-019状态/诊断](specs/SPEC-019-status-and-diagnostics.md)、[SPEC-020认证健康检查](specs/SPEC-020-authenticated-health.md)、[验证报告](testing/M2-status-health-report.md)。

- [SPEC-021合成永久金额占用](specs/SPEC-021-synthetic-order-claims.md)、[M3占用子批次报告](testing/M3-amount-claims-report.md)：真实PostgreSQL并发及默认构建拒绝，尚无匹配/记账。

- [SPEC-022合成匹配/原子入账](specs/SPEC-022-synthetic-matching-credit.md)、[M3事务验证报告](testing/M3-matching-credit-report.md)：首次候选快照、真实子进程恢复及TLS重复ACK；后续New API闭环见最终原型报告。

- [SPEC-023 New API金额校验](specs/SPEC-023-new-api-callback-amount.md)
- [SPEC-024 受限本地回调](specs/SPEC-024-local-synthetic-callback.md)
- [M3回调基础验证](testing/M3-callback-foundation-report.md)

- [SPEC-025合成资格](specs/SPEC-025-synthetic-evidence-identity.md)与[SPEC-026管理追踪/暂停](specs/SPEC-026-admin-trace-pause.md)。

## 人工验收与M4准备

- [支付宝本地通知计数验证](testing/alipay-delivery-probe.md)与[SPEC-028](specs/SPEC-028-local-notification-probe.md)：已确认支付宝登录，用户反馈真实来源计数增加；自动化结果及局限见[支付宝自动验收](testing/alipay-probe-automation.md)。
- [支付宝探针接管检查与下一批范围](testing/alipay-probe-takeover.md)：2026-10-17只读核对基线、当前环境和四笔账务；真实触发端/keyguard门槛单列，不重复已完成矩阵。
- [支付宝普通消息单窗口驱动与实际尝试](testing/alipay-live-message-window.md)：保留三次聊天协调窗口的零结果和时间限制，不追溯升级。
- [支付宝本地开始控制页](testing/alipay-local-start-control.md)：用户在本地页点击才开窗，同页显示倒计时/计数；单会话仅一次，仍不读取正文或自动重开。宿主/页面验证与实际投递证据分开记录。
- [支付宝本地窗口实测](testing/alipay-local-delivery-result.md)：首次账号退出时0回调；用户重新确认登录后，单窗口0→2次真实来源回调、恢复/四笔账务通过。仅普通场景回调可达，不证明真实支付兼容性。
- [真实App来源与环境预检](research/M4-real-app-d0.md)：#33，官方产物核验、安装与首次启动已完成；支付宝已由用户确认登录，微信登录/真实通知仍待验证。
- [手动打开App、授权、轮换配对与查看状态](testing/manual-acceptance.md)
- [真机双渠道计划与准入门槛](testing/real-device-plan.md)（计划，实测Not run）
- [签名、产物与发布检查表](operations/release-checklist.md)（规划，未发布）
- [SPEC-027人工验收准备](specs/SPEC-027-manual-acceptance-readiness.md)

- [人工配对时钟漂移与正常恢复报告](testing/manual-clock-recovery-report.md)（#59）。
- [既有网关 HTTPS 入口预检规格](specs/SPEC-029-gateway-connection-preflight.md)（#69）：仅匿名认证入口与实际 Android TLS，不证明真实收款或充值。
- [既有网关连接报告](testing/gateway-connection-report.md)与[接入步骤](operations/existing-gateway-connection.md)：实际 APK 认证入口已通；指定收款账户、配对、正文和归属分别验收。
- [SPEC-030 持久设备配对](specs/SPEC-030-production-device-pairing.md)与[实测报告](testing/production-pairing-report.md)：用户指定支付宝已配对，新进程签名健康通过；生产采集/匹配未开启。
- [SPEC-031 一次性私有内容采样](specs/SPEC-031-private-content-sample.md)与[采样报告](testing/alipay-private-content-sample.md)：独立无网络debug工具，用户仅授权一次120秒标题/正文窗口，脱敏后删除原文；不重复既有验收，不等同真实收款验证。
- [SPEC-032 本地开始扫码采样](specs/SPEC-032-user-started-scan-sample.md)与[准备报告](testing/alipay-scan-sample.md)：用户准备好后本地点开始，新固定单次标记保留旧历史；准备不采样，当前不承诺真实自动充值。

- [SPEC-033 notification候选诊断](specs/SPEC-033-notification-review.md)（Android #77 / epay-go #22）：首次认证接收即保存不可入账的候选检查与needs_review，不等同真实解析或自动充值。
- [notification首次接收诊断验证](testing/notification-review-report.md)：独立PostgreSQL/签名TLS合成输入验证，不代表真实收款解析或充值。
- [SPEC-034未付款后的单次扫码采样](specs/SPEC-034-unpaid-scan-retry.md)与[准备报告](testing/alipay-unpaid-scan-retry.md)：旧标记保留，仅新增固定slot，用户本地明确同意并点击才开窗。
