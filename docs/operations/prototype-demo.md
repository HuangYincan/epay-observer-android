# 模拟器合成通知原型：构建、演示与清理

2026-10-16；#29–32/#36。仅本机隔离测试，没有真实微信/支付宝、真机、生产资金或APK发布。完整证据见[报告](../testing/M3-prototype-report.md)。下列脚本在观察器仓库根目录执行，需要已有Docker、Python3、openssl、Git、SDK/JBR和已启动的API37 AVD，不安装宿主工具。

## 固定版本和私有产物

Android功能提交`8ff7f40`、联调脚本`79f6cf4`/`812517f`，本批最终PR包含后续管理脚本和文档；网关`9a96f72`（含SPEC-025/026）；New API基线`633b4c5b190dc1af99a2814391354d86948e7943`（v1.0.0-rc.44），本地补丁提交`42c3424d3ce3b82d71668dba2b84f58315d7d078`。最终PR/CI见报告。

本机交付目录`build/prototype-delivery/`（Git忽略），含APK、Linux arm64网关/New API二进制、New API补丁及AGPL许可证/NOTICE、SHA256清单和脱敏证据。二进制不跨CPU架构通用，可按下文重建。New API补丁SHA256为`9eed8453a796e4f2233b22bdd8f6ec9827eb5f2b32a41ace679254a5a94a65ff`；不向上游推送，也不纳入本仓库MIT授权。

实际保留实验目录`/tmp/epay-observer-demo-final`，任务前缀`epay-observer-demo`。原失败及初轮成功数据库在`/tmp/epay-observer-recharge-lab`对应已停止容器内保留。不要把原目录重新初始化。重放演示使用新目录、新前缀、新数据；永久金额claim意味着同receiver不能重复使用10/20/40元创建新单。

## 构建服务

新建隔离checkout，网关检出上述功能提交或本批已合并版本；New API固定基线并`git submodule update --init --recursive`。把私有交付的补丁应用到New API checkout：`git am /absolute/path/to/0001-epay-amount-validation.patch`。不要将AGPL代码复制到MIT网关。

```sh
observer_gateway=/tmp/epay-observer-go-pairing
observer_newapi=/tmp/epay-observer-new-api
observer_go_image=golang@sha256:37cfcca63222ec499bec5e8ad7aad52dbf7529db36d5812b8eae2f0621aa92d6
observer_bun_image=oven/bun:1.4.0@sha256:5ff609364c049b54eb0ff560ec96319729a972078ef2c755d758f0c6ef89c2d6
docker run --rm --mount type=bind,src="$observer_gateway",dst=/workspace -w /workspace \
  "$observer_go_image" go build -tags observersynthetic -o epay-observer-synthetic ./cmd/server
docker run --rm --mount type=bind,src="$observer_newapi",dst=/build -w /build/web \
  "$observer_bun_image" sh -c 'bun install --frozen-lockfile && DISABLE_ESLINT_PLUGIN=true VITE_REACT_APP_VERSION=$(cat ../VERSION) bun run build'
docker run --rm --mount type=bind,src="$observer_newapi",dst=/workspace -w /workspace \
  "$observer_go_image" go build -o new-api-observer .
```

这些命令只解析固定项目依赖并在容器构建。数据库/缓存镜像固定在`start_lab.py`：PostgreSQL16.15、Redis7及摘要；New API金额回归另验证MySQL8.4.11，完整版本见基础报告。Android AGP9.2.1、Gradle9.4.1、JBR25、API37及依赖锁见[开发指南](../development/build.md)。

## 从空数据演示

先用`stop_lab.py`停止占用本任务三个端口的旧lab，保留其数据。不要停止不相关服务。新lab只绑定127.0.0.1:18443/18080/13000；内部数据库、Redis和8444回调代理不对宿主发布端口。

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME=/Users/acccan/Library/Android/sdk
observer_lab=/tmp/epay-observer-demo-new
python3 scripts/prototype/start_lab.py "$observer_lab" \
  --gateway "$observer_gateway" --new-api "$observer_newapi" --prefix epay-observer-demo-new
python3 scripts/prototype/configure_lab.py "$observer_lab"
./gradlew --no-daemon --dependency-verification strict \
  -PobserverFixtureCa="$observer_lab/ca.pem" \
  :fixture:assembleDebug :app:assembleFixtureDebug :app:assembleFixtureDebugAndroidTest
python3 scripts/prototype/issue_grants.py "$observer_lab"
python3 scripts/prototype/recharge_avd.py "$observer_lab" emulator-5554
python3 scripts/prototype/audit_recharge.py "$observer_lab"
python3 scripts/prototype/callback_faults.py "$observer_lab" emulator-5554
python3 scripts/prototype/admin_acceptance.py "$observer_lab" emulator-5554
python3 scripts/prototype/audit_recharge.py "$observer_lab"
```

grant短期有效，必须在APK构建结束后发放；过期则重新执行issue_grants，不能改时间字段。脚本会安装发送器/观察器/runner，并清空专用`dev.epay.observer.fixtureclient.debug`的数据；不操作其他应用。recharge脚本保留两条历史UNBOUND，再实际HTTPS配对；宿主通过普通New API `/api/user/pay`及网关`/submit.php`创建10元双渠道单。Android仅接收“订单已准备”同步标记，不接收订单号/金额选单提示；独立发送App发出固定形态通知。后续20/40元也是预定义静态通知场景。

预期首次额度100,000,000→110,000,000，两条消费/收入/成功回调/充值。故障脚本新增双渠道20元，最终130,000,000、四条成功充值；20个并发真实TLS重复回调不再增额。管理测试新增40元待支付单；暂停中新通知ACK但needs_review，恢复不升级，额度仍130,000,000。`audit-result.json`逐笔核对event→decision→consumption→order→ledger→New API topup及费用、provider、原Amount额度，不以页面跳转代替账务。

## 查看和暂停

```sh
python3 scripts/prototype/admin_trace.py "$observer_lab" lab-receiver-wx
python3 scripts/prototype/admin_trace.py "$observer_lab" lab-receiver-ali
python3 scripts/prototype/admin_trace.py "$observer_lab" lab-receiver-wx --pause --reason incident
python3 scripts/prototype/admin_trace.py "$observer_lab" lab-receiver-wx --resume --reason review_completed
```

专用TLS管理员认证，输出无设备标识、密钥或原通知。候选订单号/状态与已消费订单字段分开，候选不表示支付。操作审计持久保存；暂停保留接收和ACK，历史待核对不因恢复重新入账，已提交商户回调继续投递。凭据过期需通过普通管理员登录重新取得会话；不得把JWT贴入Issue。

## 结束、恢复与清理

先保存`artifacts/`及允许公开的`*-result.json`。私有目录还有可用凭据、grant、KEK和CA私钥，绝不能整体上传；原始日志也需脱敏。当前交付的显式CA APK只信任其指定lab的localhost CA；新lab必须重建APK。

```sh
./gradlew --no-daemon --dependency-verification strict \
  :app:assembleFixtureDebug :app:assembleFixtureDebugAndroidTest :app:assembleProductionRelease
python3 scripts/verify_apk.py app/build/outputs/apk/fixture/debug/app-fixture-debug.apk --variant fixtureDebug
python3 scripts/verify_apk.py app/build/outputs/apk/production/release/app-production-release-unsigned.apk --variant productionRelease
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 install -r app/build/outputs/apk/fixture/debug/app-fixture-debug.apk
python3 scripts/prototype/stop_lab.py "$observer_lab"
# 仅当不再需要该lab数据库时，显式删除该任务容器/匿名卷/网络：
python3 scripts/prototype/stop_lab.py "$observer_lab" --remove
```

stop保留数据库，remove删除此lab数据但保留私有目录；两者核对容器/网络所有权标签，支持部分启动失败和重复清理。adb reverse由设备脚本finally清理。再次启动已停止lab需要恢复容器及runtime内callback-proxy进程，最简单的完整复现是保留旧实验并按新目录流程重建，不能清历史金额claim后冒充恢复。资金异常按[故障手册](runbook.md)处理，清库仅用于明确放弃的隔离实验。
