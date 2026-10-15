# SPEC-007 首批工程与模拟器基线

状态：Implementation scope approved，2026-10-15；#9/#5。用户批准的是首批工程与现有 AVD 验证，未授权启动后续通知功能或网关部署。

## 构建边界

单个 app 模块，Kotlin + framework Views/XML，minSdk 29、compile/target 37，AGP 9.2.1、Gradle 9.4.1、JDK runtime 25、字节码 17。使用 Gradle wrapper checksum、固定版本目录、依赖锁和制品 SHA-256 校验。Room/WorkManager/OkHttp/KSP 在其实际消费任务添加和锁定，本批不引入未使用依赖。

| 变体 | applicationId | 允许用途 |
| --- | --- | --- |
| productionRelease | dev.epay.observer | 正式源码边界检查；不配置生产签名、不发布 |
| productionDebug | dev.epay.observer.debug | 正式源码调试，不接收夹具 |
| fixtureDebug | dev.epay.observer.fixtureclient.debug | 隔离模拟器开发；不同于未来通知发送 App dev.epay.observer.fixture |
| fixtureRelease | 不生成 | Gradle variant API 在配置期禁用 |

测试构建提示只在 fixture source set 资源中。共享代码没有可通过配置启用的测试信任开关。当前没有任何监听 service、收款解析器、上传入口或夹具模块依赖；后续任务必须维持隔离约束，不能把本批通过视为未来 Release 已自动安全。

所有变体禁明文网络和备份，data extraction rules 排除云备份与设备迁移。只申请 INTERNET；唯一 exported 组件为启动 Activity。不申请通知监听权限、不持有商户凭据或生产签名。

API 37 会在没有 localhost 显式规则时添加允许明文的隐式配置，因此共享网络配置必须显式禁用 localhost／环回例外，并在 AVD 上检查 localhost、IPv4/IPv6 环回和普通主机。依据：[Android 官方 localhost configuration](https://developer.android.com/privacy-and-security/security-config#localhost)。

## 界面

基础状态页显示构建环境、通知监听未启用、设备未绑定、无事件；明确当前尚未采集／上报。状态均为未实施能力的真实说明，不提供假的已连接开关或金额确认按钮。

## 验收

| 编号 | 条件 | 证据 |
| --- | --- | --- |
| BUILD-01 | Wrapper/工具/依赖固定；正式 Release 与 fixture Debug 可构建 | 本地命令、依赖锁、CI 运行 |
| BUILD-02 | JVM 单测重算 SPEC-002 三组黄金向量及篡改负例 | JUnit 报告；不是 M2 上传实现 |
| BUILD-03 | 正式 APK 无 fixture 资源、debuggable 标志、测试入口；manifest 禁备份/明文 | 对最终 APK 的自动检查，fixtureRelease 不存在 |
| AVD-01 | 记录现有 API/ABI/镜像与启动/重置步骤 | 脱敏环境表，重置只清本项目测试应用 |
| AVD-02 | fixture Debug 启动且状态真实 | 仪器测试及仅本项目界面的截图 |
| AVD-03 | 模拟器应用使用默认 TLS 访问公开 HTTPS；明文策略仍拒绝 | 独立仪器测试，不部署测试服务、不改生产网络校验 |
| AVD-04 | 清除本项目测试客户端数据后可重新启动 | reset helper 限定包名；不 wipe 整个已有 AVD |

CI 构建与 JVM 单测在托管 runner，模拟器验证在已有 arm64 AVD。未执行最低 API 29、真实微信/支付宝、通知夹具、支付或真机测试必须保持 Not run。
