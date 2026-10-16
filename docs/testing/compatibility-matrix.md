# 兼容性矩阵

2026-10-16：API37模拟器合成通知完整充值及用户重跑已通过，见[M3报告](M3-prototype-report.md)。真实App和实体设备仍Not run；人工界面验收按[清单](manual-acceptance.md)逐项记录。

| 层次 | 来源 | Android / 镜像 | 设备 / ABI | 支付 App 版本 | 结果 | 样本与报告 |
| --- | --- | --- | --- | --- | --- | --- |
| 模拟器基础应用（非通知夹具） | fixture client 状态页 | API 37.0 Google Play rev 6 | Medium_Phone / arm64-v8a | 不适用 | Passed | [#9/#5 报告](M1-foundation-report.md) |
| 模拟器夹具 | 微信形态 | API 37.0 Google Play rev 6 | Medium_Phone / arm64-v8a | 不适用 | Passed | [M3闭环与故障报告](M3-prototype-report.md) |
| 模拟器夹具 | 支付宝形态 | API 37.0 Google Play rev 6 | Medium_Phone / arm64-v8a | 不适用 | Passed | [M3闭环与故障报告](M3-prototype-report.md) |
| 模拟器真实 App | 微信 | TBD | TBD | TBD | Not run | TBD |
| 模拟器真实 App | 支付宝 | TBD | TBD | TBD | Not run | TBD |
| 实体真机 | 微信 | 待后续设备 | TBD | TBD | Not run | TBD |
| 实体真机 | 支付宝 | 待后续设备 | TBD | TBD | Not run | TBD |

结果词汇限定为 Not run、Passed、Failed、Blocked、Unsupported，并写明日期、规则版本、环境及失败原因。正常后台、锁屏、重启、Doze、通知折叠、App 分身分别记录；单次前台成功不等于后台可靠。

真实样本提交公开仓库前需脱敏并经用户允许。若脱敏改变识别语义，用合成复现样本说明差异，不上传原文。不得将 test fixture 的 package／title 样本描述为真实 App 取样。

真机计划、待输入项和停止条件见[real-device-plan](real-device-plan.md)。通知可读取、正确解析与真实订单归属分别判定，不以其中一项替代其余项。
