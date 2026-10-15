# #9 / #5 首批工程基线验证

日期：2026-10-15。范围：SPEC-007、ADR-0004、SPEC-005；FR-07 的基础工程部分。用户已明确批准本批。实现分支 feat/9-android-foundation 基于 M0 的 4e8f2b0，未合并 main；epay-go 仍为 4d855060，未改源码。

这是 **模拟器基础应用** 验证，还不是“模拟器通知夹具”或真实微信／支付宝验证。应用目前只显示真实的未启用／未绑定／暂无事件状态，没有监听 service、配对、解析、队列、上传或充值能力。

## 结果

| 验收 | 已执行检查 | 结果 |
| --- | --- | --- |
| BUILD-01 本机构建 | Gradle 9.4.1 wrapper、AGP9.2.1、JBR25、SDK37、Build Tools36；严格依赖校验及 offline 重建 | Passed；fixtureDebug、productionRelease unsigned、AndroidTest APK 生成 |
| BUILD-01 GitHub CI | 托管 Linux runner，同版本工具和严格制品校验 | Pending，运行后补齐链接和结果 |
| BUILD-02 JVM | Kotlin/JCA 重算 3 组 SPEC-002 golden；12 个格式/篡改对比、重试 body 不变 | 3 个 JUnit 测试，0 failure/error |
| BUILD-03 产物隔离 | 最终 APK manifest、资源压缩后的网络/备份 XML、包名/组件/标记检查 | 两个 APK Passed；fixtureRelease 任务不存在 |
| AVD-01 环境 | 复用已在线的 API37 Google Play arm64 AVD | Passed；未重新安装 SDK 或 wipe AVD |
| AVD-02 状态页 | 测试包启动，环境/监听/绑定/事件状态断言 | Passed；截图已目视核对，无遮挡或截断 |
| AVD-03 网络 | 默认系统 TLS 访问 example.com，禁全局/localhost/IPv4及IPv6环回/宿主映射的明文策略 | Passed；没有测试 CA 或 trust-all |
| AVD-04 重置 | 只 pm clear 本项目 fixture client，再启动 | Passed；COLD，状态重新显示未配置 |

仪器测试合计 3/3 Passed。lint 0 error、3 个版本更新提示 warning（两项 AndroidGradlePluginVersion、一项 NewerVersionAvailable），本批保持审查过的固定版本，未压制 lint。productionDebug 可构建源码配置已由单测编译，未宣称单独安装验收；正式 Release 未签名、未安装或发布。

## 环境和复现

- 本机 Studio JBR 25.0.3 build 25.0.3+-15898627-b508.16；JVM target17。
- AVD Medium_Phone_API_37.0（配置路径 Medium_Phone.avd），Android17/API37.0、Google Play arm64-v8a 镜像 revision6；Emulator37.2.12、Platform Tools37.0.1，2GiB RAM/1CPU。
- 无需读取真实 App、账号、通知、设备标识或生产服务；HTTPS probe 只访问公开静态站点。

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew --no-daemon --offline --dependency-verification strict \
  :app:assembleFixtureDebug :app:assembleProductionRelease \
  :app:testProductionDebugUnitTest :app:assembleFixtureDebugAndroidTest \
  :app:lintProductionRelease
python3 scripts/verify_apk.py app/build/outputs/apk/production/release/app-production-release-unsigned.apk --variant productionRelease
python3 scripts/verify_apk.py app/build/outputs/apk/fixture/debug/app-fixture-debug.apk --variant fixtureDebug
python3 scripts/smoke_avd.py emulator-5554 --report /tmp/observer-foundation-instrumentation.txt
sh scripts/reset_fixture_client.sh emulator-5554
```

首次解析依赖需要网络，offline 命令适用于缓存已准备的重跑。本机代理仅通过调用时 JVM 参数传入，未进入仓库/应用。工具链 zip 和 wrapper JAR 均与官方 SHA-256 比较；Linux AAPT2 额外从 Google Maven 获取并核对发布 SHA-1、记录 SHA-256，为 CI 严格校验准备。

## 故障与修正记录

1. 初次 Gradle 未继承 shell 代理，依赖解析没有推进；停止本任务 daemon 后以本机 JVM proxy 参数重试，未改变仓库网络地址或 SDK。
2. 首次 AVD 网络检查失败：全局明文为 false，但 localhost 为 true。核对 Android 官方文档后，确认 API37 隐式 localhost 配置；增加显式禁止规则和 8 个主机反例，再次执行 3 项仪器测试全部通过。
3. 新域规则最初被 lint 指出缺少 includeSubdomains；补齐明确值后 lint 0 error。没有添加忽略证书或明文例外让测试通过。

## 证据与限制

- [APK 大小与 SHA-256](evidence/foundation/artifacts.json)：本机最后验证的文件；APK 均被 gitignore 排除，不上传到公开仓库。Gradle debug key 仅由本机工具使用，未提交或生成生产签名。
- [仪器测试原始结果](evidence/foundation/instrumentation.txt)、[结构化摘要](evidence/foundation/summary.json)。
- [测试状态页截图](evidence/foundation/fixture-client.png)，只含本项目基础界面，已检查不含账户、通知或密钥。
- JVM HTML/XML 与 lint 报告保留在 app/build/reports、app/build/test-results；CI 只存报告，不发布 APK。

本机产物在实现提交前生成，提交的源文件与本次验证一致；CI 会按提交独立构建。这里的“可复现”指固定构建输入与可重跑验收，未宣称跨 OS/debug key 的 APK 逐字节相同。

未运行：最低 API29、通知夹具 EMU-01..14、真实微信／支付宝、真机、数据库、支付联调、真实付款、完整 AVD 销毁/重建。#8/#10 的业务准入问题未因工程完成而解除。下一批候选为 #11/#12，当前未实施。
