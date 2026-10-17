# 支付宝探针无人操作验收

2026-10-17；#33 / #42；FR-01、FR-02、FR-07 的可达性调查子集；SPEC-028 AUTO-01..06。测试对象是独立本地计数探针与现有模拟器中的真实支付宝。没有发送消息、触发付款、读取通知正文，真实事件未接入合成充值链路。

## 证据分层

用户先前确认支付宝登录，并反馈普通场景计数增加；发现支付宝被杀后台后，重开支付宝，其描述的锁屏场景也有计数。保留为用户反馈的单次真实来源回调证据，当时没有独立记录keyguard状态、通知触发次数或重复轮次。

后续代理自动读取当前AVD锁屏策略为disabled=true，熄屏时keyguard=false。真正keyguard锁定测试因此Blocked；没有修改锁屏策略来让测试通过。自动化条件窗口的零回调只表示未观察到，没有受控真实发送端，不能判断该条件下通知是否可达。通知更新或系统重投也可能增加计数，不等于消息数或付款数。

## 环境与可重复执行

- 专用Medium_Phone_API_37.0，API37 / Android17 / arm64；支付宝12.12.30.8000，versionCode 212310，固定签名来源检查沿用SPEC-028。
- `probe`主APK行为不变；自动操作仅在androidTest和宿主脚本中，通过既有按钮开启120秒窗口。探针无网络、正文读取、事件/账本模块依赖。
- 本批开始时可见AVD无响应，正常结束本任务模拟器进程后保留同一userdata冷启动；可见host GPU仍不响应，最终以headless SwiftShader启动成功。没有清数据或读取登录页面，无法据进程重开证明登录仍有效。
- 当时宿主负载曾达380–413，首次构建12分13秒；不能把宿主资源争用造成的超时直接判成应用缺陷。没有终止其他任务。最终测试使用上述headless环境，不能推广至实体真机或所有模拟器渲染配置。

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME=/Users/acccan/Library/Android/sdk
./gradlew --no-daemon --max-workers=2 --dependency-verification strict \
  :probe:assembleDebugAndroidTest :probe:lintDebug
python3 -m unittest discover -s scripts/prototype/tests -p 'test_*.py'
adb -s emulator-5554 install -r probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk
python3 -u scripts/probe_lifecycle_avd.py emulator-5554 \
  --lab /tmp/epay-observer-accept-20261016-143716 \
  --directory "/tmp/observer-probe-auto-$(date +%Y%m%d-%H%M%S)"
```

脚本仅接受显式模拟器、qemu及主用户0；拒绝已有电池模拟/强制Doze和未知设置。证据目录必须不存在，权限0700，日志独占创建。可用`--stages`补跑受影响阶段；缺失阶段不能补记Passed。真正安全锁屏不绕过，来源版本/签名变化即停止。CI只构建测试APK并运行宿主模拟测试，不声称CI运行了真实支付宝。

## 本轮结果

最终代码`3beadca`，run4于2026-10-17 03:17 UTC开始，宿主脚本退出0、completed=true。11项基础设施结果Passed，真正锁屏1项Blocked；6个有测量区间的条件计数均为0，真实投递均Not exercised。

| 阶段 | 基础设施结果 | 实际证据 | 该条件通知投递 |
| --- | --- | --- | --- |
| 新进程启动 | Passed | IDLE、计数0、监听连接，未自动开始 | Not assessed |
| 三轮熄屏 | Passed × 3 | 20,421 / 20,013 / 20,069 ms，窗口RUNNING、监听连接；keyguard=false | Not exercised，均0 |
| 真正keyguard锁定 | Blocked | 原锁屏策略disabled=true，未改设置 | Not assessed |
| 断网/恢复 | Passed | 默认路由离线采样15,213 ms，恢复后默认路由存在，窗口仍RUNNING | Not exercised，0 |
| 强制Doze | Passed | PowerManager确认进入，采样15,036 ms，熄屏/监听/窗口正常 | Not exercised，0 |
| 熄屏等待窗口过期 | Passed | 135,348 ms后EXPIRED、监听连接；计数上限窗口仍120秒 | Not exercised，0 |
| 探针进程终止重开 | Passed | 只终止已准备好的探针PID，新进程IDLE、计数0、监听连接 | Not assessed |
| 探针强停重开 | Passed | 强停状态及旧进程退出已确认，新进程IDLE、计数0、监听连接 | Not assessed |
| 支付宝强停重开 | Passed | stopped=true且进程消失；正常启动后进程存在且stopped=false | Not assessed；登录未检查 |
| AVD保留数据重启 | Passed | 内存比较boot_id已变化、boot_completed=1；探针IDLE、计数0、监听连接 | Not assessed |

所有清理命令完成，网络设置、监听权限、屏幕状态、锁屏策略、reverse均与原值一致；电池模拟解除、强制Doze解除、支付宝正常重开，探针停止观察。AVD保留为可响应的无界面SwiftShader实例。

前后独立审计均为4笔订单、4条独立消费、4条收入、4笔成功topup、总增额30,000,000；固定基线与前后相等检查均通过。没有新增充值，也没有清历史数据。

私有证据在`/tmp/observer-probe-auto-20261017-run4/report.json`及同目录逐阶段日志；run1–3分别位于同前缀目录。构建日志`/tmp/observer-probe-reviewed-build.txt`、安装日志`/tmp/observer-probe-reviewed-install.txt`。这些临时原始产物不提交公开仓库，本报告只保留固定状态和聚合结果。

每项通知结果独立于基础设施结果：`Not exercised`表示已有测量窗口但条件内计数为0；`Not assessed`表示没有通知测量窗口。时间字段是最少等待时长和实际elapsed_ms。条件约每秒采样，并在最后采样通过后取结束计数；不能排除两个采样点间的短暂变化。

## 失败尝试与修复

| 尝试 | 真实结果 | 后续处理 |
| --- | --- | --- |
| run1 | fresh通过，所谓锁屏保持断言失败；清理/审计通过 | 预检发现AVD禁用keyguard，区分熄屏与真正锁屏，不更改策略 |
| run2 | fresh及三轮熄屏通过；offline基线失败；清理/审计通过 | 探针故意无INTERNET权限，UID的activeNetwork为空不能代表系统无默认路由；改测系统单行元数据 |
| run3 | offline读取系统元数据失败；清理/审计通过 | UiAutomation直接启动程序，管道没有被shell解释；改由sh标准输入执行固定过滤命令，仅返回默认路由有/无 |

run1–3保留原始失败记录，没有覆盖重跑或把它们补记通过。审查还发现fresh阶段日志覆盖、等待后未复核断网及仅比较审计前后相等的问题；现使用独立stage_id/排他日志、条件采样及固定四笔/增额30,000,000校验。宿主15项测试通过，包含异常清理、证据保留、锁屏政策、重启身份及错误稳定账务拒绝。修正后的严格依赖校验构建和lint通过；主探针未改动，原6项JVM测试通过。

## 已知边界和后续

instrumentation影响进程优先级；强制Doze与主动终止进程不能替代自然低内存回收、厂商省电或长时间待机。135秒测试只验证120秒隐私窗口结束，不是长时通知稳定性测试。断网只证明系统默认路由消失/恢复，未验证支付宝服务器连接或积压消息投递。支付宝强停重开只检查进程及stopped标志，登录状态未检查。

本批不再以用户点击为前置。没有受控发送端，真实正向投递无法自主制造；后续保留这一证据依赖，不代发消息或付款。微信尚未登录；真实内容、解析、订单归属、实体真机、签名发布仍未验收，#33/#42/#8保持开放，生产自动匹配关闭。
