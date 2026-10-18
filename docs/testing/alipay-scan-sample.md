# 用户本地启动的扫码通知采样：准备报告

2026-10-18 UTC；#75/#33/#42；SPEC-032 SCAN-01..04，FR-01/02/07。用户确认既有扫码收款进入指定支付宝，并自行完成EPay个人码通道配置。本批只准备单次受控内容采样，不替用户操作真实付款。

## 当前状态

**Ready；实际新窗口Not run。** 没有复用或重置SPEC-031的已消费`used`，新`used-scan-payment-v1`尚未消费，没有正文待清理。打开控制页、刷新和轮询不采集。用户准备好自己的付款端与目标EPay通道二维码后，明确本地点开始才进行预检、临时授予采样工具权限、请求HOME和启动一次窗口；只有实际RUNNING/来源/连接新鲜且宿主预算未超时时才显示绿色倒计时。

当前真实通知解析、订单归属和生产匹配仍未接通；配置字段完整不证明二维码有效或账户归属得到平台验证。**本轮只是采样，不承诺扫码后New API自动充值。** 页面在开始按钮前及页尾均明确提示，用户自行决定和操作付款。工具不创建生产订单、不修改支付通道或订单状态，不发送旧MD5回调；也不把用户点“我已自行付款”当作平台证据。

## 验收分层

| 验收 | 准备证据 |
| --- | --- |
| SCAN-01 | strict/offline新debug APK/runner编译、10项既有gate/source JVM契约及lint通过；原used保留，主界面/Intent不能选择新slot或启动gate，APK无网络/外部存储/活动通知/日志调用，无release；生产客户端/旧probe规则不变 |
| SCAN-02 | 宿主27项契约通过（本批6项，既有21项）：固定状态/半行/未知正文字段拒绝、单次并发开始、用户反馈需窗口内、同源鉴权/请求体拒绝、取消/超时终止自己的ADB子进程。首次在文件系统沙箱内绑定环回被拒；获准本地环回权限后通过，不把该环境拒绝记作业务失败 |
| SCAN-03 | ego-browser纯宿主页QA通过：WAITING不启动，点开始后显示绿色/118秒、付款反馈，合成结束后刷新不重开。页面明确page_test，不连接AVD，不生成通知/订单/付款；合成8秒演进仅检查页面，不是120秒或真实投递验收 |
| SCAN-04 | 唯一既有API37/Android17 arm64 AVD更新sample与runner，显式prepareOnly 1项通过（0.046秒）：支付宝固定来源匹配、旧标记存在、新标记不存在、无原文、采样工具未获监听权限。没有开内容窗口、没有复查账号会话或读取画面 |
| 原隔离账务 | 只读审计仍4 orders / 4 consumptions / 4 income ledger / 4 topups，总增额30,000,000；未重跑充值或生命周期矩阵 |

来源包、212310版本及签名集合仍与SPEC-028/031一致。新slot固定在代码里，不接受任意session ID。实际采样字段/容量、同步截止、私有传输、未知文本/数字遮蔽及失败清理沿用SPEC-031；标题/正文仅留应用no_backup与宿主0700/0600文件，脱敏后删除双方原文，模板仍私有。真实有内容的导出分支尚未在本批行使。

本批采样APK SHA-256：`c8efca2c01508b8bc6204f2044b9181c678d46f052ef40bf07cb259544d29221`。启动前已取消则不创建ADB子进程；启动后取消/超时会终止自己的子进程，之后仍进入设备清理。取消检查与子进程创建不是平台原子操作，已在途启动可能短暂执行，随后通过撤权/强停结束，不承诺零延迟取消。

## 运行入口

```sh
python3 scripts/scan_sample_control.py \
  --serial emulator-5554 \
  --adb /path/to/existing/sdk/platform-tools/adb \
  --lab /tmp/epay-observer-existing-lab \
  --directory /private/tmp/observer-scan-NEW-UNIQUE-RUN
```

这是控制页等待入口，不是自动采样命令；参数必须使用既有隔离lab及新私有目录。控制URL/token仅保存在私有control.json，绑定127.0.0.1；不发往公网，不提交到仓库。设备互斥复用既有计数控制器，防同时控制同一AVD。停止用Ctrl-C/SIGTERM，正在运行时等待撤权/强停、原文清理、监听列表恢复及账务审计结束。

构建检查入口与SPEC-031相同，宿主测试为`python3 -m unittest discover -s scripts/tests -p 'test_*.py'`；涉及环回服务的测试需本地监听权限。CI仅构建/合成测试，不运行采样或付款。

私有准备日志`/private/tmp/observer-scan-build-20261018.txt`、`observer-scan-apk-check-20261018.txt`、`observer-scan-prepare-only-20261018.txt`；纯页面QA在`observer-scan-page-qa-20261018-run1`。实际新窗口报告将位于新的控制目录及其sample/report.json；不覆盖SPEC-031首次0样本报告。

## 后续记录规则

实际用户开始后单独记录真实UTC、elapsed_ms、回调/模板数、用户反馈时点与清理结果。无回调记Not exercised，未知/普通消息或相关词不能当作真实到账；用户反馈与平台独立证据分开。instrumentation影响优先级；本批不验证自然后台、真正keyguard、长时可靠性或实体真机。

准备完成不能关闭#33/#42/#8，生产自动匹配保持关闭；首次正文采样0结果仍由SPEC-031报告保留，不追溯升级。
