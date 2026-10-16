# 长期原型验收账本

2026-10-16用户Goal：独立合成通知App→Android持久采集/签名HTTPS→epay-go明确匹配/持久收入与回调→New API测试quota恰好一次。当前运行验收如下；最终PR/CI/维护记录以[完整报告](../testing/M3-prototype-report.md)为准。

| 完成要求 | 当前证据 |
| --- | --- |
| A 构建安装启动、双渠道开关、状态/错误、正式测试隔离 | strict构建/JVM/lint；AVD43+8；SPEC-019状态及脱敏导出；默认/显式CA/Release/发送器精确APK检查 |
| B HTTPS配对、独立凭据保护、激活/撤销/轮换 | SPEC-016/M2-pairing-report；KeyStore、受保护pending、Room、双渠道实际HTTPS；不持有商户key |
| B 上传、验签/重放、原子落库/ACK及恢复 | SPEC-017/018，实际双渠道、ACK丢失、进程终止/AVD重启/强停/Doze/遗漏扫描；历史UNBOUND不补绑 |
| C 同环境双渠道普通订单→匹配→回调→New API | 干净lab实际10元双渠道；各一次；逐笔跨库关联。20元故障单恢复后累计4笔，quota增加30,000,000 |
| D 重复/更新/新key、重放/同额/迟到/复用 | 分层矩阵见最终报告：AVD身份、PostgreSQL匹配和实际TLS；未知/冲突不自动入账 |
| D ACK/账务/回调关键崩溃与幂等 | 实际子进程commit前后退出；完整lab首次503杀网关、New API已提交丢响应后两进程SIGKILL；20并发TLS回调零追加额度 |
| E 固定版本、配置、构建/启动/清理、合成数据演示 | prototype-demo.md及scripts/prototype；固定镜像摘要、New API补丁/AGPL许可、私有产物SHA |
| E 干净复现、脱敏证据、CI和仓库维护 | recharge-v1/evidence；保留失败uncertain历史；最终PR/CI/Issues记录见最终报告 |

验收只覆盖“模拟器合成通知原型”。生产自动匹配关闭；真实App、真机、生产准入和发布没有证据。永久金额claim不能证明真实付款归属；没有手动通知到账旁路。完成最后仓库维护前Goal保持active，全部A–E交付后才标记complete。
