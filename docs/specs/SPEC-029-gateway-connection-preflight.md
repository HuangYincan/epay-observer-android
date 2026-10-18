# SPEC-029 既有网关 HTTPS 入口预检

2026-10-17；Android #69 / #42，epay-go #5（跨库追踪，协议以 SPEC-016/020 为准）。

## 范围

用户要求接入既有 epay-go；New API 已接通，不重配。先验证实际 Android 系统 TLS 与认证入口，保留已有支付通道，不登记未经用户指定的 receiver，不开启生产匹配。真实正文、支付和归属仍由 #33/#8 单独约束。

新增工具只在 androidTest 中。必须显式启用 `observerConnectionProbe=true`，目标包必须为 `dev.epay.observer.debug`，且该包没有通知访问权限。origin 通过私有运行参数提供；遵循共享 JsonHttpsTransport 的 canonical HTTPS origin 校验，不把真实部署域名写进源码。

只发送一次无 Authorization 的 POST `/api/observer/v1/pairing/inspect`，Content-Type 为 application/json，固定 body 为 `{"schema_version":1}`。复用应用 JsonHttpsTransport：系统 CA、正常 hostname 校验、禁止重定向、默认平台 UA、15秒连接/读取超时、4096字节 JSON 响应上限。不生成假 token，不请求 redeem/activate/events/health，不改凭据/绑定或信任设置。

成功必须同时满足 HTTP 401、JSON、严格协议错误 `invalid_grant`。403、重定向、非 JSON、网络异常和超时均失败；不通过浏览器伪装、放宽 TLS 或发送假支付事件来获得成功。

输出仅含固定结构的状态、JSON/协议错误分类与范围布尔值，不输出 origin、响应正文、异常文本、账号、设备标识、token 或密钥。宿主驱动要求显式 emulator serial、qemu=1、Android user 0、新私有临时证据目录；保留失败日志，不覆盖旧运行。

## 验收

- CONN-01：productionDebug / 对应 androidTest 编译及生产隔离产物检查；Release 规则不放宽。默认 fixture smoke 排除该显式网络测试，原43项不变。CI 仅编译，不请求外网。
- CONN-02：宿主解析拒绝缺失/重复结果、伪成功、测试失败和异常退出。
- CONN-03：指定既有 AVD 经实际公共 HTTPS 到达上述认证入口；结果按实记录。未运行为 Not run，网络失败不得写通过。

该检查不证明配对、设备凭据、真实通知、收款解析、订单归属、到账或自动充值。它不替代 SPEC-020 的配对后签名健康检查，也不重复已完成的生命周期或合成充值验收。
