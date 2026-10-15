# 参考资料与研究边界

日期：2026-10-15。M0 已核对固定源码与官方文档，详细证据见 [源码审计](M0-source-audit.md) 和 ADR-0004/0005，不是实际收款可用性背书。

| 来源 | 用途 | 当前结论 |
| --- | --- | --- |
| [szvone/vmqApk](https://github.com/szvone/vmqApk) | 理解旧通知监听思路与历史格式 | 不直接使用旧 APK；通知格式与兼容性未由本项目实测 |
| [vmqApk LICENSE](https://github.com/szvone/vmqApk/blob/2867df10fedc26f9239054b9e34a83d3b0bf30dc/LICENSE) | 复用边界 | 已核对 MIT 和内嵌 ZXing Apache-2.0；本轮不复制代码，传递依赖未全量审计 |
| [Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) | 监听权限、连接与平台 API | 实施时按选定版本核对 |
| [Android 通知](https://developer.android.com/develop/ui/views/notifications) | extras、分组及系统行为 | 不据此推断支付 App 实际通知格式 |
| [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) | 后台可靠工作候选方案 | M0 选择，依赖版本与构建验证在 #9 |
| [Android Keystore](https://developer.android.com/privacy-and-security/keystore) | 设备密钥存储候选方案 | 实施时设计与验证 |
| [Android Emulator](https://developer.android.com/studio/run/emulator) | AVD 能力与限制 | 已有用户验证启动的 API 37 AVD；本轮产品测试未执行 |
| [epay-go](https://github.com/HuangYincan/epay-go) | 现有订单／回调／记账服务 | 已本地只读检查固定提交，见集成文档 |
| [New API](https://github.com/QuantumNous/new-api/tree/633b4c5b190dc1af99a2814391354d86948e7943) | 易支付充值与幂等验收 | 固定 RC.44，已审源码；业务金额核对缺口须处理 |

研究记录应标出：访问日期、源提交、许可证、读到的具体文件、可借鉴思想、不能直接复用的假设、是否含有复制代码。GitHub 最近更新时间、README 宣称“支持最新版”和单个成功反馈都不能代替实测。

本次文档提交不含任何第三方实现。公开真实通知前需先脱敏并取得用户许可；默认合成样本。
