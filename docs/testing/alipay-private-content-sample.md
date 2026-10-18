# 支付宝一次性私有内容采样报告

2026-10-18 UTC；#73 / #33 / #42，SPEC-031 SAMPLE-01..04；FR-01/FR-02。用户授权仅一次120秒读取标题/正文（可能含普通消息），本机私有处理后删除原文。原通知可达、持久配对、签名健康、生命周期与合成充值验收直接复用，本批未重跑。

## 实际结果

同一 API37 / Android17 arm64 专用 AVD，固定官方支付宝12.12.30.8000 / versionCode 212310，来源包/签名/用户/UID校验保持SPEC-028原值。

**2026-10-18 03:07:50.042–03:09:50.222 UTC**为instrumentation记录的本次起止边界（包含启动标记持久化），gate的elapsedRealtime测得120,176ms，终态EXPIRED，**0条回调/0条内容样本**。未安排受控触发，不代发消息、转账或付款，没有自动续窗。结果为通知采样 **Not exercised**，真实收款 **Not assessed**；不说明支付宝不兼容，也没有推翻此前普通消息0→2回调证据。

没有原文产生；设备与宿主原文路径最终均确认不存在，仅保留空模板、used标记及固定元数据。撤销了本工具临时监听权限并强停，原监听授权列表与采样前完全一致，production客户端仍未获通知访问。未改网络、锁屏、屏幕设置或重启AVD。本批没有复查支付宝登录，也不固定前后台/keyguard条件，不据此新增条件投递结论。

## 验收证据分层

| 验收 | 层次与结果 |
| --- | --- |
| SAMPLE-01 | 10项纯JVM测试通过：来源反例、连接/持久单次标记、截止/字段间超时、断连、倒退、容量/字段长度和异常后拒绝再读；均为合成输入 |
| SAMPLE-02 | strict/offline构建、JVM、lint、androidTest编译通过；lint无问题；最终APK检查无权限/网络/活动通知/日志/外部存储，唯一系统保护listener，状态UI不能启动gate，无release变体；原probe检查器未修改 |
| SAMPLE-03 | 本批9项宿主合成测试通过：默认遮蔽未知文本/数字、大小/字段/数量约束、失败清理、旧证据不覆盖、伪成功拒绝及部分导出失败不自动重试。宿主现有加新增共21项通过；不把这些测试记作真实内容采样通过 |
| SAMPLE-04 | 模拟器真实App环境，实际instrumentation单项通过，完成一次120秒窗口；来源/窗口基础设施正常，真实回调0、正文0；有内容时的设备导出/脱敏分支本次未实际行使 |
| 恢复/账务 | 撤权、强停、原文路径不存在及监听列表恢复通过；前后均4 orders / 4 consumptions / 4 income ledger / 4 topups，总增额30,000,000 |

独立包`dev.epay.observer.sample.debug`无网关/上传/配对/账本依赖，只读窗口内新收到的onNotificationPosted；不枚举已有通知。开始前no_backup写入used，重启或重连不会自动恢复。每字段读取前由同步gate核对来源/截止，最多32条，标题256、正文1024个UTF-16单元。回调更新或重投不能当成独立消息或付款笔数。

宿主目录0700，文件0600；stdout从不回显内容，未知文本/数字默认遮蔽，真实模板也仅保留私有。本次空模板不含通知信息。原文不存在验证不等于非空真实正文的清理实测，也不等于介质安全擦除；内容失败分支仅有合成测试。instrumentation影响进程优先级，不证明自然后台或长时可靠性。

## 产物与入口

采样APK SHA-256：`2a4836b485a96af5de842f71f15367c01b137b538cc172785c916b208b8d7091`。productionDebug APK仍为`c8646ec3009e25df9b9d87243d32b14854faeba5333b733b188762ba54c33939`，原probe/生产采集、解析、上传和匹配策略未改。没有接触服务器、New API、真实订单或付款。

私有证据`/private/tmp/observer-content-sample-20261018-run1/report.json`、同目录instrumentation与空模板；构建及产物日志位于`/private/tmp/observer-sample-final-*-20261018.txt`。公开仓库只含合成测试和本报告聚合结果，不提交任何原文、真实模板、标识或密钥。

构建与检查入口：

```sh
./gradlew --offline --no-daemon --dependency-verification strict \
  :sample:assembleDebug :sample:testDebugUnitTest :sample:lintDebug :sample:assembleDebugAndroidTest
python3 scripts/verify_sample_apk.py sample/build/outputs/apk/debug/sample-debug.apk
python3 -m unittest discover -s scripts/tests -p 'test_private_*.py'
```

实际驱动[scripts/private_content_sample.py](../../scripts/private_content_sample.py)要求显式模拟器serial、既有adb、隔离lab和新私有证据目录。本设备的一次性标记已经消费，驱动会拒绝再开窗；不能删除标记、清数据或安装第二实例来绕过授权。CI仅执行构建/合成测试/产物检查，不采样真实内容。

## 下一步

采样工具与一次实际尝试已交付；#33仍缺真实收款正文样本，#8仍缺真实订单归属，#42仍缺完整真实兼容性，三项保持开放。后续需要明确受控真实收款通知来源及新的采样范围，才能继续；不要求用户重复普通聊天来冒充到账样本。本次授权已用完，生产采集和自动匹配保持关闭，真机与发布未验收。
