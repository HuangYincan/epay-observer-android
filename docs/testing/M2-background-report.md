# M2 后台调度与恢复验证

2026-10-16；#23及#24状态子集、epay-go #6测试桥；SPEC-018 BG-01..05。仍为**模拟器合成通知原型**，只验证持久接收，不验证到账、订单匹配或充值。

## 实现与边界

固定WorkManager2.12.0，版本来自Google Maven官方元数据/POM/source；依赖锁与SHA校验更新。Room保存原事件/租约/重试时间，WorkManager只持久保存唤醒计划。即时工作KEEP、15分钟周期补扫描、独立延迟唤醒REPLACE；每次最多20次尝试/2分钟循环，不睡眠保活，不使用前台/加急任务或电池豁免。

规划时在锁内重新查询Room并采样时钟，等待WorkManager持久登记，避免旧worker覆盖较早计划。暂停/终态不被扫描恢复，租约过期后才可重领。配对恢复先启动，work遇恢复进行中会退避。状态新增最近持久接收时间及强停/系统延迟说明；#24完整事件页与诊断导出尚未交付。

**网络证据条件：** 本AVD默认网络被系统标为PARTIAL_CONNECTIVITY，不能满足默认CONNECTED的公网VALIDATED要求。正式构建仍使用原默认约束；fixture source set显式使用INTERNET/TRUSTED/NOT_RESTRICTED NetworkRequest，适配隔离localhost TLS桥。TLS、签名、精确origin和授权均保持；只有显式CA参数的fixtureDebug信任本地CA。`fixture-local-network-v1`标识不得进入Release。因此下面的自动恢复不能外推为正式构建、真机或公网网络条件已通过。

## 已执行的真实后台场景

同一干净schema、同一客户端依次完成五个场景，每个场景都由独立发送App发出新的微信/支付宝形态通知，内容不含订单信息。`fresh-*`只生成不同平台通知键，不复用旧key来绕过身份冲突。

| 场景 | 实际方法与结果 |
| --- | --- |
| 普通进程终止 | 离线落盘后，run-as仅终止专用debug UID的已确认PID；原PID退出，网络恢复后网关自动收到2事件/3请求（首次ACK响应丢失） |
| 模拟器重启 | 再离线落盘2条，实际adb reboot，恢复自身reverse/网络后网关累计4事件/5请求 |
| 强停 | 离线落盘后am force-stop；恢复网络观察3秒仍未新增事件；显式重新打开应用后累计6事件/7请求。3秒是本次观测长度，不声称测出无限时间保证 |
| Doze | 实际查询deep状态为IDLE后恢复网络，观察3秒仍未新增；unforce并唤醒后累计8事件/9请求。没有添加白名单或修改全局限制策略 |
| 无即时enqueue的扫描恢复 | 明确测试准备：取消即时/延迟/周期任务，保留Room事件，再仅安装周期任务的初次扫描；保持同一instrumentation进程等待，网络恢复后累计10事件/11请求。此项是测试准备的遗漏窗口，不冒充真实在两次提交之间杀进程 |

前四项先在宿主读取网关真实数据库计数，再启动verify instrumentation，防止打开验证进程本身触发发送而造成假阳性。第五项保持同一进程，期间无启动触发。所有事件只持久接收一次，首次ACK丢失产生一个额外request；两端payment_verified=false。

证据：[Android阶段结果](background-v1/evidence/background-android-result.json)、[网关结果](background-v1/evidence/background-server-result.json)、[APK与桥摘要](background-v1/evidence/artifacts.json)。最终桥运行134.14秒并PASS，schema正常清理。权限撤销/监听断连及原配对进程恢复继续由主AVD套件覆盖；不承诺补回系统未投递通知。

## 检查与复现

- strict构建、production JVM27 / fixture JVM141、productionRelease lint及fixture lint通过；最终主AVD38/38及8/8进程阶段通过（含反序规划、新通知键和既有采集/配对回归）。
- 默认/显式CA/Release/独立发送器精确APK检查；WorkManager仅新增受BIND_JOB_SERVICE保护的服务、私有重调度receiver和正常网络/唤醒/启动权限。保留的ProfileInstallReceiver受DUMP权限保护；没有公开的诊断receiver、前台服务或Startup provider。
- 原始证据 `/tmp/epay-observer-background-complete/`（私有，不上传）；主套件报告 `/tmp/observer-background-final-avd.txt`。已有API37 AVD与固定Go/PostgreSQL镜像同配对报告；未安装宿主工具。
- 按[配对本地步骤](../development/pairing-local.md)准备新私有目录与CA，构建显式CA的fixtureDebug/androidTest和fixture；桥设置 `OBSERVER_AVD_MODE=background`，运行 `ANDROID_HOME=... python3 scripts/background_tls_avd.py emulator-5554 <private-dir>`。桥仅绑定127.0.0.1:18443，设备脚本只重置专用fixtureclient.debug。
- 脚本会恢复自身网络开关、解除自身Doze、删除bootstrap及adb reverse。测试后重建不带CA属性的默认APK并再次执行verify_apk。不要上传grant、私钥或完整日志。

## 失败与修复

保留各失败轮目录，不把部分通过记为整套通过：`background-tls`的am kill未结束进程；`background-retry`因系统公网验证约束未满足而没有上传；`background-local`重复通知键被正确隔离；`background-fresh`对Doze输出大小写的测试判断失败；`background-final`的orphan测试在instrumentation结束后进程消失。最终分别改为确认PID的专用UID终止、明确fixture网络策略、新通知键、查询真实deep状态，以及在同一测试进程内等待扫描；没有放松通知身份、TLS、签名或ACK断言。

## Standards

0项硬性规范违规。低优先级建议“规划时钟应在锁内采样”已修复。新增source-set隔离、fresh通知键与测试证据结构复核未发现问题。

## Spec

三项发现已修复：并发旧due覆盖新计划，改为串行重读并增加反序回归；BG-01缺少无enqueue证据，补周期独立场景；最近ACK时间缺失，改从Room显示。复审认可实现与测试设计，运行结果单独按上述实际证据记录。

## 剩余工作

#24完整状态/脱敏导出、M3永久金额claim/匹配/事务记账/持久回调/New API余额一次性验证、最终完整复现仍待实现。真实微信/支付宝、实体真机及OEM后台兼容性没有证据。Goal保持active，不因后台ACK成功而结束。

## 跨库版本

- Android实现 `41e5ba9` / `f8f47e4`，[PR #52](https://github.com/HuangYincan/epay-observer-android/pull/52)。
- 网关仅测试桥 `184887b` / `2b8350d`，[PR #16](https://github.com/HuangYincan/epay-go/pull/16)。
- 最终head CI以两端PR Checks为准；未通过的远端检查不以本地成功替代。
