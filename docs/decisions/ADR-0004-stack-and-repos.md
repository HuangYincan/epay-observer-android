# ADR-0004 技术栈与仓库分工

状态：Proposed。用户未指定开发语言或最低 Android 版本。

建议使用原生 Android 项目，优先评估 Kotlin、NotificationListenerService、持久数据库和平台支持的后台调度。UI 可用 Jetpack Compose 或较少依赖的方案，待选型任务比较后固定；不在规划阶段提前下载工具链或创建工程。

minSdk、targetSdk、JDK、AGP、Gradle、SDK 镜像和依赖版本在实施前核对官方兼容表并锁定。先以支持宿主架构的 AVD 为开发基线；候选 API 35／36 不是已测试承诺。

本仓库负责 APK、夹具和协议契约。epay-go 负责后端与管理页，New API 原则上不改源码。跨仓库任务需要对应 Issue／PR、协议版本、网关提交和测试实例版本，不能只写“已接入”。

构建、CI、签名与发布分阶段实现。公开仓库的产品许可证待审计选择，不复制旧项目代码直到许可证决定。
