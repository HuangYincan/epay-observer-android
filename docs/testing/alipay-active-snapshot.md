# 现存支付宝通知的私有模板检查

## 最新追加类型核验实测（2026-10-18，#84/#82）

用户明确允许最多2条现存标题/正文和必要类型/样式/分组元数据，并要求本任务后续必要核验自主推进，无需逐次询问。此最新授权覆盖下文历史阶段的许可等待；不自动持续监听、不读通知key/账号、不代付款。

在`30b208c6`基线新增固定classification scope；来源/数量/原子发布沿用SPEC-037。metadata只含白名单category/style及channel存在、消息extra存在、group/summary/ongoing/auto-cancel布尔值，不导出channel/group字符串。宿主保留有限公共固定词并对完整标题模式作研究性匹配，姓名/金额/普通消息仍遮盖；所有结果固定`payment_eligibility=false`。

- 严格依赖、离线AndroidTest构建及sample lint通过；最终APK manifest/dex检查通过：无INTERNET、准备组件DUMP保护、listener系统绑定权限、posted无字段/方法调用、不取key/postTime/group字符串、不使用网络或日志；备份/设备传输全部排除。APK SHA-256为`1d7fcb05c40d9e467c4ef2b1202ef9b56203fe7d2cc13e8bdc12c33ef71a79fb`。
- 受影响宿主回归7项通过：原4项状态/超时测试，加3项脱敏、全文模式和元数据拒绝测试，均为合成输入。先前扩展宿主检查37通过、1项旧HTTP控制页测试被沙箱拒绝绑定；环境更新后仅补跑该项通过，不重跑旧生命周期或合成充值验收。直接Java静态编译也通过，不替代APK检查。
- 此前Gradle重试多次被自动审批服务繁忙阻断，均未执行；环境更新后的本次执行成功。唯一AVD仍为emulator-5554；一次`adb shell true`在8秒超时，后续3.262秒返回，再后续qemu/user/listener查询为0.057/0.082/0.111秒。未重启AVD/ADB/支付宝、未改变后台设置；该恢复不证明卡顿根因或后台问题修复。
- **模拟器真实App历史snapshot：COMPLETE/2，脱敏成功、cleanup_ok=true。** 一条无文本、group_summary=true；另一条是group child，标题骨架为`你已成功收款<number>元（<text>）`、text为未知文本片段、bigText为空。两条category/style均none、messages_present=false；child为auto_cancel=true。摘要不是收款候选；无聊天标志也不证明不能被聊天伪装。
- child未匹配研究中的任何完整固定标题模式。括号字段与正文含义仍未知，不能把`<text>`当固定方式或已核验动态姓名，不能据前缀启用真实收款解析/自动确认。
- 宿主/设备原文及临时文件已删、测试listener撤权、测试包强停、监听列表恢复；两代旧marker/COMPLETE状态原样保留。前后独立只读账务审计均为4订单/4消费/4收入/4topup、总增额30,000,000。classification marker也永久保留，不重置或重放历史通知。

私有证据：`/private/tmp/observer-active-snapshot-20261018-classification-run1`（report/templates/accounting-audit）与`/private/tmp/observer-template-classification-20261018`（准备/构建产物检查）。无原文提交公开仓库。实际付款→New API增额仍Not run；#82/#8/#33/#42与后台#81的验收边界不变。下一步先消除未知字段和聊天伪装边界，再登记真实试验规则；当前无需用户付款。

2026-10-18；#84/#82，SPEC-037。用户明确允许一次读取现存最多2条支付宝标题/正文，原文仅本机私有保存，脱敏后删除；未允许继续监听或付款。

Android仅androidTest APK新增受系统绑定权限保护的快照服务，无网络权限，固定来源包/opPkg/UID/当前用户、版本和证书。连接后一次读取activeNotifications且最多2条；posted回调不读字段。服务使用独立永久marker，与旧采样窗口无关。无界面准备组件仅启动测试APK，不打开支付宝。

实际结果分开保留：

- run1：API37不支持预想的测试包准备命令，失败发生在consent/正文读取之前。后续只读检查确认无raw/status/consent/used文件；权限恢复和四笔审计通过。该报告不覆盖。
- run2：Android最终固定状态为COMPLETE/records=2，永久used已消费。宿主在状态文件尚未完整写完时读取，JSONDecodeError后执行隐私清理，**未提取模板**；原文已删除，不能恢复或把这两条认定为收款通知。原report和补充metadata并存，不改写失败为成功。
- 清理：测试listener已撤权、测试包强停、宿主/设备原文删除，监听列表与原值一致；原隔离账务仍4订单/4消费/4收入/4topup，总增额30,000,000。没有实际订单确认、New API操作或付款。

修复：服务完整写临时文件后原子rename发布raw/status；宿主等待空文件/所有不完整JSON前缀，严格校验完整状态。新增3项合成元数据回归通过，含每一个JSON前缀与超限/伪类型/新通知范围拒绝；严格依赖androidTest构建和sample lint通过。最终APK为测试包，服务绑定权限及准备组件DUMP保护、无INTERNET已检查；不发布APK，不重跑旧生命周期。

修复后没有再次读取。一次授权已经消费，需要新的限定范围才能追加检查；不清除used、删除原报告或自动新建采样窗口。真实模板和真实付款→New API增额仍未完成。所有私有目录均在`/private/tmp/observer-active-snapshot-20261018-run1`、`run2`，原文不存在，未提交任何通知内容。

## 用户另行授权的固定恢复范围

用户随后再次明确允许额外一次同样范围的最多2条检查。使用独立recovery consent、marker及原文/状态路径；旧used和COMPLETE/2状态必须存在，旧原文必须不存在。没有清除或覆盖旧记录，没有任意session或自动续读入口。

恢复run1在读取监听设置时超时；run2在安装阶段等待超时；run3在监听设置预检超时。三次均未准备consent或消费recovery marker。只读SHA核对证明run2安装其实已提交，因此宿主增加“已安装APK精确hash相同则不重复安装”；不放宽支付宝版本/签名检查。所有失败目录保留。预检尚未开始任何修改时，报告明确无监听变更，不虚称完成前后比对。

最小控制面反馈曾为guest shell/settings 8秒超时。重建仅连接本任务AVD的宿主adb后短暂恢复；停止代理自己启动的5秒一次后台dumpsys监测后，两项查询分别0.023/0.031秒，随后恢复读取完成。该对比不证明支付宝退出根因，也不证明永久修复或长期后台可靠性。监测日志保留、状态标为受控停止且观察有缺口；未重启AVD/支付宝、未改后台系统策略。

**恢复run4实际COMPLETE/2，私有脱敏提取成功、cleanup_ok=true。** 一条没有文本，另一条提供含收款字样和金额占位符的标题线索；不保存或公开付款金额、姓名、完整文案。原文在宿主/设备均已删除，测试监听撤权、进程停止、原监听列表恢复。后续元数据审计确认两代marker都存在、旧状态未变，原四笔/总增额30,000,000账务不变。新增授权实际只读取这一次，未监听新通知。

实际采样APK SHA-256为`14189de3ef7ecba8ed4544b5aff2cc1b2000f8fdedb3c8df803933cb473cf2a5`。后续为测试APK显式增加备份/传输排除与明文禁用声明，仅构建/检查，未再次安装或读取。4项合成宿主回归（包括短暂adb超时后保留已完成结果）、严格依赖AndroidTest构建/lint与最终APK检查通过。合成测试和历史snapshot不能证明真实付款兼容性。

私有证据位于`/private/tmp/observer-active-snapshot-20261018-recovery-run1`至`run4`。脱敏骨架仍缺固定文案与通知类型核验，不能据此写宽泛关键词规则或启用生产上报/确认；#82/#8/#33/#42保持开放。下一批需先明确限定的模板/类型核验范围，不能自动增加读取次数；不要现在催用户付款。
