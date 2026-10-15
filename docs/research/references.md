# 参考资料与研究边界

日期：2026-10-15。下面是实施阶段需要核对的主来源，不是对可用性的背书。

| 来源 | 用途 | 当前结论 |
| --- | --- | --- |
| [szvone/vmqApk](https://github.com/szvone/vmqApk) | 理解旧通知监听思路与历史格式 | 不直接使用旧 APK；通知格式与兼容性未由本项目实测 |
| [vmqApk LICENSE](https://github.com/szvone/vmqApk/blob/master/LICENSE) | 决定能否及如何复用代码 | 需核对许可证全文、依赖与具体来源，尚未完成审计 |
| [Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) | 监听权限、连接与平台 API | 实施时按选定版本核对 |
| [Android 通知](https://developer.android.com/develop/ui/views/notifications) | extras、分组及系统行为 | 不据此推断支付 App 实际通知格式 |
| [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) | 后台可靠工作候选方案 | 尚未选型 |
| [Android Keystore](https://developer.android.com/privacy-and-security/keystore) | 设备密钥存储候选方案 | 实施时设计与验证 |
| [Android Emulator](https://developer.android.com/studio/run/emulator) | AVD 能力与限制 | 没有已创建或执行过的本项目 AVD |
| [epay-go](https://github.com/HuangYincan/epay-go) | 现有订单／回调／记账服务 | 已本地只读检查固定提交，见集成文档 |
| [New API](https://github.com/QuantumNous/new-api) | 易支付充值与幂等验收 | 实施时固定版本并核对源码 |

研究记录应标出：访问日期、源提交、许可证、读到的具体文件、可借鉴思想、不能直接复用的假设、是否含有复制代码。GitHub 最近更新时间、README 宣称“支持最新版”和单个成功反馈都不能代替实测。

本次文档提交不含任何第三方实现。公开真实通知前需先脱敏并取得用户许可；默认合成样本。
