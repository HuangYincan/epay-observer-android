# 构建与模拟器验证（#9 / #5 / #11 / #12 / #13 / #14）

当前已有SPEC-007–026独立夹具、采集/队列、实际配对/上报及合成充值闭环，见[M3报告](../testing/M3-prototype-report.md)。下列早期阶段的测试计数保留为历史记录；当前主套件43+8，JVM32/147。正式／测试应用 ID 是构建隔离，不是服务端身份认证。

## 本机工具

使用已有 Studio JBR 与 Android SDK；不全局安装 Gradle。wrapper 首次解析固定 Gradle 9.4.1（官方 SHA-256 已写入 properties），随后使用本机缓存。

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew --no-daemon --dependency-verification strict \
  :fixture:assembleDebug :fixture:lintDebug \
  :app:assembleFixtureDebug :app:assembleProductionRelease \
  :app:testProductionDebugUnitTest :app:testFixtureDebugUnitTest :app:assembleFixtureDebugAndroidTest \
  :app:lintProductionRelease
```

也可在被忽略的 local.properties 中配置 sdk.dir；不提交绝对路径、代理地址或账号。宿主若需网络代理，Gradle JVM 不会自动使用 shell 的 HTTPS_PROXY；用本地 Gradle user properties 或调用时的 `-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>` 等 JVM 参数，不写入仓库、不改变 APK TLS。

app/gradle.lockfile 和 fixture/gradle.lockfile 锁定已解析的运行／测试／工具配置，settings-gradle.lockfile 记录 settings 解析状态，gradle/verification-metadata.xml 校验依赖制品（含 macOS 与 Linux AAPT2），libs.versions.toml 固定直接依赖。依赖更新须评审版本、许可证和新校验值；日常构建不运行 --write-locks 或 --write-verification-metadata。

## 构建产物与验证

- 独立发送器：`fixture/build/outputs/apk/debug/fixture-debug.apk`，包名 `dev.epay.observer.fixture`；只有 debug，没有 release，不含网络权限。
- 测试客户端：`app/build/outputs/apk/fixture/debug/app-fixture-debug.apk`，包名 `dev.epay.observer.fixtureclient.debug`。
- 正式边界产物：`app/build/outputs/apk/production/release/app-production-release-unsigned.apk`，包名 `dev.epay.observer`，不配置生产签名，不用于发布。
- 正式源码调试变体 productionDebug 可供以后调试；fixtureRelease 在配置期禁用。
- JVM 测试验证协议 golden、观察身份与容量／来源边界，不提供生产签名；实际上传已按SPEC-017/018实现。AndroidX Test、JUnit、JSON 仅在测试配置中。

```sh
python3 scripts/verify_apk.py app/build/outputs/apk/production/release/app-production-release-unsigned.apk --variant productionRelease
python3 scripts/verify_apk.py app/build/outputs/apk/fixture/debug/app-fixture-debug.apk --variant fixtureDebug
./gradlew --no-daemon :app:tasks --all
```

APK 检查按 source.properties 选择 Command-line Tools23.0（不能假定 latest 目录就是该版本），解析最终 manifest 与经资源压缩后的网络／备份 XML，核对包名、SDK、组件、无 debug/TLS 绕过及无 fixture 标记。解码 XML 存入 app/build/reports/apk-inspection。任务表应有 assembleProductionRelease、assembleFixtureDebug，没有 assembleFixtureRelease。当前检查包括监听服务的系统绑定权限及生产查询／夹具排除；未来新增组件必须同步预期和证据。

## 现有模拟器

基线：Medium_Phone_API_37.0，实际目录 Medium_Phone.avd；API 37.0、Google Play arm64-v8a rev 6、配置文件2GiB RAM/1CPU，Emulator37.2.12。2026-10-16的1CPU实例出现系统ANR，后以运行参数 -cores 4 -memory 4096 通过21+2测试；config.ini前后不变。当前4CPU/4GiB结果与原配置稳定性分开记录，见M2-signing-core-report。本批旧图形进程再次过载，使用同AVD -gpu swiftshader -no-window 冷启动后通过28+8，见M2-pairing-report。只针对明确指定的 emulator-NNNN 操作，不选择实体设备。

```sh
"$ANDROID_HOME/platform-tools/adb" devices -l
# 仅当现有 AVD 尚未启动时执行；不要重复启动同一个 AVD。
"$ANDROID_HOME/emulator/emulator" -avd Medium_Phone_API_37.0 -no-snapshot-load -no-snapshot-save -cores 4 -memory 4096
# 按 adb 输出选择 emulator-NNNN；此处以本轮目标为例。
python3 scripts/smoke_avd.py emulator-5554 --report /tmp/observer-foundation-instrumentation.txt
sh scripts/reset_fixture_client.sh emulator-5554
```

smoke helper 安装独立夹具、测试客户端及 runner；只给夹具授予通知发送权限，测试期间授权／撤销本客户端监听。检查合成场景、来源过滤、重连恢复、权限 UI、备份／明文策略和 `https://example.com/` 默认 TLS 连通。即使 adb 命令退出码为零，也必须检查 JUnit 成功结果。HTTPS probe 不是 epay-go/New API 联调，不运行服务器或导入测试 CA。

若系统进程接入／窗口同步超时，先保留失败证据并检查系统状态；不能靠调大超时或删数据把失败当通过。本轮使用同一 AVD 冷启动（不加载／保存快照，不 wipe）恢复后通过；见采集报告。smoke helper 将仪器输出实时写入报告，240 秒超时也保留已输出部分。

reset helper 只清除 `dev.epay.observer.fixtureclient.debug` 的数据，再启动它。严禁拿 `-wipe-data` 或删除 AVD 代替日常重置，也不清理微信/支付宝/其他应用。冷启动可用 -no-snapshot-load；完整 AVD 重建应另用新名称并单独授权，不能破坏已有实例。

AVD 的 10.0.2.2 是宿主访问路由，不会被本工程当作生产回调地址。API 37 的 localhost 明文默认例外已通过显式域规则禁用；所有测试沿用系统证书校验。受限本地回调按SPEC-024实现，默认safehttp不变。

## CI

Android verification workflow 使用 ubuntu-24.04、Temurin 25.0.3+9（setup-java 使用发行 API 的精确标识 25.0.3+9.0.LTS）、固定 commit 的 GitHub Actions，在临时 runner 上用 Command-line Tools 23.0（下载 build 16111833）安装明确 SDK package，再严格校验构建／JUnit／lint／APK。不能假定托管 runner 已有 sdkmanager；这不涉及重新安装用户本机 SDK。CI 只上传报告，不上传 APK 或签名材料。macOS/arm64 AVD 验证另记；CI 构建不证明模拟器或真实支付 App 兼容。

## 合成通知手工操作

安装上述两个 debug APK，打开夹具的“允许发送通知”，再在观察器中打开系统通知读取授权设置。权限和服务连接分开显示；正式构建来源注册表为空。测试脚本结束时撤下夹具通知并撤销测试客户端监听，不更改其他监听器。

夹具场景见 SPEC-008；命令重复投递必须使用 singleTop：

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell am start -W -f 0x20000000 \
  -n dev.epay.observer.fixture/.FixtureActivity --es scenario wxpay
```

可选场景：wxpay、alipay、identical-pair、update、repost、summary、child、refund、expense、chat、malformed、oversized、clear。最后一个撤下夹具通知。命令没有订单号／匹配提示；真实来源不支持靠配置开启。诊断预览最多100个key，仅保留在进程内；SPEC-010/011另外以Room保存身份、候选和队列，身份容量10,000，payload预算32MiB，受阻会显示告警。清数据不能充当支付去重策略。

## 合成解析验证

SPEC-009定义两组独立规则，116条语料在docs/testing/parsing-v1。当前production单测22项、fixture136项（公共测试重复执行）；AVD helper要求25项主套件及2个独立跨进程阶段，涵盖采集、解析、Room持久身份/队列和实际Android JCA签名、KeyStore导入/幂等/删除与跨进程密钥恢复。详细证据见M1-parsing-report、M2-outbox-report与M2-signing-core-report。

追加夹具命令：parse-wx、parse-ali、parse-wx-update，以及 wx-/ali- 前缀的 conflict、currency、refund、chat、promotion、inbox、precision、subtext。两个独立CheckBox控制预览和后续新观察的解析，偏好保存在本应用私有空间。持久层保存采集时开关；旧身份不会在重新启用时追溯制造事件。开关控制预览及新观察的解析；配对后可按普通队列上传，但开关不能使正式构建信任夹具或重解释旧证据。

实际配对HTTPS及临时CA/默认构建恢复：[配对复现](pairing-local.md)。当前smoke要求43项主套件和8个真实进程阶段；网络配对单独执行，不能把离线恢复套件当作网络通过。

人工操作请使用带窗口AVD，保留userdata，避免重复启动同一实例。自动化脚本结束会清理自身adb reverse；本地HTTPS手工验收需按[清单](../testing/manual-acceptance.md)恢复该任务路由。不要把已有配对状态的客户端直接用于要求未绑定的基础测试，先归档并明确使用独立测试数据。
