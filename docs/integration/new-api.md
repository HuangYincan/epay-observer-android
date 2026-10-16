# New API 集成与验收

状态：M0历史研究与M3实施证据并存，当前金额补丁/受限TLS基础见[报告](../testing/M3-callback-foundation-report.md)；#10/#27/#29。目标维持易支付协议，由 epay-go 向 New API 通知，APK 不直接充值。

固定研究基线：`QuantumNous/new-api v1.0.0-rc.44`，提交 `633b4c5b190dc1af99a2814391354d86948e7943`，go-epay v0.0.5。2026-10-15 静态审计完成，未部署／运行数据库测试；该 RC 不是生产推荐。epay-go 固定 `4d855060439df247fc60a529139e80c7e4b757c0`。

## 配置映射

| 配置 | 来源 |
| --- | --- |
| PayAddress | epay-go 对外根地址；测试指向隔离测试网关 |
| EpayId / PID | epay-go 商户中心 ID |
| EpayKey / KEY | 同一商户 API Key，不是设备 key 或 notify_key |
| PayMethods | wxpay 与 alipay；检查固定版本支持的 JSON 配置 |
| 商户回调 | 由 New API 下单时传入并由网关保存，客户端不能替换 |

长期Goal已授权本任务专用本地隔离实例；M3已启动完整New API测试实例，金额校验本地补丁42c3424d通过三数据库和回归。此版本还以支付设置确认状态和 PayMethods 非空控制 EPay webhook 是否启用；隔离实例通过普通setup、dashboard会话和设置接口配置，不以测试绕过开关代替。只验余额充值 `/api/user/pay` → `/api/user/epay/notify`，订阅购买不在首轮范围。

金额对应：New API PurchaseArgs.Money 为两位小数元字符串；其 ServiceTradeNo 对应网关 out_trade_no，网关 trade_no 是另一标识。New API 的 quota 增额按原 topup.Amount 与 QuotaPerUnit 计算，不能将 money 元值直接当 quota。商户回调为 EPay MD5（非 Observer HMAC）；api_trade_no 可省略，New API 充值路径不要求它。

## 必须处理的契约缺口

该 SHA 的 EpayNotify 验签后调用 RechargeEpay，后者有数据库行锁和事务幂等，但没有接收/比较回调 Money 与原单 Money。go-epay Verify 也不查询订单。详见 [静态审计](../research/M0-source-audit.md)。因此 NEW-02 不能写为已支持；保留 #10 和相关集成任务阻塞，建议选已修正并审查的固定版本，或用户确认后另开最小 New API 兼容修正。用户长期Goal已授权独立New API测试checkout及必要兼容补丁，保留提交/版本记录但不推送第三方上游；金额验收不得降低。

| ID | 未来验收 | M0 状态 |
| --- | --- | --- |
| NEW-01 | 两实例/并发/响应丢失后重复回调，充值单成功和 quota 原子且一次 | 源码有机制；运行 Not run |
| NEW-02 | 合法签名但金额不符，零增额 | 静态契约缺口；Blocked |
| NEW-03 | 错误签名／不重签名的金额篡改，零增额 | 源码验签；运行 Not run |
| NEW-04 | 过期/非 epay/缺失充值单不得成功；退款不恢复 | 源码部分状态防护；运行 Not run |
| NEW-05 | 网关已提交后重启，回调最终完成；缓存与数据库一致 | Not run |

## safehttp 与受控测试链路

M3按用户仅本地授权采用[SPEC-024](../specs/SPEC-024-local-synthetic-callback.md)受限测试适配器：默认safehttp不变，专用构建只访问精确环回TLS目标、核验任务CA和已入账synthetic范围；不使用下述M0候选公网方案。实际测试见回调基础报告，完整AVD仍待验收。

建议后续使用受控公网 HTTPS 测试域名和反向隧道/转发，使 epay-go 的默认 safehttp 实际拨号到经验证的公网入口，再由入口转发到隔离 New API。只开放 `/api/user/epay/notify` 等必要测试路由，独立用户/商户/DB、访问约束与可清理路由；具体域名及入口资源 TBD，后续部署需授权。本轮没有创建隧道、开放端口或注册域名。

仅把 `localhost` 改成域名而仍解析到私网无效；safehttp 禁用 HTTP 代理，不应依赖代理环境变量绕过。AVD 到网关的开发路由与网关到 New API 的回调路由是两回事。

默认安全链路验收：NET-01 公网 HTTPS 正常；NET-02 私网/环回拒绝；NET-03 DNS 混合公私网或拨号重新解析变私网拒绝；NET-04 重定向至私网拒绝。均 Not run。纯本地单测可在 Go `_test.go` 中注入固定 transport 与 httptest 服务，但这不能作为 default safehttp 的端到端通过证据。不得增加生产 ALLOW_PRIVATE、DNS 伪装或全局校验关闭选项。

## 端到端成功条件

创建独立测试用户与充值单 → 获取网关订单 → 由夹具产生不包含订单 ID 的通知 → 观察器正常队列上报 → 网关唯一匹配并记账 → 网关向下单保存的 notify_url 通知 → New API 验签并只增加一次余额。

分别验证微信与支付宝。重复事件、重复商户回调、首次回调超时、回调响应丢失和进程重启均不能重复充值。金额或签名错误不得增加余额。return_url 跳转成功不能替代后台入账验证。

## 隔离

New API 测试实例、数据库、商户凭据和用户必须独立；不能对生产用户触发合成充值。测试结束保留脱敏证据和版本信息，不在公开仓库提交实例凭据。

参考：[官方支付设置](https://docs.newapi.ai/zh/docs/guide/console/settings/payment-settings)、[官方项目](https://github.com/QuantumNous/new-api)。链接是核对入口，不构成对所有未来版本的兼容承诺。
