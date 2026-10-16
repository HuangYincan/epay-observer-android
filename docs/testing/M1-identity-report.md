# #16 持久身份验证（schema v1）

日期：2026-10-16；SPEC-010 / PERSIST-01..05。Room 2.8.5、KSP 2.3.6，固定项目依赖；没有新全局工具／服务部署／真实账号操作。

本地严格离线构建、JVM production 13/13 与 fixture 128/128 通过（共同用例在两变体重复）；AVD 主套件 12/12（33.952 秒），跨进程 prepare/verify 两阶段 2/2。新测试使用真实 SQLite：两个独立连接并发 20 次只有一个 event；不同 key 不全文合并；修订保留原 payload/id/金额；恢复／禁用后的旧身份不追溯造事件；SQL trigger 失败回滚 epoch、身份、event；容量与表缺失错误；数据库文件不含原始 key、正文和证书标记。

跨进程测试显式结束应用进程，再重新进入 instrumentation，并比较不同 PID、相同 epoch/event_id/payload/首次时间。测试专用数据库文件独立命名并在验证后清理，不清空观察器工作数据库或 AVD。

本地候选仍 UNBOUND，不能提交事件 API；没有发送、网关 ACK 或充值。这一阶段为 #19 冻结发送字节与队列恢复提供事务基线。Room schema v1 已导出，后续 schema v2 必须显式迁移和验证，不允许 fallbackToDestructiveMigration。

依赖核验最初因 Gradle runtime 别名不同而未找到比较项，已修正核对脚本；发布值、实际缓存和 metadata 三者相同，没有修改已下载内容或放宽严格校验。证据见 [依赖核验](evidence/identity/dependency-checks.json)。

API29、真实支付 App、真机、实际上报均 Not run。入口到提交之间系统没有 ACK，仍可能在崩溃时丢观察；有界入口或数据库错误会显示保存缺口，不宣称零丢失。墓碑没有自动删除策略，达到容量会停止接收新身份。
