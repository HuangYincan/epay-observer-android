# New API 集成与验收

状态：M3实际双渠道充值已验证，见[最终报告](../testing/M3-prototype-report.md)和[演示手册](../operations/prototype-demo.md)；历史金额缺口由固定本地补丁修复；#10/#27/#29。目标维持易支付协议，由 epay-go 向 New API 通知，APK 不直接充值。

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

## 历史契约缺口与已验证补丁

该 SHA 的 EpayNotify 验签后调用 RechargeEpay，后者有数据库行锁和事务幂等，但没有接收/比较回调 Money 与原单 Money。go-epay Verify 也不查询订单。详见 [静态审计](../research/M0-source-audit.md)。该原始基线的NEW-02不支持；本地补丁42c3424d按SPEC-023修复并通过三数据库及实际TLS验收，不能把修复归给未打补丁的上游。用户长期Goal已授权独立New API测试checkout及必要兼容补丁，保留提交/版本记录但不推送第三方上游；金额验收不得降低。

| ID | 当前验收与边界 |
| --- | --- |
| NEW-01 | 原订单金额/状态事务和并发数据库测试；实际20并发TLS回调、丢响应及进程重启后仅一次quota。双活完整服务实例未部署 |
| NEW-02 | 合法签名错金额在SQLite/PG/MySQL handler及实际lab均零增额，包括成功后重复错金额 |
| NEW-03 | 错签/未重签篡改实际TLS返回fail，零增额 |
| NEW-04 | 原model回归保留非epay/缺失/非pending状态及上限保护；退款准备状态测试不冒充真实退款产品流程 |
| NEW-05 | 完整lab网关与New API SIGKILL/持久重试通过；API读quota与数据库一致 |

## safehttp 与受控测试链路

M3按用户仅本地授权采用[SPEC-024](../specs/SPEC-024-local-synthetic-callback.md)受限测试适配器：默认safehttp不变，专用构建只访问精确环回TLS目标、核验任务CA和已入账synthetic范围；不使用下述M0候选公网方案。实际双渠道AVD闭环和故障矩阵见最终报告。没有公网隧道、端口暴露或域名资源。

默认safehttp私网/环回、混合DNS、重定向等边界由Go测试验证；公网HTTPS完整部署未运行，本地适配器的成功不冒充公网端到端证据。适配器要求精确数值环回地址、路径、端口、私有CA及当前合成范围和已消费事件；不存在生产ALLOW_PRIVATE、全局TLS关闭或DNS伪装。

## 端到端成功条件

创建独立测试用户与充值单 → 获取网关订单 → 由夹具产生不包含订单 ID 的通知 → 观察器正常队列上报 → 网关唯一匹配并记账 → 网关向下单保存的 notify_url 通知 → New API 验签并只增加一次余额。

分别验证微信与支付宝。重复事件、重复商户回调、首次回调超时、回调响应丢失和进程重启均不能重复充值。金额或签名错误不得增加余额。return_url 跳转成功不能替代后台入账验证。

## 隔离

New API 测试实例、数据库、商户凭据和用户必须独立；不能对生产用户触发合成充值。测试结束保留脱敏证据和版本信息，不在公开仓库提交实例凭据。

参考：[官方支付设置](https://docs.newapi.ai/zh/docs/guide/console/settings/payment-settings)、[官方项目](https://github.com/QuantumNous/new-api)。链接是核对入口，不构成对所有未来版本的兼容承诺。
