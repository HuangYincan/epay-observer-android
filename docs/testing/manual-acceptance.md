# 人工界面验收：从哪里点、看什么

2026-10-16；SPEC-027 / #42，沿用SPEC-016/019/020/026。对象为当前用户已通过自动化验收的合成lab；本清单供用户亲手操作，不将自动化通过当作人工体验通过。准备状态：已有微信/支付宝配对、4笔成功充值、总quota130,000,000；40元暂停事件仍待核对。

## 当前机器

打开标题为`Android Emulator - Medium_Phone_API_37.0:5554`的窗口。观察器叫“收款观察器 · 测试”，发送器的应用标签为“通知夹具 · 仅测试”。观察器必须显示“隔离测试环境”。曾用于自动化的AVD以`-no-window`运行；本批已保留userdata重启为带窗口模式，没有清应用或重建绑定。

当前lab为`/tmp/epay-observer-accept-20261016-143716`，这是本机本轮路径，不是通用默认。新一轮用自己的目录。命令在仓库根目录执行，并确保`ANDROID_HOME`指向既有SDK。

```sh
observer_lab=/tmp/epay-observer-accept-20261016-143716
export ANDROID_HOME=/Users/acccan/Library/Android/sdk
# 自动化脚本结束会清理自己的reverse，人工使用时需要重新建立。
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 reverse tcp:18443 tcp:18443
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell am start -n dev.epay.observer.fixtureclient.debug/dev.epay.observer.MainActivity
```

已完成的自动化套件会撤销测试监听权限，重启后看到“未授权 / 未连接”并不代表已丢失配对。当前可见页面保留“微信已配对 / 支付宝已配对”，8个本地候选、6个已接收；它们含历史UNBOUND、重复和暂停事件，因此不应等于4笔成功充值。

## 第一轮：权限、授权检查、诊断（不发通知）

1. 在观察器页面向下滚动，点“打开通知读取授权设置”。在系统页面找到“收款观察器 · 测试”，由用户开启通知读取权限并返回。
2. 观察“通知读取权限：已授权”与“通知监听：已连接”。两者分开判断；已授权但未连接时点“重新连接监听服务”，不要重置应用。当前活动通知的重新投递不会被当成新的可自动入账证据。
3. 点“检查网关授权”。两渠道应显示检查通过。它不创建订单/事件、不修改发送暂停，不证明到账。如果失败先核对lab正在运行、reverse与当前APK的CA，不切换成明文或忽略证书。
4. 点“刷新本地事件”，核对金额/渠道/本地发送状态。已接收仅表示ACK；页面应继续明确显示网关记账/商户通知“未查询”。
5. 点“预览脱敏诊断”，核对只有版本、权限、绑定布尔值和状态数量；不含金额、标识、通知原文、凭据。点取消，返回页面。需要留档时再选择“选择文件保存”，由用户在系统选择器指定位置；不要发送私有目录或配对文件。
6. 宿主运行`python3 scripts/prototype/audit_recharge.py "$observer_lab"`，仍为4条消费/收入/成功充值、quota_added30000000。

记录实际观察，不因按钮可点击就填写Passed：权限恢复__，服务连接__，双渠道授权检查__，诊断预览/取消__，账务未变__。

## 第二轮：渠道开关与负通知（保持原账务）

观察器的“微信候选解析 / 支付宝候选解析”控制解析预览和后续新观察的解析，不是对历史事件重新确认到账，也不是网关暂停开关。

1. 打开独立发送器，若需要由用户点“允许发送通知”。点`wx-chat`和`ali-refund`，返回观察器看已忽略/未支持的原因，确认没有增加可上传的收款候选。
2. 分别关闭、开启两个解析开关，观察预览状态。关闭期间如果收到新观察，重新打开不能把旧身份追溯升级为新可上传事件。
3. 不在这份已有10/20/40元历史的lab重复执行正常充值脚本。要重新验证成功收款，用全新lab按prototype-demo.md从空数据操作；金额claim不会因清理通知、换绑定或重启释放。
4. 再运行audit，账务仍四笔。当前“停止自动确认”的业务操作只能通过管理员`admin_trace.py --pause`，不等同手机上的解析开关。

记录：负通知拒绝__，微信/支付宝开关各自生效__，旧观察未升级__，账务未变__。

## 第三轮：手工轮换配对（准备好点击时再发grant）

现有两渠道已绑定，优先用rotate保留设备/绑定代次。每次只操作一个渠道，期间不发送新的测试通知。取消尚未确认的授权不影响原绑定；确认后按正常KeyStore/Room流程轮换，旧凭据被撤销。

```sh
python3 scripts/prototype/manual_pairing_grant.py "$observer_lab" wxpay
# 仅在本机编辑器打开，复制token；不要把文件内容贴到聊天、Issue或命令行。
open -t "$observer_lab/manual-pairing-wxpay.json"
```

在观察器点“配对设备或轮换凭据”：

- 网关地址填`https://localhost:18443`。
- 短期授权填本机文件里的token；点“查看授权”。
- 核对环境等于当前lab、渠道wxpay、范围lab-receiver-wx、来源“隔离合成测试”、操作“轮换当前凭据”，再点“确认配对”。
- 完成后点“检查网关授权”，仍应两渠道通过；历史事件保持原身份，不会再次充值。
- 支付宝同理：脚本参数改为alipay，文件名为manual-pairing-alipay.json，确认渠道alipay / 范围lab-receiver-ali。

授权只有5分钟；脚本现在先比较明确指定AVD（默认emulator-5554）和TLS网关Date，超过3秒差异或无法核对时不会发放。其他模拟器编号用`--serial emulator-NNNN`。AVD长时间运行或宿主休眠后先核对时间；`adb shell cmd network_time_update_service force_refresh`失败时，保留userdata正常冷启动同一AVD并恢复reverse，绝不wipe或修改协议时间窗口。

到期且尚未确认时可以重新发放；如果已经点击确认并留下待完成配对，不能直接重复粘贴新token。先保留现场，由管理员核对对应provider/绑定代次，按SPEC-016撤销过期绑定，再取消本机待完成配对并用replace建立新绑定。已提交的收入和事件不能重写；不要仅凭通用错误文字判断某个绑定需要撤销。不要使用已被自动化消费的avd-grants.json。`--mode replace`用于确需替换设备的明确操作，会替代旧绑定，当前保留数据的轮换验收不需要它。脚本只在私有lab写0600文件，不复制设备密钥或替用户执行确认。

本批已验证辅助脚本的真实TLS创建/inspect和文件权限；用户通过UI确认轮换仍为待验收。若显示待恢复，先用“重试恢复配对”；到期或撤销按SPEC-016修复，不删Room或改历史事件。

## 结束

保存人工结论（Passed/Failed/Blocked及现象），不要上传token。退出前可删除本机短期授权文件和剪贴板中的授权；不删除服务端事件/claim。停止lab及移除本轮reverse：

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 reverse --remove tcp:18443
python3 scripts/prototype/stop_lab.py "$observer_lab"
```

真实微信/支付宝、真机、真实资金以及生产自动匹配不在本轮人工验收范围。

2026-10-16实际发现并修复时钟漂移及已过期pending恢复，见[时钟故障报告](manual-clock-recovery-report.md)。恢复是正常协议自动执行的修复证据，不计为用户手工轮换验收通过。
