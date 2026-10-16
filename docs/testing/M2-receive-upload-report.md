# M2 实际接收、上传与 ACK 验证

2026-10-16；#20/#21/#25、epay-go #6；SPEC-017 RECEIVE-01..03 / UPLOAD-01..03。范围为**模拟器独立合成通知到网关持久接收**，尚未匹配订单、记账或调用New API。ACK不是充值成功。

## 已执行结果

| 层次 | 结果 | 范围 |
| --- | --- | --- |
| Go/PostgreSQL | Passed | 原字节签名/范围/撤销、12路并发重复、request冲突与event冲突、deferred commit失败回滚、接收服务重建、密钥轮换保留首次证据、并发撤销串行化、持久设备限流及时钟回退 |
| Go全库 | Passed | 带真实测试DSN的 `go test -race ./...`、`go vet ./...`；固定Go1.26.9任务容器；未把数据库跳过当成功 |
| 共享协议向量 | Passed | 三个既有签名向量保持不变；新增 `protocol-v1/receipts.json`，Kotlin严格ACK/错误解析与Go真实持久回执对照 |
| Android构建/JVM/lint | Passed | strict依赖校验；production JVM 27、fixture JVM 141，失败/跳过均0；fixtureDebug、productionRelease、androidTest和lint |
| API37 AVD主套件 | 33/33 Passed | 原采集/身份/队列/配对；网络异常、429/5xx、401/403/409、redirect、错误ACK、终态、晚到ACK；轮换领取竞态、修复后旧响应失效 |
| API37进程阶段 | 8/8 Passed | 身份/HMAC及pending/key/Room三个配对边界的prepare→force-stop→verify；这些阶段不等同于后台上传调度验证 |
| Android→Go实际HTTPS | Passed | 独立fixture App双渠道通知→正常监听/Room→KeyStore签名→接收事务→ACK；2个事件、3次实际请求，首次ACK响应体丢失后恢复；历史2个UNBOUND不补绑 |
| TLS与APK隔离 | Passed | 未知CA、重定向、错误origin拒绝；server redirect sink为0；显式localhost CA仅fixtureDebug，默认构建及Release保持system-only |

脱敏证据位于 [upload-v1/evidence](upload-v1/evidence)。网关事件/请求计数取自真实数据库；两端 `payment_verified=false`。没有注入订单号或用event_id伪造平台交易号。

## 恢复与事务边界

接收在单个外层事务重新检查授权、验签、设备预算、request记录及事件记录，外层提交后才返回ACK。相同事件重发保留首次key/request/接收时间；重复请求依然检查当前撤销状态。request记录本批不清理。

Android每次重新生成封套，冻结body与event_id。授权快照检查与领取lease同事务；授权暂停同时验证lease。人工修复更新独立upload epoch并释放旧lease，旧响应或领取前失败不能覆盖修复。采集epoch独立，修复不追溯绑定历史UNBOUND。

## Standards

0项仓库规范硬性违规。1项低优先级建议保留：Receiver通过PairingService共享授权存储环境，后续扩展可收拢共同依赖，本批不阻塞。暂停与修复并发建议已处理并加入AVD回归。

## Spec

两项P2发现已修复并经静态复审及运行验证：轮换后旧worker可能领取新授权事件并错误HELD，改为原子领取；时钟倒退时Retry-After未计算未来补充起点，现加入时间差和token缺额并向上取整。没有未处理的审查阻断项。

## 版本、复现与限制

- Android实现初始快照 `7e6da30`，gateway `1135b59`；后续修复提交及最终CI在PR记录。前置配对合并为Android `8ecf253` / gateway `24dd4f3`。
- AVD为已有Medium_Phone_API_37.0，emulator-5554；主报告 `/tmp/observer-upload-final-avd.txt`，同前缀8个进程报告。实际TLS报告 `/tmp/epay-observer-upload-tls/android-instrumentation.txt`；私有目录不上传。
- Go镜像digest `sha256:37cfcca63222ec499bec5e8ad7aad52dbf7529db36d5812b8eae2f0621aa92d6`；PostgreSQL16 digest `sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d`。
- [本地步骤](../development/pairing-local.md)的上传模式创建独立schema；桥只绑定宿主loopback，退出清理schema；脚本清理自身adb reverse/bootstrap。仅重置专用fixtureclient.debug，不触及真实App。
- 仍是机会触发上传：启动、采集、按钮最多处理20次。没有WorkManager保证，待#23实现持久调度、断网/重启恢复；#24完整事件诊断尚未完成。
- M3匹配、永久金额claim、事务记账、持久商户回调和New API余额验证均未交付。真实支付兼容性、API29、真机没有本批运行证据。

本地验证不代替最终head CI。跨仓库PR及其最终检查完成后追加记录，Goal保持active。

## 跨库记录

- [Android PR #51](https://github.com/HuangYincan/epay-observer-android/pull/51)，代码 `7e6da30` / `814d796`。
- [epay-go PR #15](https://github.com/HuangYincan/epay-go/pull/15)，代码 `1135b59` / `c645f10`。
- 实际HTTPS用修复后代码重新运行通过；恢复默认APK后再次验证system-only及Release通过。最终head远端检查以两端PR Checks为准，尚未完成的CI不写作通过。
