# ADR-0004 Android 技术栈与可复现基线

状态：Accepted for #9/#5 foundation，2026-10-15；用户已批准首批工程。Kotlin/Views、编译 SDK 和工具链已用于本地成功构建；2026-10-16 的后续开发已接入 Room；WorkManager 留到上传调度任务。证据见工程基线报告。

| 项目 | 候选固定值 | 理由／验证边界 |
| --- | --- | --- |
| 语言 | Kotlin，AGP 内置 KGP 2.2.10 | 原生通知／Keystore／调度，无跨平台桥接；不额外应用旧 Kotlin Android plugin |
| UI | Android Views + XML，最小 Activity | 当前只有权限、绑定、队列、诊断；比 Compose 少一组编译器/BOM 组合 |
| 持久化 | Room + SQLite，显式迁移 | 事件、身份映射、发送 body 同一事务；Room 2.8.5 + KSP 2.3.6 在 #16 接入，显式事务、schema 导出，无破坏性迁移 |
| 后台 | WorkManager；连接态可触发短任务 | 重启恢复、持久调度；优于无限线程/WakeLock；不承诺秒级 SLA 或补回漏投通知 |
| 网络／签名 | OkHttp、JCA HmacSHA256、Android Keystore | #9 仅以测试代码验证 JCA golden/默认 HTTPS；OkHttp 在 #21 接入时锁定，不提前实现上传 |
| minSdk | 29（Android 10） | 可直接检查 opPkg／UID，减少旧平台分支；不表示 API 29 已实测；低 RAM Q 监听限制仍适用 |
| compileSdk / targetSdk | 37 / 37（API 37.0） | 使用现有 SDK；不以降低 target 绕开后台规则 |
| AGP / Gradle | 9.2.1 / 9.4.1 | AGP 9.2 官方支持最高 API 37.0、最低 Gradle 9.4.1；采用维护补丁版 |
| JDK runtime | Studio JBR 25.0.3，build `25.0.3+-15898627-b508.16` | 本地 java -version 已执行；Gradle 支持 Java 25 从 9.1.0 起；AGP 最低 JDK 17 |
| JVM 字节码目标 | 17 | 与运行 Gradle 的 JBR 25 分开，不下载另一个 JDK |
| SDK Build Tools | 36.0.0 | 已安装，AGP 官方默认；API 37 不必然要求 Build Tools 37 |
| Studio | `AI-262.9437.185.2621.16467767` | 本机 product-info；IDE 不是构建依赖锁 |

Gradle `9.4.1-bin.zip` 官方 SHA-256：`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`。#9 已按此校验值解析项目工具链，生成官方 wrapper，并核对 wrapper JAR SHA-256 `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`。AGP 9.2.1 POM runtime KGP 为 **2.2.10**，运行时 stdlib 同版本；release/test 已实际构建，锁文件及严格制品校验已加入。未全局安装工具或重装 SDK。

## 已有环境

AVD 名称 `Medium_Phone_API_37.0`，其 `.ini` 实际指向 `Medium_Phone.avd`。配置为 arm64-v8a、Google Play、2 GiB RAM、1 CPU；镜像 `system-images;android-37.0;google_apis_playstore;arm64-v8a` revision 6，SDK platform revision 2，Emulator 37.2.12。M0 仅核对元数据；获准的 #5 已复用在线 AVD 完成基础应用测试，没有重建或清空该 AVD。

Studio/SDK/JBR 已存在，不能继续写“尚未安装”。Docker Engine 28.0.4、Compose 2.34.0-desktop.1 为用户提供的已验证基线，本轮不部署、未重验 socket。扩展候选为 API 29 最低版本、35/36 与真机 OEM；未下载、未测试，不列为已支持。

## 主来源（2026-10-15 核对）

- [AGP 9.2 兼容表](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [AGP 9.2.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.2.1/gradle-9.2.1.pom)
- [Gradle Java 兼容表](https://docs.gradle.org/current/userguide/compatibility.html)
- [Gradle 固定校验值](https://services.gradle.org/distributions/gradle-9.4.1-bin.zip.sha256)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)、[Keystore](https://developer.android.com/privacy-and-security/keystore)

本仓库负责 APK、fixture、协议；epay-go 负责认证、匹配、事务和管理。用户于 2026-10-16 授权按依赖持续开发与已有 AVD 验证；额外全局工具安装、服务部署及真实支付仍不在范围内。New API 金额核对差异须解决后验收 M3。

## 持久化依赖验证（2026-10-16）

Room 2.8.5 / KSP 2.3.6 已与内置 KGP 2.2.10、AGP 9.2.1、JBR25 完成本地构建与 API37 SQLite 测试。固定可用组合，不追逐预览版。Gradle module 中 runtime 的文件别名是 room-runtime.aar，与带版本发布 URL 不同；发布 SHA-256 与缓存／verification metadata 一致。直接 runtime/compiler/KSP 的核对见 identity/dependency-checks.json；传递依赖全部锁定并严格校验，未宣称每个传递制品都有独立签名核验。

主来源：AndroidX Room release notes；Google Maven androidx/room 2.8.5 POM/module/checksum；Maven Central com/google/devtools/ksp 2.3.6 POM/module/checksum。无第三方旧项目源码复用。Room 的 MultiInstanceInvalidationService 随库清单存在，exported=false，APK 检查明确核验，不扩大外部调用面。
