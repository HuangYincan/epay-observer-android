# epay-go 集成计划

状态：M0 review candidate。研究基线：`HuangYincan/epay-go@4d855060439df247fc60a529139e80c7e4b757c0`，2026-10-15 重新只读核对，工作区干净且未改动。详见 [源码审计](../research/M0-source-audit.md)。

## 当前实现基线（2026-10-16）

研究基线仍保留4d855060，不能把新代码写入历史审计结论。当前main为 `9d326b2`（PR #13已合并，模型功能代码e0580d8完整CI通过），包括协议核心 `7459367`（PR #10）及安全补丁基线 `6097e1f`（PR #12）。pkg/observer提供纯协议校验，internal/observerstore提供身份/凭据元数据、版本化迁移和不可变证据事务原语；尚未挂载HTTP路由，也没有配对、请求重放登记、完整授权接收事务或支付确认。New API固定版本及#10金额缺口不变。

双方实现与证据见 [网关协议核心报告](../testing/M2-gateway-protocol-report.md)；对外接收API未实现，不能把库返回的VerifiedRequest视为ACK。

## 已存在的能力

- `/submit.php`、`/mapi.php`、`/api.php` 易支付兼容入口。
- epay 个人码通道的 wechat_qr、alipay_qr、notify_key 配置。
- `/api/pay/notify/epay/{channel_id}` 验证 EPay MD5，要求订单、金额和流水／凭据字段。
- 支付确认服务事务更新订单、商户余额与流水；商户通知已有数据库 pending/sending 状态、重试时间、租约和恢复 worker，使用商户当前 API Key；不能误写成仅内存 goroutine。

证据：[epay adapter](https://github.com/HuangYincan/epay-go/blob/4d855060439df247fc60a529139e80c7e4b757c0/internal/payment/epay.go)、[order service](https://github.com/HuangYincan/epay-go/blob/4d855060439df247fc60a529139e80c7e4b757c0/internal/service/order.go)、[notify service](https://github.com/HuangYincan/epay-go/blob/4d855060439df247fc60a529139e80c7e4b757c0/internal/service/notify.go)。

## 需要增加的能力

设备与 receiver 绑定模型、签名事件入口、持久事件表、业务幂等／重放防护、账户级订单匹配、自动确认策略、事件与记账共同事务、审计查询及异常核对页面。新接口建议由 SPEC-002 定义，不能要求手机从通知中凭空得知 trade_no。

旧回调保持兼容；新 Observer 路径属于另一信任边界，不直接把设备 key 替换进旧 MD5 回调就宣称已完成。无真实平台流水时需明确 observer evidence 存储方案，避免假造交易流水。

## 跨仓库交付

本仓库的网关 Issues 是协调任务，M0 已检查 epay-go 现有任务并建立对应边界，映射见 [BACKLOG](../issues/BACKLOG.md)。已交付PR #10协议核心与PR #13模型/证据原语；完整接收与联调仍待后续PR，不虚构端到端证据。不得复制网关；数据库迁移、并发及回滚测试在 epay-go，协议向量和端到端报告固定双方提交。

既有 epay-go #5 的旧入口教程不适用于新 events v1：尤其不能将 event_id 作为 api_trade_no，也不能让 APK持有网关订单映射或直接确认支付。原旧入口保持兼容，新接口单独处理 observer evidence。

模型迁移已按SPEC-014验证旧数据保留、DDL回滚、版本漂移及并发；部署仍未执行。既有migrate/启动入口会执行新增DDL，代码回退保留历史表与保护trigger，不自动删除证据。事件本身不可信时不能通过强行写数据库来绕过审核。
