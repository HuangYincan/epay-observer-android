# 下一批实现边界

2026-10-16。长期Goal授权与完整A–E验收见[PROTOTYPE-GOAL](PROTOTYPE-GOAL.md)。目标仍是双渠道合成通知到New API真实测试余额更新；配对成功不等于目标完成。

1. **#17实际配对已实现并验证，继续保留接收联动追踪。** SPEC-016定义HTTPS inspect/redeem/activate、短期grant恢复与管理员撤销/轮换。Android pending以KeyStore AES-GCM保护，导入HMAC后激活Room；capture epoch阻止轮换暂停期间旧快照补绑。28项AVD主套件、8个进程阶段、双渠道实际HTTPS及TLS负例通过；报告M2-pairing-report。服务端独立KEK，不混用商户密钥；KEK运维重封装仍未实现。
2. **下一批#20/#21/#25：接收事务与实际上传。** 在同一事务检查环境/receiver/device/binding/credential有效性、登记request/event及pending，提交后才ACK。撤销/轮换并发必须与接收锁顺序一致；严格ACK、重放/冲突、响应丢失、跨环境和断网恢复必须运行。不得直接把RecordEvidence挂成接收接口。
3. **#23/#24：后台调度与状态。** Room为真相源，系统调度可恢复；权限/服务/配对/网络ACK/充值分别显示。当前没有WorkManager，不宣称后台可靠送达。
4. **M3：#18/#22/#26/#27/#29与New API补丁。** 按隔离合成策略实现永久金额claim、明确10分钟窗口、事务消费/入账/持久回调；普通测试订单不得通过通知携带ID。New API独立checkout已获授权，金额核对缺口需修复和幂等验证，不能因历史“未授权第三仓库”停止。

本机复用已有SDK/JBR/AVD与Docker，不安装全局工具。当前任务容器为epay-observer-go-tools、epay-observer-postgres及epay-observer-prototype网络；临时配对TLS桥测试结束即退出。Go使用固定1.26.9容器，网关开发隔离checkout为/tmp/epay-observer-go-pairing；原相邻仓库main未被覆盖。测试CA仅显式fixtureDebug/localhost，默认和Release仍system-only；后续safehttp回调不能直接放行内网，应设计独立受限测试链路。

生产/公网部署、真实账号资金、真机付款、正式APK发布及第三方上游推送不在授权内。真实来源自动匹配继续关闭；保留无法取得的外部证据，不把合成通过写成真实兼容性。
