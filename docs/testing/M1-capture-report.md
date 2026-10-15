# #11/#12 通知夹具与监听验证

日期：2026-10-15。依据 SPEC-008、SPEC-001、ADR-0005；范围为 CAP-01..05。

## 仓库整理

PR #43（M0）与 #44（#9/#5 工程）已合并；主线基线为 `6b7e394`，#9/#5 已关闭。旧分支在确认祖先关系后删除；当前工作在独立 `feat/11-12-notification-capture` 分支。网关仍为 `4d855060439df247fc60a529139e80c7e4b757c0`，未修改。M0 的 #8/#10 准入问题继续开放。

## 实现范围

- 独立 `dev.epay.observer.fixture` debug APK，双通道合成形态与更新、相同文案两条、摘要／子项、退款／支出／聊天、畸形／超长字段场景。无订单号或网络权限。
- 客户端按 flavor 隔离来源。测试来源必须包名／opPkg／当前 user 一致、当前签名集合与同机构建客户端相同；正式来源列表为空。
- 系统通知回调只采集 NotificationObservation；100 个 key 的有界内存诊断，不是持久队列或 ReceiptEvent。相同 key 修订保留首次时间，不同 key 同文案分开；恢复读取始终保留 uncertain。
- UI 分别展示授权与服务连接，提供系统设置与重连入口；只展示计数，不展示原文、key、签名或设备信息。

## 验证记录

| 项目 | 状态 | 证据／界限 |
| --- | --- | --- |
| 构建／严格离线依赖校验 | Pass | 三个应用 APK 和 AndroidTest；没有新增库，只新增 fixture 锁文件 |
| JVM | Pass，8/8 | 3 个协议 golden + 5 个观察身份／来源／容量测试 |
| 三个最终 APK 边界 | Pass | 系统绑定权限、fixture 查询隔离、备份／网络政策、发送器无网络权限；不输出证书 |
| AVD 合成投递与生命周期 | Pass，5/5 | `0bbc4b9`，23.374 秒；见 [仪器日志](evidence/capture/instrumentation.txt) 与 [摘要](evidence/capture/summary.json) |
| Linux CI | Pass | 代码提交 `0bbc4b9`；run `38819456437`，见 evidence/capture/ci-run.json |
| API29／真实微信支付宝／实体设备 | Not run | 不主张真实收款可用 |
| EMU-01..14 完整充值 | Not run | 没有解析、队列、网关／New API 实现或部署 |

首次编译时纠正了非公开的 UserHandle identifier 用法，改为公开 UserManager 的本地 user serial 查询；没有使用反射／隐藏 API。首轮 AVD 失败未计为通过，失败不计通过。后续两轮在系统窗口同步／进程接入阶段超时，涉及系统应用 ANR；普通 Android 重启后 system_server 也未恢复。最终关闭既有模拟器进程，使用同一 AVD 以 `-no-snapshot-load -no-snapshot-save` 冷启动，未 wipe、未重建 AVD、未改配置。恢复后核对已安装三个测试 APK 的 SHA-256 全部与本地产物相等，直接运行完整 5 项仪器测试通过。该过程说明本轮环境出现过不稳定，不承诺后续永不发生。

实际运行配置为 API37 / Google Play / arm64-v8a / 4 GiB / 1 CPU；与首批报告的历史 2 GiB 配置分开记录，本轮未修改 RAM/CPU。产物 hash 见 [artifacts.json](evidence/capture/artifacts.json)，最新代码 CI 见 [ci-run.json](evidence/capture/ci-run.json)。

## CAP 验收细分

| 编号 | 结果与证据 |
| --- | --- |
| CAP-01 | 两组形态、同文案不同 key、同 key 更新／重投、摘要／子项、退款／支出／聊天经系统投递；字段与 flags 断言通过。没有解析、充值断言，负例仍是等待后续解析器拒绝的输入。 |
| CAP-02 | AVD 检查成功来源的签名存在、其他包被拒且不进入 journal；JVM 验证错误包／opPkg／user／空或不同签名集合均拒绝。不同签名 APK 的安装攻击未在 AVD 单独执行。 |
| CAP-03 | 系统授予／撤销监听、UI 权限与连接状态分离、断连停止接收、恢复 active 快照不改变首次时间、removed 不删除历史；所有观察仍 uncertain。 |
| CAP-04 | AVD 非 CharSequence／超长字段拒收为 unsupported，SpannableString 安全提取；JVM 满容量不淘汰历史、重复投递不增加身份记录。 |
| CAP-05 | 三种最终 APK 校验，正式包没有夹具查询／来源／场景；绑定权限与 TLS／备份边界正确。CI 验证两个测试模块均没有 release 测试变体。 |

## 复现

见 [开发指南](../development/build.md)。已有 API37 Google Play arm64 AVD；测试只安装本项目三个测试 APK，给独立发送器授予通知权限，授权／撤销本客户端监听。测试结束撤下夹具通知并撤销监听。`cmd notification post` 的无关来源负例使用固定 tag `observer-unrelated`，可能保留一条可手动划掉的合成 shell 通知；未批量清理其他通知。不会清空模拟器或操作支付 App。

本批不实现 #13/#14 解析或 #16/#19 持久化。下一步建议先做双通道合成解析与严格负例，再接稳定事件身份和事务出队，不能直接从内存观察计数跳到自动匹配。
