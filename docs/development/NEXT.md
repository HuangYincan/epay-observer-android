# 下一批实现边界

2026-10-16。长期Goal授权与完整A–E验收见[PROTOTYPE-GOAL](PROTOTYPE-GOAL.md)。目标仍是双渠道合成通知到New API真实测试余额更新；配对成功不等于目标完成。

1. **#17实际配对已实现并验证，继续保留接收联动追踪。** SPEC-016定义HTTPS inspect/redeem/activate、短期grant恢复与管理员撤销/轮换。Android pending以KeyStore AES-GCM保护，导入HMAC后激活Room；capture epoch阻止轮换暂停期间旧快照补绑。28项AVD主套件、8个进程阶段、双渠道实际HTTPS及TLS负例通过；报告M2-pairing-report。服务端独立KEK，不混用商户密钥；KEK运维重封装仍未实现。
2. **#20/#21/#25接收事务与实际上传已实现并验证。** SPEC-017在同一事务验权、登记request/event及pending，提交后ACK；持久设备限流、撤销/轮换并发、严格ACK、重放/冲突、响应丢失均有测试。33项AVD主套件和8个进程阶段通过；独立发送App双渠道合成通知实际签名HTTPS落库，历史UNBOUND保持不变。见M2-receive-upload-report；当前仅机会触发上传，仍需下一项提供后台恢复。
3. **#23后台调度已实现并验证，本批#24完整状态/诊断已验证。** SPEC-018接入WorkManager2.12.0，Room为真相源；38+8 AVD、实际进程终止/重启/强停/Doze/周期遗漏扫描通过，见M2-background-report。fixture网络策略仅用于隔离AVD局域网，production仍默认CONNECTED。ACK仍不表示充值；完整事件状态和可预览脱敏导出已按SPEC-019/020实现并验证，见M2-status-health-report；独立health不改变队列/记账。
4. **M3：#18/#22/#26/#27/#29与New API补丁。** SPEC-021永久金额claim与10分钟普通订单窗口已实现，见M3-amount-claims-report；SPEC-022事务匹配/消费/入账已通过双构建race、实际子进程恢复和Go TLS验证，见M3-matching-credit-report；下一步持久回调/New API金额修正与双渠道AVD余额闭环；普通测试订单不得通过通知携带ID。New API金额补丁已通过三数据库/完整model-controller race与两轴复审，受限本地TLS回调基础已验证，见M3-callback-foundation-report；继续实际AVD余额及回调崩溃/响应丢失/key轮换。

本机复用已有SDK/JBR/AVD与Docker，不安装全局工具。当前任务容器为epay-observer-go-tools、epay-observer-postgres及epay-observer-prototype网络；临时配对TLS桥测试结束即退出。Go使用固定1.26.9容器，网关开发隔离checkout为/tmp/epay-observer-go-pairing；原相邻仓库main未被覆盖。测试CA仅显式fixtureDebug/localhost，默认和Release仍system-only；后续safehttp回调不能直接放行内网，应设计独立受限测试链路。

生产/公网部署、真实账号资金、真机付款、正式APK发布及第三方上游推送不在授权内。真实来源自动匹配继续关闭；保留无法取得的外部证据，不把合成通过写成真实兼容性。
