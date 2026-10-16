# #19 持久队列验证报告

日期：2026-10-16；关联 PR #47、SPEC-011 / QUEUE-01..05，连同 #16 / SPEC-010 验证。使用原有 API37 / arm64 Google Play AVD；运行配置4GiB、1CPU。未安装额外全局工具，未部署服务、操作真实支付账号或发布APK。

## 结果

- 严格依赖校验的离线构建通过：fixtureDebug、productionRelease、androidTest、两个JVM变体与productionRelease lint（无警告）。
- JVM production **16/16**，fixture **130/130**；公共测试在两个变体重复运行。新增 wire 字段/金额/绑定校验、生产出站拒绝用例。
- AVD主套件 **20/20**（35.552秒），实际跨进程 prepare/verify **2/2**。包括两个数据库连接并发领取、租约到期、更换token拒绝旧回包、无效ACK、重试字节不变、换绑/撤销/身份冲突、50条双通道离线数据、outbox SQL abort事务回滚、容量墓碑、schema1→2迁移。
- 跨进程测试先保存50条已绑定的隔离合成事件和1条UNBOUND候选，然后结束fixture客户端进程。新进程核对不同PID、相同epoch/ID/body/首次时间，并恢复过期的IN_FLIGHT租约。仅测试数据库/参考文件在验证后删除，不清空工作数据库或AVD。
- productionRelease与fixtureDebug APK包名、权限、备份、网络、组件与来源隔离检查通过。首次APK检查命令漏设JAVA_HOME，修正为已有Android Studio JBR后通过；没有安装Java或修改系统环境。

原始证据：[主套件](evidence/outbox/instrumentation.txt)、[进程准备](evidence/outbox/instrumentation-process-prepare.txt)、[进程恢复](evidence/outbox/instrumentation-process-verify.txt)、[JVM计数与schema摘要](evidence/outbox/verification.json)。

## 实现与限制

Room schema2新增outbox，显式迁移保留schema1数据。观察身份、本地候选和可发送body同事务持久化；无有效采集时绑定就保持UNBOUND，历史不补绑。固定body和业务ID不随重试变化，发送租约与未来HTTP request_id分开。

运行时配对/上传入口尚未接入；绑定和ACK由androidTest直接输入存储契约，不是远端网关接收证据。ACKED只表示持久接收；没有客户端“充值成功”状态。Release当前拒绝所有未验证出站组合。

32MiB是payload预算，不是数据库文件配额；10,000身份/队列上限，无自动清理。容量超限保留墓碑并显示缺口；SQLite真实磁盘耗尽未运行，SQL abort不能替代该项。进程在系统通知投递到数据库提交之间仍可能丢观察。wall-clock回退可能延迟租约恢复，不刷新observed_at。

API29、真实微信/支付宝、真机、真实断网/HTTP重传、签名上传、网关接收/记账、New API充值均 **Not run**。#8/#10的业务准入阻塞不因本报告解除。后续先完成配对与上报的明确契约和实现，再做端到端故障验收。
