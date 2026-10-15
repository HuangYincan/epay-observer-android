# epay-go 集成计划

状态：Draft。研究基线：`HuangYincan/epay-go@4d855060439df247fc60a529139e80c7e4b757c0`，本地只读检查于 2026-10-15。后续实施前重新核对上游变更。

## 已存在的能力

- `/submit.php`、`/mapi.php`、`/api.php` 易支付兼容入口。
- epay 个人码通道的 wechat_qr、alipay_qr、notify_key 配置。
- `/api/pay/notify/epay/{channel_id}` 验证 EPay MD5，要求订单、金额和流水／凭据字段。
- 支付确认服务使用事务更新订单、商户余额与流水；商户通知采用商户 API Key 签名并重试。

证据：[epay adapter](https://github.com/HuangYincan/epay-go/blob/4d855060439df247fc60a529139e80c7e4b757c0/internal/payment/epay.go)、[order service](https://github.com/HuangYincan/epay-go/blob/4d855060439df247fc60a529139e80c7e4b757c0/internal/service/order.go)、[notify service](https://github.com/HuangYincan/epay-go/blob/4d855060439df247fc60a529139e80c7e4b757c0/internal/service/notify.go)。

## 需要增加的能力

设备与 receiver 绑定模型、签名事件入口、持久事件表、业务幂等／重放防护、账户级订单匹配、自动确认策略、事件与记账共同事务、审计查询及异常核对页面。新接口建议由 SPEC-002 定义，不能要求手机从通知中凭空得知 trade_no。

旧回调保持兼容；新 Observer 路径属于另一信任边界，不直接把设备 key 替换进旧 MD5 回调就宣称已完成。无真实平台流水时需明确 observer evidence 存储方案，避免假造交易流水。

## 跨仓库交付

本仓库的网关 Issue 是协调任务，实施时在 epay-go 创建对应 Issue／PR 并回链。不得为了本仓库“可运行”复制整个网关。数据库迁移、并发及回滚测试在 epay-go 执行；协议向量和端到端报告双方固定提交版本。

新增模型／索引的部署、回滚与旧数据行为需要专门任务。事件本身不可信时不能通过强行写数据库来绕过审核。
