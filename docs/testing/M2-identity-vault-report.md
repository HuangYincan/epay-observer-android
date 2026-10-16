# #15 身份模型与 #17 KeyStore 子集

2026-10-16；SPEC-014 / MODEL-01..04、SPEC-015 / VAULT-01..03。网关PR #13；Android PR #49。使用现有CI和4CPU/4GiB API37 AVD，无新全局工具/服务部署/真实账号/APK发布。

## Android已运行

严格离线构建、两个JVM变体（22/136项）、productionRelease lint与两种客户端APK隔离检查通过，无新依赖。实际AVD **25/25（23.482秒）**，跨进程 **2/2**。

新增4项真实AndroidKeyStore测试：公开合成HMAC key导入后encoded为空，实际RequestSigner匹配既有golden；输入数组在成功/失败后清零；同key幂等、不同key不覆盖；scope/key_id隔离、非法输入和删除/缺失；8次并发跨vault实例仅创建一次。原跨进程测试增加KeyStore恢复验证：结束应用进程后重新加载同alias并签同golden，随后仅删除本测试alias。Room原有51条持久候选/50条队列恢复继续通过。

[evidence](evidence/identity-model-vault)保留原始主套件和两阶段报告。这个结果证明AndroidKeyStore API行为，不证明硬件隔离/StrongBox；输入清零不保证JCA/系统临时副本可全部擦除。没有验证两个独立进程同时导入；实现使用进程内mutex及私有文件锁串行化。

## 网关已运行

初版模型在真实CI PostgreSQL通过并发迁移/旧数据保留/DDL回滚/版本漂移、身份与凭据约束、12路事件并发、冲突和deferred commit失败。随后补充首次key/request审计、直接INSERT范围负例和原迁移入口验证，代码9bf7881也通过全部Go测试；gofmt差异已应用。最终功能代码e0580d8在run38840015908的完整CI通过：8项真实PostgreSQL模型测试及既有Go/race回归、fuzz、vet/build/gofmt/govulncheck、前端与两个镜像。额外验证了数据库默认REPEATABLE READ下显式READ COMMITTED仍可并发去重，外层事务回滚也会撤销嵌套证据。摘要与迁移SHA见evidence/identity-model-vault/gateway-ci.txt和verification.json。

迁移默认创建空且disabled的环境；不会凭新增表启用观察器。RecordEvidence返回stored/duplicate/conflict，不是ACK；凭据活跃/撤销、请求重放和接收事务仍待#20。新表保护旧数据和不可变证据，不更改原订单支付/账本/商户回调；密文槽位的结构测试不等于已实现服务端KEK加解密。

## 未运行与后续

HTTP配对/正常TLS发放、受保护的pending配对日志、服务端KEK/轮换/撤销联动、重装全链路、实际网络ACK、API29与真机均Not run。#17保持开放；#8/#10仍限制真实匹配/M3。SPEC-015只收敛配对恢复候选，最终HTTP错误/轮换路由和双方向量在实现前补齐。
