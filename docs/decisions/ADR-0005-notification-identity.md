# ADR-0005 通知观察身份与收款候选身份

状态：隔离实现基线；2026-10-16 持久化细节见 SPEC-010；#4、#16，SPEC-001/002。真实支付 App 规则仍未验证。

## 依据与边界

Android [StatusBarNotification](https://developer.android.com/reference/android/service/notification/StatusBarNotification) 的 key 是通知记录实例标识；postTime 是通知发布的系统时钟时间，可能不同于 Notification.when。两者都不是支付流水或到账时间。getOpPkg 可能因 notification delegation 不同于 getPackageName；因此包名、通知代理、签名证书、user/profile 都要核验。不能仅检查可由应用内容控制的 title 或 channelId。

[NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) 要求等待连接后操作；重新读取活动通知不是交易历史。工作资料监听受平台限制；本轮定义单用户、单账户、每 receiver 一个活跃观察设备。分身、工作资料、账号切换、通知代理未知组合均 unsupported。账号变化无法从通知可靠察觉是残余风险，不宣称自动检测一定成功。

## 身份与持久化决定

1. 保存最小 NotificationObservation：安装 epoch、本地 profile 标识、source package/opPkg、签名集合摘要、版本、key、postTime、接收 wall/elapsed 时间、恢复读取标记、结构化字段摘要、规则版本。真实原文不进服务器／公共仓库；本地内容哈希可能泄漏低熵金额，诊断也不导出。
2. 在本地事务内用安装 epoch + profile + package + key 找到记录，保存观察 revision 与关联 ReceiptEvent。没有 key 则标记 identity_uncertain；不靠全文哈希生成交易身份。
3. 首次认可的候选分配随机 UUIDv4 event_id，并与**不可变的发送 body 字节**、队列项一并落盘。崩溃后重试读取原字节；request_id 每次请求重新生成。记录层可以增加 revision，已经封存的 ReceiptEvent 不改金额、时间、规则版本或 event_id。
4. 相同 key、相同语义与相同 postTime 的重复回调／恢复快照，复用既有候选，不再次入队。此规则只去重观察；它不能证明没有第二笔真实付款。解析器只有具备该版本通知生命周期的证据，才可把此类候选标记 distinct；未验证真实规则一律 uncertain。
5. 同 key 金额／语义改变，或相同内容但 postTime 改变：保存冲突观察，不制造第二笔可自动确认事件；标记 identity_uncertain。新 key 的相同金额／内容保留为独立候选（不合并），但新 key 本身也不能证明新交易；在历史付款／重复风险存在时不允许再消费。
6. 通知消失不表示付款撤销。删除后重发、重启后缺少历史映射、重装或清库，都不能重置服务端的历史付款保护。已 ACK 的本地身份墓碑保留 90 天（候选默认）；清理后恢复的旧通知只能 uncertain，不能因墓碑过期再次自动确认。
7. 两条无法区分的真实付款不可从不足的字段恢复；保留“可能多笔／身份不明”状态供账单核对。不能宣称一种 dedup 算法可以同时保证零漏记和零重复。

## 合成场景与预期（具体执行层次见各批测试报告）

| ID | 输入 | 候选与自动确认预期 |
| --- | --- | --- |
| ID-01 | 同 key/postTime/语义重复 20 次 | 一个 event_id；至多一次消费 |
| ID-02 | 同 key 从收款 10 元改为 20 元 | 原事件不改；冲突观察，暂停自动确认 |
| ID-03 | 相同内容、两个不同 key | 保留两个候选；不得按全文哈希合并；是否可消费由身份规则和历史策略决定 |
| ID-04 | 同 key 同文案但新 postTime | uncertain，不把它必然当成更新或新交易 |
| ID-05 | 服务重连／开机活动通知与历史一致 | 复用映射，禁止改 observed_at |
| ID-06 | 恢复活动通知但没有映射 | uncertain，不能自动匹配新单 |
| ID-07 | 群摘要、摘要展开 | 摘要 ignored；未知子项与既有关系 uncertain |
| ID-08 | 聊天伪装收款标题 | ignored/unsupported，不入账 |
| ID-09 | 跨 profile／分身／代理包不同 | unsupported，不跨 receiver 合并 |
| ID-10 | 两台设备观察同一收款 | 非活跃绑定拒绝；切换设备不清空金额历史 |
| ID-11 | 两笔真实付款在所有可见字段上相同 | 信息不足，不能承诺区分；账单核对 |

合成夹具使用 `dev.epay.observer.fixture` 自己的包名及证书，提供 wxpay/alipay 两组文案和上述生命周期；不提供订单 ID 或匹配提示。fixture 规则的 distinct 只能在隔离测试域成立，不能变成真实来源白名单。

#16 先持久保存本地候选的稳定 ID 与私有 payload；缺少配对上下文时不伪造完整 SPEC-002 body。#19 的 wire body/outbox 必须在绑定有效时随新观察原子创建；不能把旧 UNBOUND 事件补绑到新 receiver。
