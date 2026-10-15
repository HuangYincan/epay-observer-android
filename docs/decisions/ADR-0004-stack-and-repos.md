# ADR-0004 Android 技术栈与可复现基线

状态：M0 review candidate；#3，2026-10-15。版本已核对元数据，Android 构建未运行；候选不等于用户已批准开工。

| 项目 | 候选固定值 | 理由／验证边界 |
| --- | --- | --- |
| 语言 | Kotlin，AGP 内置 KGP 2.2.10 | 原生通知／Keystore／调度，无跨平台桥接；不额外应用旧 Kotlin Android plugin |
| UI | Android Views + XML，最小 Activity | 当前只有权限、绑定、队列、诊断；比 Compose 少一组编译器/BOM 组合 |
| 持久化 | Room + SQLite，显式迁移 | 事件、身份映射、发送 body 同一事务；优于 SharedPreferences/内存；KSP 与 Room 制品版本在 #9 锁定并验构建 |
| 后台 | WorkManager；连接态可触发短任务 | 重启恢复、持久调度；优于无限线程/WakeLock；不承诺秒级 SLA 或补回漏投通知 |
| 网络／签名 | OkHttp、JCA HmacSHA256、Android Keystore | 单次序列化、禁重定向、正常 TLS；依赖精确版本、锁文件在 #9 提交 |
| minSdk | 29（Android 10） | 可直接检查 opPkg／UID，减少旧平台分支；不表示 API 29 已实测；低 RAM Q 监听限制仍适用 |
| compileSdk / targetSdk | 37 / 37（API 37.0） | 使用现有 SDK；不以降低 target 绕开后台规则 |
| AGP / Gradle | 9.2.1 / 9.4.1 | AGP 9.2 官方支持最高 API 37.0、最低 Gradle 9.4.1；采用维护补丁版 |
| JDK runtime | Studio JBR 25.0.3，build `25.0.3+-15898627-b508.16` | 本地 java -version 已执行；Gradle 支持 Java 25 从 9.1.0 起；AGP 最低 JDK 17 |
| JVM 字节码目标 | 17 | 与运行 Gradle 的 JBR 25 分开，不下载另一个 JDK |
| SDK Build Tools | 36.0.0 | 已安装，AGP 官方默认；API 37 不必然要求 Build Tools 37 |
| Studio | `AI-262.9437.185.2621.16467767` | 本机 product-info；IDE 不是构建依赖锁 |

Gradle `9.4.1-bin.zip` 官方 SHA-256：`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`。只读取校验文件，没有下载 Gradle 或创建 wrapper。AGP 文档修复列表提及其他 Kotlin 版本，但 9.2.1 POM runtime KGP 实际为 **2.2.10**；以制品元数据为准。#9 必须构建验证 release/test 及依赖组合；失败先修订 ADR，不能换工具后宣称原组合通过。

## 已有环境

AVD 名称 `Medium_Phone_API_37.0`，其 `.ini` 实际指向 `Medium_Phone.avd`。配置为 arm64-v8a、Google Play、2 GiB RAM、1 CPU；镜像 `system-images;android-37.0;google_apis_playstore;arm64-v8a` revision 6，SDK platform revision 2，Emulator 37.2.12。用户已验证 emulator-5554 可启动；本轮只读元数据，未启动／重建 AVD。

Studio/SDK/JBR 已存在，不能继续写“尚未安装”。Docker Engine 28.0.4、Compose 2.34.0-desktop.1 为用户提供的已验证基线，本轮不部署、未重验 socket。扩展候选为 API 29 最低版本、35/36 与真机 OEM；未下载、未测试，不列为已支持。

## 主来源（2026-10-15 核对）

- [AGP 9.2 兼容表](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [AGP 9.2.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.2.1/gradle-9.2.1.pom)
- [Gradle Java 兼容表](https://docs.gradle.org/current/userguide/compatibility.html)
- [Gradle 固定校验值](https://services.gradle.org/distributions/gradle-9.4.1-bin.zip.sha256)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)、[Keystore](https://developer.android.com/privacy-and-security/keystore)

本仓库负责 APK、fixture、协议；epay-go 负责认证、匹配、事务和管理。New API 原则上不改源码，但金额核对差异须解决后验收 M3。功能开发、下载工具、部署及真实支付均需后续授权。
