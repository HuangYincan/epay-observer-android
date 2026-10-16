# #21 签名与退避核心验证

日期：2026-10-16；SPEC-012 / SIGN-01..02、RETRY-01；#21为部分交付，不关闭完整上传任务。

## 已验证

实际Kotlin RequestSigner使用原始body，三个既有Python/JCA黄金向量逐字节一致；每次prepare重新取得request_id/sent_at，防御性复制body/header，不在toString中泄露签名。HTTP状态分类、指数full jitter、Retry-After秒/日期及溢出边界完成纯逻辑测试。未新增依赖。

严格离线构建、productionRelease lint通过；JVM production **22/22**、fixture **136/136**（公共测试重复运行）。Android实际JCA签名和日期解析也通过，最终API37 AVD主套件 **21/21（28.249秒）**，实际跨进程 **2/2**。其中前三组golden使用公开合成32字节key，不是Keystore密钥或真实凭据。

原始证据在 [evidence/signing](evidence/signing)；[计数与向量摘要](evidence/signing/verification.json)、[最终instrumentation](evidence/signing/instrumentation.txt)。epay-go纯协议库同步使用同一vectors文件，跨库CI报告另见 [网关核心报告](M2-gateway-protocol-report.md)。

## 模拟器失败与恢复

原1CPU运行实例在本轮全套复测中240秒超时；logcat/dumpsys均曾超时。正常关闭并按原配置冷启动后仍有Launcher/SystemUI/Settings等系统ANR，cpuinfo显示单CPU100%，SurfaceFlinger约75%。该次套件最终完成21项，1项失败：Foundation的公网HTTPS探针对example.com出现UnknownHostException/EAI_NODATA；新增签名测试通过。DNS失败与系统ANR分别记录，不能断言两者有已证明的因果关系或把失败写成通过。

随后以同一AVD/API37/用户数据，临时参数 `-cores 4 -memory 4096 -no-snapshot-load -no-snapshot-save` 冷启动，通过上述21+2测试。config.ini前后SHA256相同，未wipe、未清数据、未安装新镜像/SDK，未操作真实App。该结果的资源基线是4CPU/4GiB，不能宣称1CPU稳定性已解决。运行参数、首次失败、原配置重试及ANR/CPU证据均保留。

## 限制

没有HTTP发送、严格ACK JSON解码、KeyStore配对/轮换、Room发送适配或WorkManager。401/403/409等只是纯决策返回，不是已验证服务响应。Received只代表预先验证的接收ACK，不表示支付。API29、真实App/真机、真实网关/网络故障均未运行。#17/#20/#21/#25保持开放，#8/#10准入风险未解除。
