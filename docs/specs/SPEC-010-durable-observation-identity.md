# SPEC-010 持久通知身份与本地候选

状态：2026-10-16，#16 实施基线；用户授权长程开发。依据 ADR-0005、SPEC-001/006/009。

## 本批事务边界

Room + SQLite schema v1 保存安装 epoch、观察身份和不可变本地候选。事件尚未配对：本批没有合法 device_id/receiver_id/binding_version，不能编造它们。持久候选分配 UUIDv4 event_id，保存 `local-candidate-v1` 私有 payload 与首次 observed_at；它不是 SPEC-002 的可发送 body，状态保持 UNBOUND 或 IDENTITY_CONFLICT。#19 在新观察发生时持有有效绑定的前提下，才会同事务封存 wire body 与 outbox；不得给旧 UNBOUND 候选补填新绑定后发送。

一次事务同时：读取／初始化安装 epoch → 定位身份 → 插入／更新观察 → 首次合格候选分配 event_id 并封存本地 payload → 提交。任一步失败全部回滚。只有事务提交才增加 UI 的“已持久化”计数；失败不能通过内存记录冒充已保存。

## 身份与隐私

- identity_key = SHA-256(长度前缀编码的安装 epoch、user serial、package、notification key)。身份哈希不含通知正文；不同 key 的相同正文不会合并。epoch 在数据库内持久化，重新打开／进程退出不重建。
- source fingerprint 包含 package、opPkg、版本与排序后的当前签名摘要；fields fingerprint 包含严格长度前缀的 title/text/bigText、channel/category/flags/group/template/additional 标记。哈希仅本地使用，不导出；低熵金额的哈希不被称作匿名化。
- 不将通知正文、原始 key 或签名集合落盘；只保存身份／内容摘要、来源包、user serial、版本、时间、revision 和状态。当前 UI 仍可保留最多 100 条进程内诊断；它与持久真相源分开。
- 本地候选 payload 只含本地 kind、event_id、provider、整数分、CNY、规则版本、首次 observed_at、identity_confidence=uncertain；没有支付流水号或订单号，不能直接提交事件 API。

## 状态机

1. 首次 live、合法时间、解析合格：一个 local event。首次恢复／未知／禁用／负例：保存身份墓碑，不创建 event。
2. 已有 key + 相同 source/fields/postTime：增加 deliveries，复用 event 与首次时间；恢复读取不重新分配 event。
3. 已有 key 的 source/fields/postTime 改变或移除后重投：revision 增加，conflict 粘性保持；已有 payload/id/金额/首次时间不改，只将事件状态改为 IDENTITY_CONFLICT。原来没有 event 的身份不会因修订或重新启用开关而追溯制造付款。
4. removed 仅标记通知消失，不删除身份、不撤销收款、不制造新事件。重新启动／应用升级不会清空映射。
5. 无映射的恢复观察、清数据／重装后的旧活动通知仍只能成为 uncertain 墓碑。清库不是消除服务端历史付款风险的办法。

## 回调、容量与错误

通知回调只复制允许来源的有界字段并提交到单线程、有界 128 项入口；数据库操作不在 UI／通知回调线程执行。入口满立即显示“可能漏采”，下一次可落盘工作保存粘性 gap 标记。系统没有消费 ACK，进程在投递至提交之间退出仍可能丢观察，不能承诺零丢失。

持久身份最多 10,000 条；达到上限拒绝新身份，保留旧数据且显示受阻，允许已有身份的重复／修订更新。schema v1 不自动删除墓碑；90 天 ACK 墓碑策略须在后续具备 ACK 和清理策略时实现。数据库打不开、迁移缺失、约束错误、磁盘异常均报固定故障类别，不输出 SQL、通知或路径；不得破坏性重建数据库。

通道配置在 Application 启动与 UI 用户操作时加载／保存，监听任务捕获当时的 enabled 集合；后续开关改变不能重算持久历史并产生新 event。

## 验收

- PERSIST-01：同 key 20 次并发、重复打开数据库／跨进程仍只有同一 event_id 和 payload；真实 SQLite 事务，不用 mock 代替。
- PERSIST-02：不同 key 同文案两个本地候选；更新金额、postTime、来源或删除后重发都不更改原 payload、不生成第二个 event。
- PERSIST-03：恢复已知映射复用、未知恢复只建墓碑；禁用后重启用不追溯制造付款。
- PERSIST-04：真实 SQL 触发器拒绝 event INSERT，身份和 event 都回滚；容量满保留旧记录；关闭／不可用数据库错误可见。
- PERSIST-05：AVD 系统通知 → 异步持久存储；结束进程再运行验证，同一 ID／payload／epoch 不变；数据库不存原文，备份与 Release 隔离检查继续通过。

API29、真实支付 App、真实设备、网关接收、资金确认和端到端发送仍未验收。#16 不通过手工填绑定、伪造订单号或运行账号绕过 #17/#19/#21 的依赖。
