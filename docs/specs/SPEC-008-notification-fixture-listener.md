# SPEC-008 独立通知夹具与监听边界

状态：#11/#12 实施基线，2026-10-15；FR-01/02/07/08。#9/#5 已合并，M0 的真实来源与自动收款准入阻塞继续有效。

## 交付范围

独立 `fixture` Android application 模块，包名 `dev.epay.observer.fixture`，仅 debug 变体；观察器只有 fixtureDebug 允许该来源。合成数据经过 Android NotificationManager → NotificationListenerService 实际投递。未实现解析器、ReceiptEvent、金额转换、持久事件队列或网络上报；本批 NotificationObservation 绝不充当支付事件。

## 来源与隐私

1. 在读取 extras 之前检查系统 package、opPkg 和当前 user。拒绝代理、其他用户和未注册来源，不保存其 key、内容或包名，只累计拒绝数量。
2. production 的来源注册表为空：真实 App 签名／模板尚未验证。fixture 源码集独立声明包可见性，并校验发送 App 当前签名集合与观察器自身当前签名集合相等且非空（同一次本机构建的 debug key）。不信任任意安装的同名 APK；这是隔离实验授权，绝非真实到账证明。不支持证书轮换，变化即拒绝。
3. 只复制 title/text/bigText 三个 CharSequence 为有长度上限的 String，保留 channel、category、flags、group 信息。异常／非 CharSequence／超长字段不截断成合法支付输入，标 unsupported；不反序列化或保存自定义 extras、PendingIntent、URI 或 Parcelable。
4. UI 只展示计数和状态，不显示／日志输出通知内容、key、签名或身份。内存诊断最多保留 100 个 key，达到容量拒收新 key 并明确告警；不会静默淘汰后把旧通知当新付款。进程退出丢失诊断，后续 #16/#19 负责持久化。

## 身份与生命周期

观察键是本次进程 epoch + user + package + notification key。保存来源版本／签名摘要、postTime、首次观察 wall/elapsed 时间、最新观察及 revision、是否恢复读取、是否 removed。相同 key 的重复观察只增加观察次数，内容或 postTime 改变增加 revision 并标 identity_uncertain；不同 key 同文案分别保留，不按文本哈希合并。所有观察都不具备支付资格；恢复读取始终标 restoration，不改已有首次时间；无映射的恢复项标 uncertain。删除只记 removed，不撤销／制造付款。

权限由系统 API 查询，连接状态仅由服务回调驱动，二者分别显示。onListenerConnected 后才读取 activeNotifications；onListenerDisconnected/onDestroy 清除连接状态。用户可打开系统授权页或在有权限时请求重连；不无限保活。回调没有网络／磁盘持久化操作，读取包信息是本地系统 IPC；失败仅记录固定错误类别。

## 夹具场景（仅合成，非真实文案认证）

| 场景 | 通知投递 | 用途 |
| --- | --- | --- |
| wxpay / alipay | 两组收款形态，各 10.00 元 | EMU-01/02 输入层 |
| identical-pair | 不同 id，完全相同文案 | 不按文案合并 |
| update | 相同 id 更新金额至 20.00 元 | 同 key revision |
| repost | 相同 id 和文案再次 notify | 系统重投／postTime 观察 |
| summary / child | 群摘要与子项 | EMU-03/04 输入层 |
| refund / expense / chat | 退款、支出、聊天伪装 | EMU-03 负例输入层 |
| malformed / oversized | 非 CharSequence／超长 text | 安全读取 |
| clear | cancelAll | 删除与恢复测试 |

场景名仅作为夹具 Activity 的本地命令，不写入通知自定义 extras；通知不包含订单号、匹配提示或虚构支付流水号。所有通知使用独立测试 channel 和自身包名。开启通知权限是发送器行为，与观察器通知读取授权分开。

## 验收

- CAP-01：双通道、负例、摘要、更新、同文案两条经系统投递；检查 key/revision/字段与 flags，而非充值结果。
- CAP-02：包名／代理／用户／签名拒绝的 JVM 测试与无关包的 AVD 投递；拒绝发生在读取内容前。
- CAP-03：授权、连接、撤权、断连与重连恢复测试；恢复不产生 ReceiptEvent，保留首次时间。
- CAP-04：非 CharSequence／超长字段 fail closed；有界内存容量及 removed 行为测试。
- CAP-05：productionRelease 最终 APK 不含夹具来源、场景或信任代码，service 受系统绑定权限保护；fixture 模块没有 release 变体。

以上只覆盖 EMU-01..04/10/14 的通知输入与监听子集，完整支付验收依然 Not run。API29、真实支付 App、实体真机未验证。

## 平台依据

- Android NotificationListenerService：连接后才可获取活动通知；断连后仅 requestRebind 安全，服务声明 BIND_NOTIFICATION_LISTENER_SERVICE。
- NotificationManager.isNotificationListenerAccessGranted：独立查询权限。
- PackageManager GET_SIGNING_CERTIFICATES / SigningInfo.apkContentsSigners：当前签名集合；本批不隐式接受历史签名。

官方参考：https://developer.android.com/reference/android/service/notification/NotificationListenerService 、https://developer.android.com/reference/android/app/NotificationManager 、https://developer.android.com/reference/android/content/pm/SigningInfo 。
