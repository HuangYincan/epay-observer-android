# SPEC-002 观察器事件协议 v1 候选

状态：M0 review candidate（`observer-events-v1-rc1`）；#7、FR-03/06/08。本轮实际签名/严格解析核心按SPEC-012/013实施验证；仍未实现接收API/授权/重放事务，不能据此调用现有服务器或宣称生产协议准入。

## 路由与 JSON

`POST /api/observer/v1/events`，HTTPS，无 query、尾斜线、路径编码变体或 redirect。Content-Type 为 application/json（可带 charset=utf-8）。body 为 UTF-8 JSON object，无 BOM、无压缩，最大 16384 字节。拒绝重复属性、未知字段、尾随垃圾、无效 UTF-8、孤立 surrogate；字段名区分大小写。心跳／配对另行定义，不包含在 events v1。

| 字段 | 要求 |
| --- | --- |
| schema_version | JSON 整数字面量 1 |
| event_id | 小写标准 UUIDv4；首次持久化分配，重试不变 |
| device_id / receiver_id | 1..64 位 ASCII `[A-Za-z0-9_-]`，与 key 授权记录一致 |
| binding_version | 正整数 1..2147483647，绑定 generation；换账号／设备不得沿用旧绑定 |
| provider | wxpay / alipay |
| amount_minor | 正整数字面量 1..999999999999，单位分；拒绝浮点、指数、字符串、负零；receiver 另有限额 |
| currency | CNY |
| observed_at_ms | 0..9007199254740991 整数，首次本地观察时间，非支付时间 |
| parser_version | 1..64 位 ASCII `[A-Za-z0-9._-]`，服务端规则注册表核准 |
| source_class | synthetic / notification；服务端环境与凭据共同核对，不是客户端自授权 |
| identity_confidence | distinct / uncertain；服务端可降级，distinct 不代表平台支付证明 |
| provider_transaction_id | 可选，1..128 个 Unicode 标量值、无控制字符，仅真实取得才提交；缺失省略，拒绝 null/空串/event_id 冒充 |

其余字段均必需。不接受 order_id/trade_no、付款人、title/text、通知 key 或调试选单提示。通道由 receiver 绑定取得。向量均合成，中文测试流水值不是真实交易。

## 认证封套

以下为单值 header，重复拒绝：

| Header | 格式 |
| --- | --- |
| X-Observer-Version | 1 |
| X-Observer-Key-Id | 1..64 位 ASCII `[A-Za-z0-9_-]` |
| X-Observer-Sent-At | 无前导零的十进制毫秒 Unix 时间，范围同 observed_at_ms |
| X-Observer-Request-Id | 小写标准 UUIDv4，每次 HTTP 尝试生成 |
| X-Observer-Signature | 64 位小写 hex HMAC-SHA256 |

设备 key 为随机 32 字节。向量 key_hex 需解码为字节，不能拿 hex 字符串当 key。不同服务／环境必须使用不同 key；按服务端 key 记录确定 device/env/receiver。

待签内容是以下数组以 **单个 LF（0x0a）** 连接后 UTF-8 编码；无 CR、无末尾 LF：

```text
["epay-observer-v1", "POST", "/api/observer/v1/events",
 KEY_ID, SENT_AT_DECIMAL, REQUEST_ID, LOWER_HEX_SHA256(EXACT_BODY_BYTES)]
```

客户端 JSON 只序列化一次，将 body 原字节落盘。服务端验证路由/封套，hash 实收 body，再严格解析；不能重新序列化后验签。签名用常量时间比较。Header 名大小写不敏感，值按格式验证，不自行 trim/改写。代理不得改 body 或重写应用所见路径。

## 时间、重放、幂等

- `abs(received_at - sent_at) <= 300000 ms`，含边界。封套新鲜不代表事件新鲜；事件时间单独服从 SPEC-003。
- 请求唯一键 `(key_id,request_id)`，存封套摘要与接收结果。接收事务同时登记请求、事件和待处理状态；落盘失败全部回滚，不 ACK；重放防护不能依赖单机 cache。
- 同请求相同封套：仍先校验凭据有效性、时间和授权，再返回已有 duplicate。相同请求但封套不同：409 request_conflict。重试正常使用新 request_id/sent_at。
- replay 记录保留到 sent_at+300001ms 之后且不少于首次接收后 10 分钟；时钟倒退暂停接收或延长保留，防止清理后旧请求重新进入窗口。
- 事件唯一键 `(environment,device_id,event_id)`，轮换 key 不改变范围。同身份 exact body SHA-256 不同即 409 event_conflict；连 JSON 空白或顺序变化也算冲突，这是有意的不可变字节契约。不能换 receiver/binding 重发旧 event。
- provider_transaction_id 仅是证据；经可信来源确认后才使用 `(receiver,provider,provider_transaction_id)` 交易唯一约束。任意非空字符串不能绕过匹配。

## ACK 与错误

首次持久接收 202，重复 200，JSON 示例：

```json
{"schema_version":1,"event_id":"11111111-1111-4111-8111-111111111111","receipt_status":"accepted","processing_status":"pending","received_at_ms":1792022400000}
```

receipt_status 为 accepted/duplicate，processing_status 为 pending/needs_review/credited。具体匹配原因及 merchant notify 状态分开追踪，credited 仍不等于 New API 已充值。客户端核对版本、event_id、receipt_status；空体/HTML 的 2xx 不是 ACK。ACK 结束发送重试，身份墓碑与诊断仍保留。

| HTTP / code | 客户端动作 |
| --- | --- |
| 400 invalid_payload / unsupported_version | 隔离事件，修复规则，不无限重试 |
| 401 invalid_signature / clock_skew / unknown_key | 暂停该凭据上传，修复后用新封套恢复 |
| 403 revoked / scope_denied / binding_changed / environment_denied | 暂停并保留事件，不改身份绕过 |
| 409 event_conflict / request_conflict | 隔离冲突并告警，不覆盖旧值 |
| 413 body_too_large / 415 unsupported_media_type | 终态诊断，不拆成任意收款事件 |
| 429 rate_limited | 遵守 Retry-After，退避+jitter |
| 5xx / timeout / 无效 ACK | 原 body/event_id，新 request_id/sent_at 重试 |

错误 JSON 至少 `{"schema_version":1,"code":"scope_denied"}`，不回显通知、key 或签名。候选退避初始 10 秒、最大 1 小时、全抖动；Retry-After 接受秒数或 HTTP 日期。候选限速每设备 60 请求/分钟、突发 20，恢复积压不改变事件时间。

## 验证资产

[黄金向量](../testing/protocol-v1/vectors.json) 包含 exact body base64、hash、签名输入 base64、HMAC、mutation 与预期。Python/JCA 纯签名实验见 [M0 报告](../research/M0-review.md)。它不等同 Android Keystore/Kotlin 上传端或 Go 服务端测试；数据库与跨端执行留给 #20/#25，不能把摘要一致写成完整协议通过。
