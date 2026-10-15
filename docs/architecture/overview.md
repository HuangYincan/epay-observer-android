# 系统架构

状态：Draft。组件均为计划，现有能力在 [epay-go 集成现状](../integration/epay-go.md) 单独列出。

## 模块与责任

| 模块 | 责任 | 禁止承担 |
| --- | --- | --- |
| Android Listener | 接收允许来源通知、提取观察快照 | 网络请求阻塞系统回调、确认订单 |
| Provider Parsers | 按微信／支付宝规则识别到账候选 | 任意通知全局搜金额后自动认可 |
| Event Store / Uploader | 稳定身份、持久队列、认证、重试 | 重新生成事件身份、持有商户 API Key |
| Gateway Observer API | 验证设备、持久事件、返回接收状态 | 未落盘即 ACK、仅凭签名直接入账 |
| Matcher | 基于收款绑定、金额、时间、状态与冲突策略决定候选 | 模糊匹配最近一单、跨账户选单 |
| Credit Service | 原子消费事件、订单／余额／流水变化 | 先记账后单独写去重标志 |
| Merchant Notify | 以原商户凭据向 New API 重试通知 | 重试时重复增加网关收入 |
| Review / Diagnostics | 展示原因、可核对记录、脱敏日志 | 未核实账单直接把通知当支付凭证 |

## 数据边界

本仓库以后放 Android 主应用、测试夹具与协议向量。网关事件表、设备表、匹配逻辑及后台页面放在 epay-go。共享契约放在本仓库 SPEC-002，并在双方 PR 固定版本。

数据库事务中完成事件消费与现有订单记账；需重构现有支付确认服务以允许复用同一事务，而不是在外层调用一个已经独立提交的方法。事件 ACK 可以先于业务处理完成，但必须有可靠待处理任务，并通过独立业务状态追踪。

## 状态流

`observed → parsed → queued → accepted → matched → credited → merchant_notified` 为正常路径；各层存在 `ignored`、`unsupported`、`needs_review`、`retryable_error`、`rejected`。不能用单个 success 字段抹掉区别。

## 第一阶段环境

AVD + 独立通知夹具 App + 本地测试 epay-go + 测试 PostgreSQL + New API 测试实例。夹具可产生可控通知，不冒用真实应用包签名；测试构建只信任指定测试包，Release 必须剔除该信任入口。

生产域名、真实密钥、实际收款码和真机设备均不在此阶段接入。
