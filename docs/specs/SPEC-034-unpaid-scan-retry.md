# SPEC-034 未付款后的单次扫码采样准备

2026-10-18；#79/#33/#42/#8，FR-01/02/07。用户反馈SPEC-032窗口期间尚未付款，旧窗口0回调不能判断收款通知失败。本批准备一个新的固定slot，实际新增标题/正文采样仅由用户在明确范围的本地页再次同意并点击开始；代理不点击、不付款。

- 固定新marker `used-scan-payment-unpaid-retry-v1` 与consent `scan-payment-unpaid-retry-once-120s-v1`。必须保留并确认 `used`、`used-scan-payment-v1` 已存在；新marker未用、无原文/运行中gate。不得重置旧标记、不接受任意session ID、不自动连续重试。仅明确androidTest选择，外部UI/Intent不能选择或启动。
- 沿用SPEC-031/032固定来源、字段/容量、elapsedRealtime120秒、私有脱敏删除、一次启动/保守宿主预算和失败恢复。等待/刷新/prepareOnlyRetry均不读取正文、不消费新标记。新增本地CLI模式只准备等待页，原CLI及旧slot仍拒绝已消费标记。
- 页面明确“上一轮结束、用户反馈尚未付款；这是新增一次120秒支付宝标题/正文采样，私有脱敏后删除”；按钮为“同意并开始这次额外采样”。用户先扫码并按收银台金额填好，停在最终确认付款前；点开始并等实际RUNNING变绿后才自行决定付款。页面仍明确不会自动为New API充值，用户反馈不是平台证据。
- 新私有目录，不覆盖run1–3；先核对旧控制器FINISHED/cleanup_ok再停止它并释放锁。唯一AVD只做prepareOnlyRetry，不启动第二个模拟器、不清数据、不复查账号或读屏。原四笔/总增额30,000,000只读审计。
- 采样仍未运行时记Ready/Not run；原窗口用户“尚未付款”单独补记，不覆盖其0结果。生产来源/解析/匹配及线上配置不变。

| 编号 | 验收 |
| --- | --- |
| RETRY-01 | 固定scope、前序标记/空原文检查、旧入口不变；受影响sample构建/JVM/lint/runner及最终APK检查 |
| RETRY-02 | 宿主严格路由retry consent与marker；仍需本地控制，等待/刷新不运行；页面明确新增范围和金额准备步骤 |
| RETRY-03 | 唯一AVD只prepareOnlyRetry通过，原标记保留/新标记未消费/无原文/无sample监听权限，原账务审计不变；等待页交回用户 |

实际采样另记，不把准备或合成页面检查当作真实支付兼容性。结束/失败不再自动安排下一slot。

准备结果见[未付款后的扫码采样报告](../testing/alipay-unpaid-scan-retry.md)。
