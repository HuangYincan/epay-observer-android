# 接入既有 epay-go：先验证连接

适用 SPEC-029 / #69。New API 已接通网关时无需重配；连接验证不创建订单、不测试真实付款，也不登记未经用户指定的收款账户。现有通道保持原样，生产自动匹配关闭。

## 运行条件

先核对网关实际运行版本，而不是把部署目录中的模板当成线上配置。普通 HTTP 网关不暴露观察器接口；需要默认构建的独立 TLS 监听器、固定 origin/environment/source_class=notification、独立持久 KEK 和受限证书文件。默认构建不能启用合成 matcher。

可以只为观察器路径在原前端增加到后端 TLS 的代理，启用服务证书验证，保留原 Host；Android 继续验证公网域名的系统信任链，内部 CA 不进入 APK。其余支付、登录、回调及 New API 配置保持原样。变更前应在无生产数据的隔离环境验证，再备份数据库/原配置并保留原镜像。备份归档可读不等于恢复演练通过。

受控切换只重建网关前后端，不重启数据库、Redis 或共享隧道。失败回退原镜像并保留新增 schema 与期间的真实交易，不能恢复旧数据库覆盖新交易。实际部署 override、镜像 ID、回退命令、KEK 备份和证书续期信息保存在私有运维记录；后续更新也必须沿用该 override。

## 实际 Android 预检

使用现有 AVD；不要启动第二个实例。构建 productionDebug 和对应 androidTest，只安装这两个包；不授予通知访问、不安装夹具或额外 CA。产物检查：

```sh
python3 scripts/verify_apk.py app/build/outputs/apk/production/debug/app-production-debug.apk --variant productionDebug
```

以实际网关 origin 替换示例，在新私有目录运行：

```sh
python3 scripts/check_gateway_connection_avd.py emulator-5554 \
  --origin https://gateway.example \
  --directory /tmp/observer-connection-unique-run
```

通过必须是实际 APK 网络栈得到 HTTP 401、JSON 与 `invalid_grant`。这是无凭据请求的预期拒绝，只证明到达认证入口。公开命令只使用示例域名，实际 origin、日志和部署信息留在私有目录；失败运行保留，不覆盖。

DNS 失败时先区分 Wi-Fi 断开、解析代理故障和应用 TLS 问题。本次曾确认模拟器 DNS 代理不应答、宿主已有 IPv4 DNS 正常；保留同一 userdata 冷启动并显式沿用该 DNS 后恢复。不要通过修改 hosts、伪装 UA 或关闭证书验证把连接失败变成通过。只补跑受影响的连接检查，不重跑已完成的支付/生命周期矩阵。

## 连接通过后

请用户指定当前支付宝是否就是要绑定的稳定收款账户；不读取账号页来猜测身份，也不用设备/通道 ID 代替 receiver。得到指定后才创建配对授权、正常配对并执行 SPEC-020 签名健康检查。

连接、配对、通知回调、正文解析和订单归属是不同验收项。真实正文与归属未准入时，不开启生产自动匹配，也不以连接通过宣称自动收款可用。

2026-10-18：本用户已指定当前支付宝，[实际持久配对与新进程签名健康已通过](../testing/production-pairing-report.md)，无需重复操作。自动工具 `scripts/provision_gateway_avd.py` 仅用于已指定初始支付宝的受控登记；`--allow-persistent-pairing` 必须显式提供。新私有目录保留每阶段证据，不能重复 pair 替换现有授权。单独 `--stages verify` 只接受无token的 `{"scope":...}` 私有描述，不生成新授权。后续凭据到期、撤销或账号改变时按正常管理流程处理，不能重用本次短期授权或把新账号放进旧receiver。

随后获准的[SPEC-031单次私有正文采样](../testing/alipay-private-content-sample.md)已完成一次120秒被动窗口，0样本且权限/清理恢复通过。当前缺受控真实到账样本与归属规则；不重复配对，不把空采样或普通聊天当成收款验证，生产采集/匹配继续关闭。

用户后续自行配置EPay个人收款码通道，填写该插件的回调密钥只满足旧式回调配置，不会将当前设备签名事件自动关联到订单。真实observer自动匹配仍受#8阻塞。新单次扫码采样使用[SPEC-032本地控制页](../testing/alipay-scan-sample.md)，用户自行决定付款；代理不调用旧回调、伪造平台流水或改写订单来制造充值成功。准备阶段不修改现有通道或New API。
