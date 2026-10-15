# 兼容性矩阵

产品场景没有实测结果。宿主工具/AVD 元数据已核对，用户报告 AVD 启动已通过；不等于观察器或支付 App 兼容性通过。

| 层次 | 来源 | Android / 镜像 | 设备 / ABI | 支付 App 版本 | 结果 | 样本与报告 |
| --- | --- | --- | --- | --- | --- | --- |
| 模拟器夹具 | 微信形态 | API 37.0 Google Play rev 6 | Medium_Phone / arm64-v8a | 不适用 | Not run | ADR-0004 环境记录 |
| 模拟器夹具 | 支付宝形态 | API 37.0 Google Play rev 6 | Medium_Phone / arm64-v8a | 不适用 | Not run | ADR-0004 环境记录 |
| 模拟器真实 App | 微信 | TBD | TBD | TBD | Not run | TBD |
| 模拟器真实 App | 支付宝 | TBD | TBD | TBD | Not run | TBD |
| 实体真机 | 微信 | 待后续设备 | TBD | TBD | Not run | TBD |
| 实体真机 | 支付宝 | 待后续设备 | TBD | TBD | Not run | TBD |

结果词汇限定为 Not run、Passed、Failed、Blocked、Unsupported，并写明日期、规则版本、环境及失败原因。正常后台、锁屏、重启、Doze、通知折叠、App 分身分别记录；单次前台成功不等于后台可靠。

真实样本提交公开仓库前需脱敏并经用户允许。若脱敏改变识别语义，用合成复现样本说明差异，不上传原文。不得将 test fixture 的 package／title 样本描述为真实 App 取样。
