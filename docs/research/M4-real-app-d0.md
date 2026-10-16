# M4 真实 App 来源与环境预检

2026-10-16；#33 / #42。依据 SPEC-001 来源核查、SPEC-008 内容读取前的来源边界、SPEC-027 READY-01 与 ADR-0002。用户要求继续下一阶段；本批实际完成官方安装产物的离线核验和现有模拟器环境盘点。安装、登录与通知投递尚未运行，不将 APK 签名验证等同运行兼容性。

## 已完成的合成验收

用户明确反馈“双渠道轮换通过”：微信、支付宝各经手工 rotate 后复核网关授权。随后重新运行 `scripts/prototype/audit_recharge.py`，实际结果仍是 4 笔订单、4 条独立事件消费、4 条收入、4 笔成功 New API topup，总增额 30,000,000。没有重跑充值、清数据或重写历史身份。此为人工反馈加独立账务审计，不充当真实 App 证据。

## D0 环境

本轮只读盘点已有专用 AVD：Android 17 / API 37，安全补丁 2026-05-05，`sdk_gphone64_arm64`，ABI `arm64-v8a`，`ro.kernel.qemu=1`。分别查询 `pm path com.tencent.mm` 与 `pm path com.eg.android.AlipayGphone`，均未发现已安装包。

工具复用现有 Android Build Tools 36.0.0 的 `aapt2`、`apksigner` 和 Android Studio JBR，没有安装工具或新镜像。产物只保存在本机临时目录，未提交 APK。

## 两渠道官方来源与产物

下载来源是在本轮实际读取的官方页面中发现；这里只固定本次取得的文件，不承诺它是所有地区/分发渠道的最新版本。后续更换文件必须重新核验，不能仅按文件名或动态链接认定版本。

| 字段 | 微信 | 支付宝 |
| --- | --- | --- |
| 官方入口 | https://weixin.qq.com/ | https://d.alipay.com/ |
| 本次下载 URL | https://dldir1v6.qq.com/weixin/android/weixin8079android3200_0x28004f30_arm64_1.apk | https://tfs.alipayobjects.com/L1/71/100/and/alipay_wap_dc_64.apk |
| 包名 | `com.tencent.mm` | `com.eg.android.AlipayGphone` |
| versionName / versionCode | `8.0.79` / `3200` | `12.12.30.8000` / `212310` |
| targetSdk / native ABI | 34 / arm64-v8a | 35 / arm64-v8a |
| 文件字节数 | 285284760 | 186828123 |
| APK SHA-256 | `f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d` | `de62e391023a2f822d077673e7e7701b44abd5294a76c09fd754b1e1f0647c8d` |
| 当前签名证书 SHA-256 | `0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c` | `389b49f7832f53e9017923220aa85e14dfaa4886ecd7428818bf339543cf498a` |
| `apksigner verify` | Passed，退出码0，1个签名者，v2通过 | Passed，退出码0，1个签名者，v2/v3通过 |

微信入口直接包含上述 arm64 APK 链接。支付宝入口引用 `https://gw.alipayobjects.com/render/p/yuyan/180020010000000069/alipay-unified-landing.acd0bd91e.js`，其中 Android 64 位下载配置给出上述 URL。两次 APK 下载均为 HTTPS，最终 URL 与起始 URL 一致。

核验命令（对两个实际文件分别运行）：

```sh
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging /path/to/downloaded.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs /path/to/downloaded.apk
shasum -a 256 /path/to/downloaded.apk
```

证书摘要属于这次官方入口取得的产物，不是此前已独立批准的信任白名单；没有写入 production 来源注册表。APK 验签只确认文件签名有效，不能证明登录、通知可达或通知所称交易真实，也不代替后续签名轮换核验。

## 当前结果与下一项

| 验收项 | 微信 | 支付宝 | 证据/阻塞 |
| --- | --- | --- | --- |
| 官方来源、版本、ABI和签名核验 | Passed | Passed | 上述实际产物与SDK工具结果 |
| 在目标AVD安装和启动 | Not run | Not run | 已准备安装产物；等待本次安装选择 |
| 登录及模拟器限制 | Not run | Not run | 需用户自行登录测试账号，不代操作账号验证或绕过限制 |
| 通知可达性与字段 | Blocked | Blocked | 尚未登录，也未获真实样本采集许可；当前观察器不接受真实来源 |
| 正负例解析及后台稳定性 | Not run | Not run | 依赖获准样本和新的观察实现 |
| 真实订单归属 | Blocked | Blocked | #8仍缺可信交易/订单关联证据 |

现有 fixture `SourcePolicy` 只接收 `dev.epay.observer.fixture` 且验证同构建签名；production `SourcePolicy` 返回空。直接安装支付 App 后使用现有观察器不能得到真实通知采集结论，不得因此误判支付 App 没有投递通知。

后续观察工具方案为 **Proposed，未实现**：另行定义仅本地观察的独立构建/应用，限定已核验包、版本、签名和用户；经用户允许才读取目标通知字段；无网络上报、配对或支付事件生成，避免真实通知进入已有合成账本。实现前细化规格、保留读取内容前的来源检查，新增拒绝和产物隔离测试。当前不放宽现有来源规则，也不通过夹具规则解析真实通知。

本阶段不要求真实付款。若登录/运行被平台限制，记录界面与版本后标 Blocked，不使用 Hook、签名伪装或登录绕过。#33需安装、登录限制及获准通知证据才能完整验收；#42保持开放。
