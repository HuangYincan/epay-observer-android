# 未付款后新增单次扫码采样：准备结果

2026-10-18；#79/#33/#42/#8；SPEC-034 RETRY-01..03，FR-01/02/07。

用户明确反馈上一轮尚未付款。SPEC-032原窗口EXPIRED/120423ms、0回调/0样本保留；这不能作为付款通知失败证据。该反馈只作为用户陈述，不是平台账单核验。

## 准备检查时状态

**Ready；新窗口Not run。** 旧`used`与`used-scan-payment-v1`存在且保留，固定新`used-scan-payment-unpaid-retry-v1`未消费，无原文、sample未获通知访问权限。原run3控制器FINISHED/completed/cleanup_ok均为true后正常停止，才释放互斥锁；没有停止活动采样或启动第二个模拟器。

本地页新增清楚的范围提示：这是额外一次120秒固定支付宝标题/正文采样，仅本机私有脱敏后删除。按钮“同意并开始这次额外采样”由用户自行点击，等待/刷新/prepareOnlyRetry不采样。用户先扫码，按epay-go收银台应付金额填好，停在确认付款前；点开始等实际RUNNING变绿后才自行决定付款。观察器仍未接通真实订单确认，不承诺New API自动充值。

| 验收 | 准备证据 |
| --- | --- |
| RETRY-01 | strict/offline sample APK/runner构建、既有10项gate/source JVM及lint通过；最终APK检查通过，无网络/正文日志/外部存储/删除marker路径，主UI/Intent不能选slot或启动gate；原来源/上限/清理不变 |
| RETRY-02 | 受影响10项控制契约与9项私有处理契约通过，均合成输入：固定retry consent、缺前序marker不授予权限/不启动、旧非本地入口拒绝retry、等待/刷新不启动、一次开始/取消/恢复规则保留；新页面明确新增范围与手填金额步骤 |
| RETRY-03 | 唯一AVD安装后仅prepareOnlyRetry一项通过：固定来源匹配、旧标记保留、新标记未用/无原文/无通知访问；原四笔orders/consumptions/income/topups及总增额30,000,000只读审计不变 |

本批APK SHA-256：`05a18919105cf8ef3c81fb37a718dc3b9eca0d2ec2b6fe95f247d8d81ccdbf1f`。本地构建/检查/准备日志及私有prepared.json位于`/private/tmp/observer-scan-unpaid-retry-20261018-preparation/`；新的控制目录另建，不覆盖旧run1–3。实际窗口及清理结果后续单独记录，失败/超时不自动再开slot。

运行入口在SPEC-032原命令上增加`--unpaid-retry`；只能准备等待页，仍需用户在本地新增范围提示下明确同意并点击。未经该点击不得启动sampleOnce。未修改生产客户端、通道、订单状态或线上网关，也未重跑生命周期/充值验收；真实投递/解析/订单归属/New API充值均未验收。
