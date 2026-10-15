# SPEC-002 观察器事件协议

状态：Draft v0；**尚未实现、不得按本文件调用现有服务器**。关联 FR-03、FR-06、FR-08。

## 路由与字段（Proposed）

新事件入口建议为 `POST /api/observer/v1/events`；连通与健康入口建议为 `POST /api/observer/v1/heartbeat`。路径最终由协议 Issue 定稿，不能和现有 `/api/pay/notify/epay/{channel_id}` 混淆。

```json
{
  "schema_version": 1,
  "event_id": "persisted-event-identifier",
  "device_id": "bound-device-identifier",
  "receiver_id": "gateway-receiver-identifier",
  "provider": "wxpay",
  "amount_minor": 1001,
  "currency": "CNY",
  "observed_at_ms": 1792022400000,
  "parser_version": "fixture-v1",
  "source_class": "synthetic"
}
```

示例全为合成数据。`provider` 只允许 wxpay／alipay；金额为整数分，范围 1..999999999999，实施时与网关字段容量核对。不得提交 order_id／trade_no 作为生产匹配提示，模拟闭环也不允许靠订单提示绕过匹配。provider_transaction_id 缺失时应保持缺失。

source_class 不是客户端自行声明就生效的权限：服务端按凭据所属环境及构建绑定决定真实／测试信任域。receiver_id 必须属于已认证设备的授权范围，不能信任 JSON 中任意值。

## 认证封套（Proposed）

设备凭据独立于商户 API Key 和旧个人码回调密钥。请求头包含 key_id、发送时间、request_id、协议版本和签名。HMAC-SHA256 的待签字节为 UTF-8：

```text
epay-observer-v1\n
HTTP_METHOD\n
EXACT_PATH\n
KEY_ID\n
SENT_AT_MS\n
REQUEST_ID\n
SHA256_HEX_OF_EXACT_BODY_BYTES
```

这里每个 `\n` 表示单个 LF 字节，不额外添加空白／尾部换行；方法大写、body hash 与签名采用小写十六进制。路径不得附查询参数，禁止重定向；客户端序列化一次并签署实际发送的 body，不各自重新序列化 JSON。协议实现前必须冻结跨语言黄金向量，消除示例排版歧义。

初始建议允许发送时间偏差 ±5 分钟；不同重试生成新 request_id 和发送时间，event_id 不变。事件时间有效性单独按匹配策略判断，不能因网络封套新鲜就接受旧到账。具体窗口属于待评审参数。

## 幂等与 ACK

- 数据库唯一键至少包含授权 receiver 范围和 event_id；内容不一致的同身份请求返回冲突，不覆盖原事件。
- request_id 用于传输重放检测，不替代业务幂等键。请求重放处理不得导致再次记账。
- 持久接收成功才返回 ACK；返回 accepted／duplicate 及服务端 event 状态，不把 accepted 当作 credited。
- 重复事件若内容一致，返回已有接收结果；客户端可结束发送重试，业务结果由网关继续追踪。
- 400 字段错误、401 鉴权／时间错误、403 授权撤销、409 身份内容冲突为需诊断的终态；429 与 5xx／网络异常按策略重试，遵守 Retry-After。401 在明确时钟问题修复或重新配对前不进行无限重试。
- HTTP 最大 body 建议 16 KiB；拒绝重复 JSON 键、未知关键版本、溢出金额和非 CNY。服务端限流按设备与 IP 设计。

## 联调要求

黄金向量包括中文 UTF-8、body 篡改、路径篡改、设备越权、轮换撤销、过期请求和同 event 不同 payload。两端协议版本必须在报告中固定。
