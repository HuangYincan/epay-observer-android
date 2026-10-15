# M0 源码、许可证与集成审计

日期：2026-10-15。范围：#2、#10；静态源码审查，无 APK 安装、服务部署或真实付款。源码仅在临时目录读取，没有将第三方实现复制进本仓库。

## 固定来源

| 项目 | 固定版本 | 读取内容 |
| --- | --- | --- |
| 观察器规划 | `d432e576d5e79f4a0074c0de669f0dc0282ecd18` | README、全部 specs/decisions/integration、PRD、验收、42 个 Issues |
| epay-go | `4d855060439df247fc60a529139e80c7e4b757c0` | payment/epay、service/order、service/notify、repository/order、model/order、safehttp、server 启动 |
| szvone/vmqApk | `2867df10fedc26f9239054b9e34a83d3b0bf30dc` | LICENSE、README、NeNotificationService2、MainActivity、Manifest、Gradle、内嵌 ZXing 文件头 |
| QuantumNous/new-api | `v1.0.0-rc.44` → `633b4c5b190dc1af99a2814391354d86948e7943` | controller/topup、model/topup、payment_webhook_availability、router/api-router、go.mod 与相关测试源码 |
| Calcium-Ion/go-epay | `v0.0.5`（New API go.mod 固定） | epay/order、signature tests；验签与回调映射 |

New API 该 release 发布于 2026-10-12；此处固定为研究基线，不承诺 RC 适合生产，也不使用浮动 latest 部署。测试源码已阅读不等于测试已执行。

## vmqApk：借鉴架构，不复用实现

[源码树](https://github.com/szvone/vmqApk/tree/2867df10fedc26f9239054b9e34a83d3b0bf30dc)。GitHub release v1.8.1 的 APK 更新时间为 2019-12-20，未下载、反编译或运行 APK；不能证明它与当前源码可重复对应。

`NeNotificationService2.java` 使用 NotificationListenerService，读取包名和 title/text。微信按少数标题匹配后取文案最后一个数字片段；支付宝按包含指定文字匹配。金额转 double；每次通知直接 HTTP GET `/appPush`，MD5 拼接签名；凭据来自 SharedPreferences；日志打印通知与请求；心跳无限循环并持有 WakeLock。未见持久事件队列、交易身份、可靠 ACK 或完整重试。旧工程为 compile/target 26、min 19、AGP 2.3.2、Gradle wrapper 4.6，并有动态 `26.+` 和 jcenter 依赖。以上均不作为新项目基线。

可借鉴：系统通知监听→按来源拆分解析→报告健康状态的职责划分。不能沿用：标题即可信支付、最后一个数字即金额、HTTP/MD5 上传、明文配置、通知全量日志、无限保活、无去重的即时上传。

| 来源 | 许可证证据 | 复用边界 |
| --- | --- | --- |
| 仓库原创部分 | [固定 LICENSE](https://github.com/szvone/vmqApk/blob/2867df10fedc26f9239054b9e34a83d3b0bf30dc/LICENSE)，MIT，copyright 2018 ahuyangdong | 授权文字允许复制／修改／分发，复制或实质部分须保留版权和许可声明；本轮选择不复制 |
| 内嵌 `com/google/zxing` | CameraManager 等文件头为 ZXing authors、Apache-2.0 | 不能以仓库 MIT 覆盖这些文件；若未来复制，逐文件确认来源、许可、修改说明及适用 NOTICE |
| 声明依赖 | appcompat 26.+、constraint-layout 1.0.2、ZXing 3.3.0、OkHttp 3.7.0、Okio 1.12.0、JUnit 4.12 | 没有下载并审计全部传递制品；不继承旧依赖清单；#9 为选定新版本生成依赖／许可清单 |
| 新观察器原创成果 | 尚无 LICENSE | 建议 MIT，须用户选择后才添加；不能把建议写成已授权 |

这是本次读取材料的工程复用结论，不是对上游所有文件版权归属的保证。旧仓库的 MIT 不自动授予整个新仓库许可证。

## epay-go：已存在与需要扩展

固定提交下：

- `internal/payment/epay.go` 接受 `wechat_qr` 原始 payload（可为 `wxp://`），提供静态码，不能主动查单或自动退款；旧回调需要网关订单号、金额、非空上游流水和 notify_key 签名。
- `internal/service/order.go:ProcessPayNotify` 检查 **Amount**（非自动容忍 RealAmount），锁订单→商户，事务内支付状态、余额、余额流水一起提交；入口独立创建事务并要求非空 apiTradeNo。新 observer evidence 不能用 event_id 填充该参数，需要抽出共享事务内记账能力。
- `internal/model/order.go` 金额为 decimal(12,2)，最大可表示整数分 999999999999；没有平台流水的跨订单唯一约束，也没有 observer 事件模型。状态只有未付／已付／退款；CheckoutExpiresAt 不能直接冒充不可撤销的业务支付期限。
- `internal/repository/order.go:UpdatePayInfo` 使订单变为 paid；默认 pending 的通知状态在同一订单行中。`GetPendingNotifyOrders` 可重新扫描，`ClaimNotify` 使用持久租约和 token，`FinishNotify` 按 token 结束，`cmd/server/main.go` 启动 worker。已有回调恢复机制，不能描述成“只靠 goroutine”。
- `internal/service/notify.go` 以原订单 Amount/out_trade_no 和商户当前 ApiKey 构造回调，省略空 api_trade_no；2xx 且 body trim/lowercase 为 success 才成功。6 次重试用尽后需运维恢复，租约过期可抢占。后续还要验证 key 轮换、两进程抢占、HTTP 响应丢失、提交后立即退出；本轮未运行这些测试。
- `pkg/safehttp/client.go` 同时验证 URL 和拨号解析 IP，禁用代理，重定向再验证；私网、环回与保留地址被拒绝。本地 HTTP 直连不能算通过真实回调链路。

## New API：充值幂等已有，金额契约存在缺口

[controller/topup.go](https://github.com/QuantumNous/new-api/blob/633b4c5b190dc1af99a2814391354d86948e7943/controller/topup.go) 的 RequestEpay 创建商户订单，金额以两位小数字符串交给 go-epay；回调 `GET/POST /api/user/epay/notify` 验签后将 ServiceTradeNo、type 交给 RechargeEpay。

[model/topup.go](https://github.com/QuantumNous/new-api/blob/633b4c5b190dc1af99a2814391354d86948e7943/model/topup.go) 的 RechargeEpay 锁充值单，检查 provider/status，并在同一事务内更新成功状态和用户 quota；重复成功返回 alreadyDone。额度缓存同步与日志在事务后，不能把日志缺失直接判断为余额未入账。

该调用没有传入回调 Money；go-epay v0.0.5 Verify 只计算／比较签名和解码字段，没有访问原订单。因此该基线**不能作为“合法签名但金额与原单不同也会拒绝”的已实现依据**。不改签名的金额篡改与合法签名的业务金额不符是两个测试项，必须分别验收。这是静态契约差异结论，未运行真实攻击或数据库实验。

处理建议：保留该 SHA 为复现基线，#10/#27 登记阻塞；实施前选择已修正并审计的固定 New API 版本，或明确授权最小兼容修正（精确金额检查与充值共用事务），不得默默放宽验收。本轮不改 New API、不向其上游发布漏洞细节。

跨库事务不存在：网关提交与 New API 提交通过持久重试和接收端幂等组成最终一致性，不承诺分布式一次提交。
