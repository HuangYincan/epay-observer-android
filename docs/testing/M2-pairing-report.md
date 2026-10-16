# M2 实际配对与恢复验证

2026-10-16；#17/#25、epay-go #6；SPEC-015/016、PAIR-01..05。这是**模拟器合成通知原型的配对子链路**，没有事件HTTP接收、订单匹配或New API充值。#17继续追踪接收端撤销联动，Goal保持active。

## 已执行结果

| 层次 | 结果 | 证据/范围 |
| --- | --- | --- |
| Go纯协议 | Passed | 14个双方共享配对请求向量；严格JSON、base64url、UUID、字段集合；既有事件黄金向量不变 |
| Go真实PostgreSQL | Passed | 原8项模型测试及新增配对：12路并发兑换、同一密钥恢复、错误resume、过期/撤销/替换/轮换、持久限流、deferred commit失败回滚、AAD跨范围/篡改拒绝 |
| Go全库 | Passed | `go test -race ./...`、`go vet ./...`；现有支付、通知和safehttp回归保持通过，无DSN跳过冒充通过 |
| Go实际TLS | Passed | 正常inspect/redeem/activate；错误方法/尾斜线/query/重复header/大body/host/未知CA；撤销后HTTPS恢复拒绝 |
| Android构建/JVM/lint | Passed（CI另记） | 严格依赖验证，fixtureDebug、productionRelease、androidTest；production JVM 25项、fixture JVM 139项，零失败/跳过 |
| API37 AVD主套件 | 28/28 Passed | 合成采集、身份/队列、HMAC、AES pending篡改/密钥缺失、activation响应丢失、本地恢复、轮换延迟采集epoch与冲突隔离 |
| API37真实进程恢复 | 8/8阶段 Passed | 原身份/HMAC两个阶段；pending、HMAC导入、Room激活三个边界，各prepare→force-stop→verify；Room已提交时不重新请求密钥 |
| Android→Go实际HTTPS | Passed | 微信/支付宝两个独立授权实际兑换/KeyStore/Room激活；历史2个UNBOUND保持不变，新2个候选READY；服务端2个active凭据 |
| Android TLS负例 | Passed | 127.0.0.1仅系统信任拒绝临时CA；受信localhost的307拒绝且server sink计数0；错误origin响应拒绝 |
| APK边界 | Passed（显式CA、恢复默认、Release） | 仅显式fixtureDebug的localhost加入额外CA；精确SHA检查，无私钥；省略构建属性重新验证system-only |

脱敏机器证据：[Android](pairing-v1/evidence/android-result.json)、[网关](pairing-v1/evidence/server-result.json)、[产物摘要](pairing-v1/evidence/artifacts.json)。它们明确记录 `events_uploaded=false` / `payment_verified=false`，不能外推为自动充值通过。

## 环境与原始记录

- 已有 Medium_Phone_API_37.0、Google Play arm64/API37；Emulator37.2.12；运行参数4CPU/4096MiB，未改config.ini、未wipe，未安装宿主工具。
- Go1.26.9镜像digest `sha256:37cfcca63222ec499bec5e8ad7aad52dbf7529db36d5812b8eae2f0621aa92d6`；PostgreSQL16镜像digest `sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d`。
- 任务资源名 `epay-observer-prototype` 网络、`epay-observer-postgres`、`epay-observer-go-tools`；数据库不发布宿主端口。临时TLS桥仅发布 `127.0.0.1:18443`，测试结束自动退出，schema由测试清理。
- 本机原始主报告 `/tmp/observer-pairing-avd-software.txt`，同前缀8个process/pairing阶段报告。HTTPS报告及带临时CA的APK保留在 `/tmp/epay-observer-pairing-tls/`，不提交私有bootstrap、CA私钥、数据库环境文件或完整日志。
- TLS桥每次新建schema，最终正常/负例组合在新schema上重跑。此清洁复现仅覆盖配对，不满足长期Goal的完整充值复现。

## 失败与修复记录

1. 首轮Go数据库报 `INSERT has more expressions than target columns`：GORM将VALUES首位置的byte切片展开，改为命名SQL参数，原测试转绿。
2. 原运行AVD图形服务/系统CPU过载、Activity启动45秒超时，240秒整套测试失败。保留 `/tmp/observer-pairing-avd.txt`；同AVD冷启动、`-gpu swiftshader -no-window`后28+8通过，没有延长断言超时或清数据。
3. 显式CA构建遇AGP Kotlin DSL名称/source-set/Provider API差异，修正为明确目录及生成依赖后构建通过；未禁用AGP安全检查。
4. AVD bootstrap的exec-in未落文件，改为已用非敏感探针验证的非PTY `adb shell -T` stdin。没有把token写入命令参数或共享存储。
5. AVD时钟比网关慢约135ms，首次新密钥尚未达到本地not_before；增加最多5秒的后台等待，随后仍严格验证not_before/expiry。较大时差继续失败，不回拨时间或放宽条件。

## Standards

审查未发现仓库规范硬性违规。一项低优先级重复授权规则建议已处理：inspect和redeem共用事务内generation/device核对helper。

## Spec

两项实现问题已修复并回归：轮换同scope时旧缓存绑定可能跨暂停窗口生效，现使用Room capture epoch阻断；未知路径/方法原落入Gin默认404，现统一Guard和严格错误JSON。验收缺口已补：三个实际进程恢复边界及Android实际HTTPS正常/负例。未增加支付资格或通知订单提示。

审查计数：Standards 0项硬性违规、1项建议已处理；Spec 2项实现问题已修复、1项验证覆盖问题已补齐。本批没有待处理的审查阻断项。

## 尚未交付

实际事件接收事务/重放登记/网络上传/ACK、WorkManager与完整状态页、测试订单匹配与永久claim、商户回调到New API、New API金额补丁、完整崩溃矩阵/自动充值复现仍未实现。API29与真机、真实微信/支付宝兼容性未验证。普通运维KEK重封装未实现；设备key轮换不等于KEK轮换。不得关闭整个长期Goal。

CI与两端PR/最终代码SHA在本报告末尾追加，不能用本地成功代替远端检查。

本地复现：[完整配对步骤](../development/pairing-local.md)。

## 固定代码与跨库记录

- Android实现提交 `9b74816`（前置39f9243），[PR #50](https://github.com/HuangYincan/epay-observer-android/pull/50)。
- 网关实现提交 `f961159`（前置0107b0f），[PR #14](https://github.com/HuangYincan/epay-go/pull/14)。
- 网关实现head完整CI [run 38850098132](https://github.com/HuangYincan/epay-go/actions/runs/38850098132)通过：race/PostgreSQL、fuzz、vet、build、漏洞扫描、前端及两个镜像。
- Android与后续文档提交的最终CI状态以各PR Checks为权威，只有最终head通过且审查问题处理后合并；文档更新不改变上述代码快照。
