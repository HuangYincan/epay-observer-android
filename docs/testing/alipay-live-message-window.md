# 支付宝普通消息：单窗口计数驱动与实际尝试

2026-10-17；#33 / #42；FR-01 / FR-02 / FR-07；SPEC-028 PROBE-04 / PROBE-05、LIVE-01..03。用户同意收到提示后自行从另一端发送普通支付宝消息，代理不代发消息或付款。两次实际观察均为0回调，消息发送与测量窗口的时间关系尚未确认；不新增真实投递通过项，不判支付宝漏投或永久不兼容。

## 驱动与读取边界

新增 `ProbeLiveDeliveryTest` 仅在androidTest内运行：检查来源匹配、既有通知访问、监听连接、屏幕亮起和keyguard未锁定，通过主探针原按钮开启120秒窗口；armed时计数必须为0。约每秒检查条件，计数变化或每10次采样输出聚合状态，结束必须为EXPIRED。运行中没有UI层级读取、截图、通知枚举、正文读取、业务界面操作或网络上报。

仅测试Activity临时设置keep-screen-on，finally清除并结束Activity；没有修改系统屏幕超时、锁屏策略、网络或监听权限。探针主APK、签名白名单和120秒隐私上限不变。instrumentation影响进程优先级，这不是自然后台稳定性测试。场景为探针前台/屏幕亮起/未锁定，不能替代真正keyguard锁定或支付宝收款样本。

## 两次实际尝试

沿用专用API37 / Android17 / arm64 headless SwiftShader AVD、支付宝12.12.30.8000 / 212310。以下UTC为宿主收到结构化状态的时间，elapsed_ms为设备单调时钟差值，不能将宿主接收时间等同通知/消息发送时间。

| 尝试 | armed → finished（UTC） | elapsed_ms | 回调差值 | 用户确认记录时间（UTC） | 结论 |
| --- | --- | --- | --- | --- | --- |
| 第一次 | 05:38:47.707 → 05:40:47.867 | 120,280 | 0 | 05:40:52.698 | 正式提示前已耗用部分窗口；确认记录在结束后约5秒，发送时刻未测量 |
| 第二次 | 05:42:18.915 → 05:44:19.166 | 120,294 | 0 | 06:45:32.418 | armed后立即给正式提示；确认记录在结束之后，发送时刻未测量 |

两次各14条结构化状态（armed、12次采样、finished），记录的连接、来源匹配、屏幕交互均为true，keyguard_locked=false；instrumentation各1项通过。条件约每秒检查，不能排除两个检查点间的短暂变化。回调差值均0，通知结果均 **Not exercised**。用户两次回复“已发”，各自按提示的一条普通消息记录为用户反馈，不等于独立确认消息到达了目标App或在窗口内产生系统通知。

两次宿主均完成停止探针并重开、恢复原屏幕状态和设置比对；支付宝进程仍存在且stopped=false。每次前后独立审计均为4笔订单、4条独立消费、4条收入、4笔成功topup及总增额30,000,000。第一次私有报告的`probe_reopened_default_stopped`只依据停止/启动命令完成，未独立读取重开后的内存状态；第二次改名为`probe_reopen_command_completed`，不把命令完成当作新状态验收。没有检查登录界面或接入合成充值。

## 证据与复现

私有证据目录：`/tmp/observer-probe-live-20261017-053835`、`/tmp/observer-probe-live-20261017-054206`。各含`report.json`、`instrumentation.txt`和另存的`user-send-confirmation.json`；原报告中的Pending保留原样，确认文件是后续注记。失败/零结果不覆盖。构建日志`/tmp/observer-probe-live-build-20261017.txt`。公开库只保留固定状态和聚合结果，不提交原始lab、APK或账号内容。

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME=/Users/acccan/Library/Android/sdk
./gradlew --no-daemon --max-workers=2 --dependency-verification strict \
  :probe:assembleDebug :probe:assembleDebugAndroidTest :probe:testDebugUnitTest :probe:lintDebug
python3 scripts/verify_probe_apk.py probe/build/outputs/apk/debug/probe-debug.apk
adb -s emulator-5554 install -r probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk
```

下一次仅在双方就绪、有明确单次发送安排时运行。先核对目标仍为qemu用户0、支付宝进程/非强停、屏幕和原设置，使用新私有目录保存日志，独立审计固定四笔基线；已有观察窗口不得被替换。单次驱动命令为：

```sh
adb -s emulator-5554 shell am instrument -w -r -e probeUserMessage true \
  -e class dev.epay.observer.probe.ProbeLiveDeliveryTest \
  dev.epay.observer.probe.debug.test/androidx.test.runner.AndroidJUnitRunner
```

宿主须给命令180秒上限，仅在收到`probe_live_state`的armed状态后提示发送，并明确UTC截止时间；看到提示时已过截止就不要发送。不得自动连续重开窗口。成功也需核对finished、测试成功、来源/条件及计数差值；异常显式停止探针，恢复屏幕/原设置并再做固定基线账务审计。这条单独instrumentation命令不负责宿主超时或账务审计。

严格依赖校验构建、6项probe JVM测试、lint及主APK隔离检查通过；设备上只执行上述新消息窗口，没有重跑生命周期矩阵或合成充值。CI只构建驱动，不生成支付宝真实通知。

## 下一步

用户随后说明是在回复“已发”时才发送；这份补充另存两次目录中的`user-send-timing-clarification.json`。宿主处理确认的时间仍不是独立发送时间，不能据此补记窗口内触发，也不能断定漏投。

第三次在用户回复“准备好了”后运行：2026-10-17 07:43:25.406 → 07:45:25.786 UTC，elapsed_ms=120,331，14条状态，回调0，条件/来源/连接和最终EXPIRED检查通过。给出07:45:10 UTC的保守发送截止时间后，用户反馈“过时了”；无新的发送确认，该次没有已知窗口内触发，仍为Not exercised。清理/设置恢复和前后四笔、总增额30,000,000审计通过。私有目录`/tmp/observer-probe-live-20261017-074201`，另存`user-deadline-expired.json`；不覆盖前两次。

聊天提示仍不能可靠对齐短窗口，因此后续改为[本地开始控制页](alipay-local-start-control.md)：用户看到页面后自行点击开始，等待页面实际变绿再发消息，不再要求赶聊天提示。页面等待期间不开始观察，不延长或自动重开窗口。即使确认窗口内发送，0回调也只定位为该条件未观察到，仍需区分目标App未产生通知、消息未达/通知设置和观察路径，不能跳到收款解析或生产确认。真正锁屏、正文、真实收款归属、真机与发布保持原门槛，生产自动匹配关闭，#33 / #42 / #8不关闭。
