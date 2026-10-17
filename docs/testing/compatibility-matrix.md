# 兼容性矩阵

API37模拟器合成通知完整充值及用户重跑已通过，见[M3报告](M3-prototype-report.md)。2026-10-16真实App已完成安装与首次启动；登录结果按渠道单独记录，支付宝已有用户反馈的正向回调，完整通知兼容性未验收，实体设备Not run。人工界面验收按[清单](manual-acceptance.md)逐项记录。

| 层次 | 来源 | Android / 镜像 | 设备 / ABI | 支付 App 版本 | 结果 | 样本与报告 |
| --- | --- | --- | --- | --- | --- | --- |
| 模拟器基础应用（非通知夹具） | fixture client 状态页 | API 37.0 Google Play rev 6 | Medium_Phone / arm64-v8a | 不适用 | Passed | [#9/#5 报告](M1-foundation-report.md) |
| 模拟器夹具 | 微信形态 | API 37.0 Google Play rev 6 | Medium_Phone / arm64-v8a | 不适用 | Passed | [M3闭环与故障报告](M3-prototype-report.md) |
| 模拟器夹具 | 支付宝形态 | API 37.0 Google Play rev 6 | Medium_Phone / arm64-v8a | 不适用 | Passed | [M3闭环与故障报告](M3-prototype-report.md) |
| 模拟器真实 App | 微信 | API37 / Android17 | Medium_Phone / arm64-v8a | 已安装8.0.79 (3200) | Not run | [安装及首次登录入口Passed，登录/通知未测](../research/M4-real-app-d0.md) |
| 模拟器真实 App | 支付宝 | API37 / Android17 | Medium_Phone / arm64-v8a | 已安装12.12.30.8000 (212310) | Passed（单次回调，用户反馈） | [登录/普通回调用户确认](alipay-delivery-probe.md)；[自动生命周期结果与限制](alipay-probe-automation.md)，完整兼容性未验收 |
| 实体真机 | 微信 | 待后续设备 | TBD | TBD | Not run | TBD |
| 实体真机 | 支付宝 | 待后续设备 | TBD | TBD | Not run | TBD |

结果词汇限定为 Not run、Passed、Failed、Blocked、Unsupported，并写明日期、规则版本、环境及失败原因。正常后台、锁屏、重启、Doze、通知折叠、App 分身分别记录；单次前台成功不等于后台可靠。

真实样本提交公开仓库前需脱敏并经用户允许。若脱敏改变识别语义，用合成复现样本说明差异，不上传原文。不得将 test fixture 的 package／title 样本描述为真实 App 取样。

真机计划、待输入项和停止条件见[real-device-plan](real-device-plan.md)。通知可读取、正确解析与真实订单归属分别判定，不以其中一项替代其余项。

2026-10-17：用户确认支付宝登录通过，微信尚未登录。支付宝系统通知权限及独立本地探针来源/监听/起停已验证，用户后来反馈普通场景和支付宝重开后的熄屏场景计数增加，未独立记录当时keyguard状态。后续AVD预检确认当前lockscreen disabled=true；真正锁屏为Blocked。自动条件窗口没有受控发送端时单列Not exercised，不能把探针生命周期通过当作所有条件下真实通知投递通过。见[支付宝单渠道报告](alipay-delivery-probe.md)。
