# 隔离 HTTPS 配对复现

仅覆盖SPEC-016配对：不运行匹配/支付/New API，不宣称完整Goal完成。需要已有Docker、openssl、Android SDK/JBR和已启动的明确AVD；不安装宿主工具。网关checkout必须包含配对PR。以下名字只用于本任务，已有同名资源时先核对，不覆盖其他服务。

## 私有测试目录与数据库

在观察器仓库执行；`observer_gateway`替换为网关隔离checkout绝对路径。目录必须尚不存在。

```sh
observer_gateway=/absolute/path/to/epay-go
observer_lab=/tmp/observer-pairing-repro
python3 scripts/prepare_pairing_tls.py "$observer_lab"
```

把下面脚本的目录与上面保持一致。数据库凭据只写权限0600文件，不复制到终端、Issue或仓库。

```sh
python3 - <<'PY'
from pathlib import Path
import os, secrets
folder = Path('/tmp/observer-pairing-repro')
password = secrets.token_hex(32)
fd = os.open(folder / 'database.env', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as f:
    f.write('POSTGRES_USER=observer_test\nPOSTGRES_DB=observer_test\nPOSTGRES_PASSWORD=' + password + '\n')
    f.write('EPAY_TEST_DATABASE_DSN=host=epay-observer-postgres user=observer_test dbname=observer_test password=' + password + ' sslmode=disable\n')
PY
docker network create epay-observer-prototype
docker run -d --name epay-observer-postgres --network epay-observer-prototype \
  --env-file "$observer_lab/database.env" \
  postgres@sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d
docker exec epay-observer-postgres pg_isready -U observer_test
docker run -d --name epay-observer-go-tools --network epay-observer-prototype \
  --mount type=bind,src="$observer_gateway",dst=/workspace --workdir /workspace \
  golang@sha256:37cfcca63222ec499bec5e8ad7aad52dbf7529db36d5812b8eae2f0621aa92d6 sleep infinity
docker exec epay-observer-go-tools go test -tags observeravd -c \
  -o /workspace/observer-avd.test ./internal/observerstore
```

## 构建与运行

沿用build.md的JAVA_HOME/ANDROID_HOME。先构建，再启动五分钟grant窗口。只有明确传入公有CA证书的fixtureDebug加入localhost信任；私钥不复制到Android。

```sh
./gradlew --no-daemon --dependency-verification strict \
  -PobserverFixtureCa="$observer_lab/ca.pem" \
  :app:assembleFixtureDebug :app:assembleFixtureDebugAndroidTest :app:assembleProductionRelease
```

一个终端启动测试桥（前台等待设备结果，最多15分钟；退出自动清理schema）：

```sh
docker run --rm --name epay-observer-pairing-bridge --network epay-observer-prototype \
  --env-file "$observer_lab/database.env" -e OBSERVER_AVD_DIR=/lab \
  --mount type=bind,src="$observer_lab",dst=/lab \
  --mount type=bind,src="$observer_gateway",dst=/workspace,readonly \
  --workdir /workspace/internal/observerstore -p 127.0.0.1:18443:8443 \
  golang@sha256:37cfcca63222ec499bec5e8ad7aad52dbf7529db36d5812b8eae2f0621aa92d6 \
  /workspace/observer-avd.test -test.run '^TestPairingAVDBridge$' -test.v -test.timeout 16m
```

另一终端检查实际APK，再执行设备测试；CA摘要使用prepare脚本打印的值。

```sh
python3 scripts/verify_apk.py app/build/outputs/apk/fixture/debug/app-fixture-debug.apk \
  --variant fixtureDebug --fixture-ca-sha256 <CA文件SHA256>
python3 scripts/pairing_tls_avd.py emulator-5554 "$observer_lab"
```

预期Android报告：两个provider激活、历史2个UNBOUND、新2个READY、未知CA/重定向/错误origin拒绝，events_uploaded/payment_verified为false。网关报告：2个active，redirect_requests=0，events=0。grant过期时在私有目录创建空`refresh-grants`文件，待其被桥删除且avd-grants.json更新后重试；不能编辑响应时间/放宽校验。

## 恢复默认产物与清理

### SPEC-017实际上传模式

完成上述准备与构建后，测试桥增加 `-e OBSERVER_AVD_MODE=upload`，容器名可改为 `epay-observer-upload-bridge`；设备脚本增加 `--upload`。使用独立私有目录，或先归档上一轮报告并仅移除该目录的 `device-uploaded` 完成标记，防止桥提前退出。桥每轮建立新schema与新短期授权。

上传脚本会显式清空本任务专用 `dev.epay.observer.fixtureclient.debug` 数据，然后安装客户端、独立通知fixture与runner。预期Android报告：历史2个UNBOUND、2个ACKED、3次发送（首次ACK响应体丢失）；网关报告：2个active、2个event、3个request、redirect_requests=0。两端payment_verified仍false；不会运行订单匹配或充值。设备报告完成后桥自动退出并清理schema。通用AVD主套件要求初始未配对；上传测试后再次运行主套件前只重置此专用debug客户端。

### 恢复system-only构建

```sh
./gradlew --no-daemon --dependency-verification strict :app:assembleFixtureDebug :app:assembleProductionRelease
python3 scripts/verify_apk.py app/build/outputs/apk/fixture/debug/app-fixture-debug.apk --variant fixtureDebug
python3 scripts/verify_apk.py app/build/outputs/apk/production/release/app-production-release-unsigned.apk --variant productionRelease
```

默认检查必须拒绝额外CA，证明省略参数不会继承旧生成资源。`pairing_tls_avd.py`自动撤销自己的adb reverse、删除bootstrap；测试用KeyStore aliases/Room文件由instrumentation清理，正常应用数据不清空。若失败，保留报告并核对进程，再处理明确命名资源。任务结束后可停止/删除本任务两个容器和网络；需要继续网关开发时保留它们。私有目录包含短期grant和私钥，绝不上传；只分享脱敏结果JSON及产物SHA。测试桥被强制终止时遗留schema仍仅存在于专用测试数据库，不能去生产库清理。
