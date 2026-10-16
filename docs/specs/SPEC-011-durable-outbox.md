# SPEC-011 持久事件队列

状态：2026-10-16，#19 实施基线；依赖 SPEC-010、SPEC-002 的 observer-events-v1-rc1。只实现本地存储契约，无配对、HTTP、签名、WorkManager 或服务部署。

## 捕获事务与绑定

Room schema v2 新增 outbox，显式迁移保留 v1 身份及本地 payload，不为历史 UNBOUND 候选生成发送任务。首次 live 合格观察、身份、本地候选、outbox 必须在同一事务提交。body 按 SPEC-002 序列化一次；保存确切字节，不随重试、迁移、配置更改重新序列化。

调用者传入采集瞬间的 BindingSnapshot；包含 HTTPS 网关 origin、环境 ID、设备 ID、收款绑定 ID、binding_version、provider、source_class。ID 使用协议 ASCII 范围；origin 只允许规范 HTTPS 主机及可选端口，无 userinfo/path/query/fragment。origin 校验不是 SSRF 防线或服务部署授权。绑定范围 hash 包含上述全部字段，不含可轮换 key_id。存储事务必须确认该 scope 仍是该 provider 的当前有效绑定；撤销或更换绑定与落盘/领取共享数据库事务边界。

本批运行时无配对输入，正常采集仍为 UNBOUND。只有 androidTest 向存储契约提供隔离合成绑定，没有测试配对 UI／Intent／HTTP 旁路。fixture 构建只允许 synthetic 与已知夹具规则；production 规则尚未验证，出站策略全部拒绝。identity_confidence 继续 uncertain；不添加订单号或虚构 provider_transaction_id。以后配对必须基于网关授予范围，不能信任用户自行填写的环境/来源标签。

## 队列状态与恢复

- READY：落盘可领取；IN_FLIGHT：事务领取，随机 leaseToken、leaseUntil、attempts+1；RETRY：到 nextAttemptAt 后可领取；ACKED：已持久接收；HELD：需处理的阻塞；TERMINAL：明确终态拒绝。
- 领取只选相同有效绑定下 READY、到期 RETRY 或过期 IN_FLIGHT。租约到期可再次领取，新 token 阻止旧尝试的迟到回包修改状态。成功/失败处理都必须匹配当前 IN_FLIGHT token；不以 event_id 单独确认。
- ACK 核对版本、event_id、HTTP/receipt_status（202/accepted 或 200/duplicate）、有效 received_at 和 processing_status。ACKED 表示接收端持久化，不表示网关或 New API 充值；本批不生成“充值成功”状态。无效 ACK 不确认事件，由上传层安排重试或租约恢复。
- RETRY 保留 body/event_id/observed_at，不生成请求封套；#21 每次发送时生成新的 request_id/sent_at。退避和 Retry-After 解析归 #21。
- identity conflict、绑定更换或撤销使尚未结束的 outbox HELD，清除租约；已 ACKED/TERMINAL 数据不改写。撤销后的迟到 ACK 无效；本地不能撤回已发出的请求，服务端仍须验证绑定 generation。
- 同一绑定 key 轮换不变 scope；旧绑定重新激活不自动恢复 HELD，不重写 receiver。没有自动释放 held 或重绑定 API。
- 本地 wall-clock 倒退可能延迟租约到期；不修改事件时间绕过。时钟恢复或后续上传诊断处理；跨进程不依赖内存计时器。

## 容量、保留与故障

身份上限仍为10,000；outbox条数上限10,000、保存的本地候选+wire payload合计上限32MiB（是应用 payload 预算，不是 SQLite 文件/磁盘配额，索引和 WAL 另需空间）。ACK前后均不自动删除；90天清理尚未实现，必须保留身份墓碑后才能另行设计。

达到 payload/outbox 预算仍写入该观察的墓碑，不创建 event/outbox，并持久标记 capacity/采集缺口；容量恢复后重复投递同 key 不重新造事件。真实磁盘/SQL失败导致事务回滚、应用显示数据库错误，绝不清库伪装成功；磁盘恢复前无法保证墓碑可写，不能宣称零丢失。已有事件读取、ACK与重试不依赖剩余容量。

## 验收

- QUEUE-01：50条离线双通道候选完整保留，关闭重开与实际结束进程后 ID/body/首次时间不变；ACK前不删除。
- QUEUE-02：并发领取唯一租约；到期重新领取；旧 token/错误 event/无效 ACK 拒绝；ACK与充值含义分开；重试原字节不变。
- QUEUE-03：换绑/撤销/通知修订暂停待发送或在途事件，历史不追溯补绑，fixture与production出站隔离。
- QUEUE-04：outbox INSERT 的真实 SQLite abort 回滚 epoch/identity/local event/outbox；容量保留墓碑和既有记录；数据库异常可见（继承 PERSIST-04/05）。不把 SQL abort 写作真实磁盘耗尽测试。
- QUEUE-05：从导出的 schema v1 建库、插入旧数据、运行1→2迁移并验证结构、ID/payload不变、outbox为空。没有 destructive fallback。

API29、真实App/真机、网络ACK/断网重传、网关落盘和自动充值均未验收；测试中的 ACK 是存储契约输入，不是远端证据。
