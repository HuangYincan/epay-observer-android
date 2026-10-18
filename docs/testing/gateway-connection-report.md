# 既有网关连接预检报告

2026-10-17 至 2026-10-18 UTC；Android #69 / #42，epay-go #5 / 已合并 #21；SPEC-029 CONN-01..03。需求 FR-02 / FR-08。

## 实际结果

2026-10-18 00:05:53–00:05:55 UTC，同一 API 37 / Android 17 arm64 AVD 中的 productionDebug 经系统 TLS 到达既有公网网关，无 Authorization 的 inspect 返回 HTTP 401、JSON、严格协议错误 `invalid_grant`。CONN-03 通过。未授予该客户端通知访问；未登记 receiver、grant 或 binding，未发送事件。

这是实际 APK 的网络入口证据，**不证明配对、签名设备健康、真实通知、收款解析、订单归属或充值**。既有支付宝普通消息 0→2 回调仍由原报告单独维护，没有重复运行。

应用主代码沿用 Android `b8f62ee`，APK SHA-256 `c8646ec3009e25df9b9d87243d32b14854faeba5333b733b188762ba54c33939`；新增网络驱动只在 androidTest。网关静态二进制来自 `e8b1814`，Go 1.26.9 / linux amd64、CGO=0、无 build tags、vcs.modified=false，SHA-256 `27682b62ca2b6ddcdb16a41ce1443c4720809cb99b5c4bd615fef532aa57911c`。该提交四项 GitHub checks 当次复核均成功。

## 验证与边界

| 检查 | 结果与限制 |
| --- | --- |
| CONN-01 构建/隔离 | strict offline productionDebug、productionDebugAndroidTest、fixtureDebugAndroidTest 编译及 productionDebug lint 通过；productionDebug/Release 最终 APK 检查通过。JVM/Release 大部分任务命中未变更缓存，不记为重新运行整套验收 |
| CONN-02 宿主契约 | 6 项 unittest 通过：精确成功条件、缺失/重复/伪成功、失败进程、不安全 origin、脱敏失败诊断及未知字段拒绝 |
| 数据迁移演练 | 独立合成库旧→新→旧重入→新重入通过，8 张旧业务表所有旧字段一致，schema 6 幂等；不是生产恢复或真实付款验证 |
| 后端实际 amd64 隔离运行 | 7 项通过，包括 TLS/Host/未知 CA、普通 HTTP 不暴露配对、无业务/设备记录；演练容器无宿主端口 |
| 前端 TLS 转发隔离运行 | 8 项通过，包括原配置保留、nginx 校验、健康、认证入口、错误 Host、管理员鉴权、静态首页一致、无新增业务/设备/事件记录 |
| 既有回调目标策略 | 仅核对目标地址是否满足默认公网策略；未主动调用真实商户回调，不是 New API 充值复验 |
| 受控切换 | 00:05:22–00:05:29 UTC 完成；后端 healthy、前端健康、TLS 与管理员鉴权通过，原通道路由/静态首页保留，New API/共享隧道未修改 |
| 切换后观察器数据 | schema 6；receiver/binding/grant/event/request 均 0。默认构建生产 matcher 关闭 |
| 数据保护 | 切换前真实数据库归档可读；没有执行生产恢复演练。正式 KEK/内部 TLS 只保存在服务器受限目录及同机受限备份，未下载或提交；尚无异机备份证据 |
| 原隔离账务 | 4 orders / 4 consumptions / 4 income ledger / 4 topups，总增额 30,000,000 不变 |

本次相关构建/检查命令为 `:app:assembleProductionDebug :app:assembleProductionDebugAndroidTest :app:assembleFixtureDebugAndroidTest :app:lintProductionDebug :app:testProductionDebugUnitTest :app:assembleProductionRelease`（strict dependency verification）、两个 production 变体的 `verify_apk.py`、`python3 -m unittest discover -s scripts/tests -p 'test_gateway_connection.py'` 和 [匿名驱动](../../scripts/check_gateway_connection_avd.py)。最终 CI 以本批 PR checks 为准，CI 只编译网络测试、不请求公网；原43项 smoke 继续排除所有显式网络类。

## 失败尝试与恢复

原始失败报告均保留：后端演练 v1/v2 分别为验证脚本启动同步/空库管理员配置和 HTTP helper 名称遮蔽问题，v3 修正后通过；前端首次构建把镜像 ID 误作远端名称，改用已核验的本地回退标签；前端 v2 误查不存在的管理员路由，改为实际 grants 路径，v3 通过。没有修改产品认证规则来通过断言。

首次 AVD 网络请求失败，细分结果为 DNS failure；Wi-Fi 初始断开，重新连接后仍无法解析。保留数据重启亦未恢复；直接 IP 可达、模拟器 DNS 代理无响应，而向宿主已有 IPv4 DNS 的同一查询成功。先停止唯一实例，再沿用同一 userdata/可见性与显式宿主 DNS 冷启动，原全局网络设置保持不变。恢复后旧网关实际返回 404，切换后才得到上述 401；这些旧失败不会追溯改为成功。未伪装 UA、关闭 TLS 或清数据。

最终私有 APK 证据目录 `observer-connection-after-cutover-20261018-0006`，准备产物/失败记录在私有 `observer-production-connection-20261017-230649`。真实部署域名、配置、原始日志、备份和密钥不进入公开仓库。两天演练密钥未用于正式接入；正式内部服务证书到期 2027-10-17 UTC，私有运维记录要求提前30天续期；不能重生成 KEK 来处理旧凭据故障。

下一步等待用户指定实际收款账户，再配对和签名健康检查；真实正文和生产自动匹配继续受 #33/#8 阻塞，#42 不因本次入口检查关闭。
