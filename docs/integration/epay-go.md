# epay-go 集成计划

状态：隔离原型已实现。历史研究基线`4d855060439df247fc60a529139e80c7e4b757c0`保留在[源码审计](../research/M0-source-audit.md)，当前原型功能基线`9a96f72`及最终PR见[M3报告](../testing/M3-prototype-report.md)。

## 当前能力与信任边界

SPEC-014–020提供身份/KEK、专用TLS配对/事件接口、验权/防重放、持久接收和管理员撤销/轮换。SPEC-021/022在明确synthetic环境下提供永久claim、首次候选快照、原子消费/订单/收入与持久匹配退避。SPEC-024提供受限本地TLS回调，SPEC-026提供管理员事件追踪和非追溯暂停。默认构建不能自动确认synthetic/notification证据；生产真实来源未准入。

Android只持有独立设备凭据，不直接确认订单、不查询业务数据库、不持商户API Key。ACK代表持久接收；只有网关消费/原收入及New API topup审计能证明隔离充值结果。没有平台流水时保存NULL，不用event_id伪造。

## 已存在的能力

- `/submit.php`、`/mapi.php`、`/api.php` 易支付兼容入口。
- epay 个人码通道的 wechat_qr、alipay_qr、notify_key 配置。
- `/api/pay/notify/epay/{channel_id}` 验证 EPay MD5，要求订单、金额和流水／凭据字段。
- 支付确认服务事务更新订单、商户余额与流水；商户通知已有数据库 pending/sending 状态、重试时间、租约和恢复 worker，使用商户当前 API Key；不能误写成仅内存 goroutine。

证据：[epay adapter](https://github.com/HuangYincan/epay-go/blob/4d855060439df247fc60a529139e80c7e4b757c0/internal/payment/epay.go)、[order service](https://github.com/HuangYincan/epay-go/blob/4d855060439df247fc60a529139e80c7e4b757c0/internal/service/order.go)、[notify service](https://github.com/HuangYincan/epay-go/blob/4d855060439df247fc60a529139e80c7e4b757c0/internal/service/notify.go)。

## 跨仓库交付与运行

网关实现不复制到Android仓库；跨库Issue映射见[BACKLOG](../issues/BACKLOG.md)。M2由epay-go PR #14–17交付，M3金额/匹配/回调由#18–20及最终联调PR交付；Android对应#50–56及最终PR。版本与CI见最终报告。

旧支付入口保持兼容，无法用于确认observer订单；旧教程#5不适用于events v1。数据库版本化迁移保护历史证据/claim/审计，回退代码不删表/trigger。实际schema5→6升级两次启动保持四笔账务；数据备份和生产灾难恢复未验收。

本机隔离构建、配置、启动和清理见[演示手册](../operations/prototype-demo.md)。设备、管理员、商户及New API会话四类凭据分开；公开文档不含可用凭据。真实异常应暂停并核对独立账单，不能直接改数据库绕过证据约束。
