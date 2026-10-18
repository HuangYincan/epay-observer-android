# 用户指定支付宝的持久配对报告

2026-10-18 UTC；Android #71 / #42，epay-go #5/#6/#21；SPEC-030 PROV-01..04，沿用 SPEC-016/020；需求 FR-02/FR-08。

## 实测结果

用户明确指定当前专用模拟器的支付宝为实际收款账号。服务器登记一个稳定 receiver 引用，保存用户指定的来源记录；不读取账号页，不把用户指定当成支付平台签发的身份核验。receiver 不由设备或通道 ID 派生，更换设备应保留同一收款账户引用。

2026-10-18 **00:45:21.496–00:45:27.008 UTC**，同一 API 37 / Android 17 arm64 AVD 的 productionDebug 实际通过公网系统 TLS 完成 inspect/redeem/activate，使用默认 observer.db、默认加密 pending 和 AndroidKeyStore。pair 阶段签名健康检查为 AUTHORIZED；强停并重新启动目标观察器进程后，独立 verify 再次为 AUTHORIZED，仍只有指定支付宝一份活跃凭据、第一代绑定。KeyStore key 的 encoded 为 null；不据此宣称模拟器具有硬件级安全性。

这证明指定设备授权在两次检查时有效，且跨目标进程重启持久保存。**不证明支付宝当前登录、通知投递、收款解析、订单归属或充值，也不证明自然后台长期可靠性。** 本批未授予客户端通知访问，未采集正文或发送事件。

## 验收证据

| 验收 | 结果 |
| --- | --- |
| PROV-01 | strict/offline productionDebugAndroidTest、fixtureDebugAndroidTest 编译和 productionDebug lint 通过；应用主 APK 未改，设备中 SHA-256 与此前通过生产隔离检查的固定产物一致。CI 同时构建/检查 productionDebug 与 Release；完整 CI 以本批 PR checks 为准 |
| PROV-02 | 12项宿主契约测试通过（含原连接检查6项和本批6项）：初始 notification 范围、重复字段/非canonical token拒绝、秘密只经 stdin、verify不含token、精确阶段/失败与伪成功拒绝 |
| PROV-03 | 实际 pair 与新进程 verify 两阶段通过：各1份活跃凭据、AUTHORIZED、KeyStore不可直接导出、观察/事件/outbox均0；与普通消息回调验收分开 |
| PROV-04 | 服务器 receiver=1、active binding=1、active credential=1、已消费grant=1、generation=1、device budget row=1；alias/event/event request均0；orders/ledger/refund/settlement表计数前后相同，未主动下单或调用支付/商户回调接口 |
| 原隔离账务 | 4 orders / 4 consumptions / 4 income ledger / 4 topups，总增额30,000,000不变 |
| 秘密清理 | 应用私有输入读取后即删除，宿主finally再次核对清理；消费后的服务器/本地bootstrap及服务器临时管理员会话文件已删除；只保留无token的私有scope、稳定账户记录与持久授权 |

应用主 APK SHA-256 `c8646ec3009e25df9b9d87243d32b14854faeba5333b733b188762ba54c33939`；网关仍为默认无tag构建 `e8b1814`。没有修改现有通道、New API、服务镜像、共享隧道、来源签名/版本策略或生产 matcher。凭据遵循现有30天有效期，续期使用正常管理员grant/轮换，不重建收款账户或清历史。

运行入口为 [provision_gateway_avd.py](../../scripts/provision_gateway_avd.py)。主运行私有目录名 `observer-production-pairing-20261018-0052-run1`，以上时间以报告字段为准，不从目录名推断。准备和非秘密scope保存在私有 `observer-production-pairing-20261018-0050-preparation`；服务器另存稳定receiver记录及受限审计。真实域名、receiver/device/key标识、grant、管理员会话、配置和原始日志不提交。

本次实际配对首轮通过，没有以重发grant、清库或替换绑定来修复失败。默认43项 smoke 排除这个显式持久网络类，没有重跑已完成的生命周期、普通消息和合成充值验收。新增入口只存在于 androidTest；Release 无私有输入入口或新增真实来源许可。

## 下一步边界

生产自动匹配仍关闭，真实通知采集尚未开启。#33 的真实收款通知标题/正文采样范围尚需单独明确；#8 的订单归属仍未解决。当前设备配对完成不能用来关闭 #42 或宣布自动充值可用。
