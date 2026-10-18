# Issue 驱动任务索引

日期：2026-10-16。M1/M2及M3模拟器合成通知自动充值已通过，最终PR Android #57 / epay-go #21；[完整报告](../testing/M3-prototype-report.md)和[当前后续边界](../development/NEXT.md)。原42个Issues不重复创建，真实收款准入#8与M4真实App/真机/发布继续保留。

开发顺序：需求／研究／关键规格 → 模拟器采集 → 可靠上报 → 自动充值测试闭环 → 真机兼容性与发布准备。自动充值是首版目标；未知或歧义事件不能强行入账。

## M0 · 规格与研究

需求、许可证、通知身份、协议与匹配策略收敛。规划完成不等于授权开始实现。

研究结果：#1/#2/#3/#4/#6/#7 已按研究验收关闭，#10固定金额补丁已完成；#8真实收款准入仍需证据/决策；不因 M0 报告存在就关闭下游实现任务。跨库映射：#15/#20/#25 → epay-go #6；#8/#18/#22/#32 → epay-go #7；#26/#27/#28 → epay-go #8；#10/#27/#29 → epay-go #9。具体链接见 [M0 报告](../research/M0-review.md)。

Epic：[[Epic] 规格、研究与跨仓库开发基线](https://github.com/HuangYincan/epay-observer-android/issues/38)

| Issue | 类型 / 范围 | 依赖 |
| --- | --- | --- |
| [#1 冻结双通道、自动充值与模拟器优先的需求及验收基线](https://github.com/HuangYincan/epay-observer-android/issues/1) | spec / docs | — |
| [#2 研究旧观察器源码与许可证，确定可复用边界](https://github.com/HuangYincan/epay-observer-android/issues/2) | research / research | [#1](https://github.com/HuangYincan/epay-observer-android/issues/1) |
| [#3 定稿原生 Android 技术栈、工具链与 AVD 基线 ADR](https://github.com/HuangYincan/epay-observer-android/issues/3) | spec / android | [#1](https://github.com/HuangYincan/epay-observer-android/issues/1) |
| [#4 研究通知字段、来源边界与事件身份规则](https://github.com/HuangYincan/epay-observer-android/issues/4) | research / research | [#1](https://github.com/HuangYincan/epay-observer-android/issues/1)、[#2](https://github.com/HuangYincan/epay-observer-android/issues/2) |
| [#6 评审通知伪装、凭据及测试隔离的威胁模型](https://github.com/HuangYincan/epay-observer-android/issues/6) | spec / security | [#1](https://github.com/HuangYincan/epay-observer-android/issues/1)、[#4](https://github.com/HuangYincan/epay-observer-android/issues/4) |
| [#7 冻结事件上报 v1 契约及跨语言签名黄金向量](https://github.com/HuangYincan/epay-observer-android/issues/7) | spec / protocol | [#4](https://github.com/HuangYincan/epay-observer-android/issues/4)、[#6](https://github.com/HuangYincan/epay-observer-android/issues/6) |
| [#8 定稿同金额并发、迟到付款与金额复用策略 ADR](https://github.com/HuangYincan/epay-observer-android/issues/8) | spec / gateway | [#1](https://github.com/HuangYincan/epay-observer-android/issues/1)、[#4](https://github.com/HuangYincan/epay-observer-android/issues/4)、[#6](https://github.com/HuangYincan/epay-observer-android/issues/6) |
| [#10 固定 epay-go 与 New API 集成版本及跨仓库任务边界](https://github.com/HuangYincan/epay-observer-android/issues/10) | spec / integration | [#1](https://github.com/HuangYincan/epay-observer-android/issues/1)、[#7](https://github.com/HuangYincan/epay-observer-android/issues/7) |

## M1 · 模拟器双通道采集

首个实现切片 #9 + #5 已合并。#11/#12 按 SPEC-008 已合并，#13/#14 按 SPEC-009 交付合成解析。#16 实现身份持久化，真实 App 兼容性仍未验证。

建立Android测试工程、AVD和独立夹具，分别验证微信／支付宝合成通知。

Epic：[[Epic] 安卓模拟器中的微信／支付宝双通道采集](https://github.com/HuangYincan/epay-observer-android/issues/39)

| Issue | 类型 / 范围 | 依赖 |
| --- | --- | --- |
| [#9 建立 Android 工程、构建变体与最小 CI](https://github.com/HuangYincan/epay-observer-android/issues/9) | task / android | [#3](https://github.com/HuangYincan/epay-observer-android/issues/3)、[#2](https://github.com/HuangYincan/epay-observer-android/issues/2)、[#6](https://github.com/HuangYincan/epay-observer-android/issues/6) |
| [#5 建立可重复的 AVD 开发与测试环境](https://github.com/HuangYincan/epay-observer-android/issues/5) | task / emulator | [#3](https://github.com/HuangYincan/epay-observer-android/issues/3) |
| [#11 制作独立通知夹具 App 与双通道场景集](https://github.com/HuangYincan/epay-observer-android/issues/11) | task / emulator | [#9](https://github.com/HuangYincan/epay-observer-android/issues/9)、[#4](https://github.com/HuangYincan/epay-observer-android/issues/4)、[#5](https://github.com/HuangYincan/epay-observer-android/issues/5) |
| [#12 实现通知权限引导、监听回调与服务状态](https://github.com/HuangYincan/epay-observer-android/issues/12) | task / android | [#9](https://github.com/HuangYincan/epay-observer-android/issues/9)、[#4](https://github.com/HuangYincan/epay-observer-android/issues/4) |
| [#13 实现微信收款候选解析与负例拒绝](https://github.com/HuangYincan/epay-observer-android/issues/13) | task / android | [#4](https://github.com/HuangYincan/epay-observer-android/issues/4)、[#11](https://github.com/HuangYincan/epay-observer-android/issues/11)、[#12](https://github.com/HuangYincan/epay-observer-android/issues/12) |
| [#14 实现支付宝收款候选解析与负例拒绝](https://github.com/HuangYincan/epay-observer-android/issues/14) | task / android | [#4](https://github.com/HuangYincan/epay-observer-android/issues/4)、[#11](https://github.com/HuangYincan/epay-observer-android/issues/11)、[#12](https://github.com/HuangYincan/epay-observer-android/issues/12) |
| [#16 实现持久事件身份与通知更新去重](https://github.com/HuangYincan/epay-observer-android/issues/16) | task / android | [#4](https://github.com/HuangYincan/epay-observer-android/issues/4)、[#13](https://github.com/HuangYincan/epay-observer-android/issues/13)、[#14](https://github.com/HuangYincan/epay-observer-android/issues/14) |

## M2 · 可靠认证上报

设备绑定、持久队列、实际HTTPS配对/上传/接收、后台恢复和诊断均已交付，见M2系列报告及最终M3真实联调证据。

Epic：[[Epic] 设备认证与可靠事件上报](https://github.com/HuangYincan/epay-observer-android/issues/40)

| Issue | 类型 / 范围 | 依赖 |
| --- | --- | --- |
| [#19 实现持久事件队列及失败恢复](https://github.com/HuangYincan/epay-observer-android/issues/19) | task / android | [#16](https://github.com/HuangYincan/epay-observer-android/issues/16)、[#7](https://github.com/HuangYincan/epay-observer-android/issues/7) |
| [#17 实现设备配对、受限凭据与本地安全存储](https://github.com/HuangYincan/epay-observer-android/issues/17) | task / security | [#9](https://github.com/HuangYincan/epay-observer-android/issues/9)、[#7](https://github.com/HuangYincan/epay-observer-android/issues/7)、[#6](https://github.com/HuangYincan/epay-observer-android/issues/6)、[#15](https://github.com/HuangYincan/epay-observer-android/issues/15) |
| [#21 实现签名上传、错误分类与幂等重试](https://github.com/HuangYincan/epay-observer-android/issues/21) | task / android | [#19](https://github.com/HuangYincan/epay-observer-android/issues/19)、[#17](https://github.com/HuangYincan/epay-observer-android/issues/17)、[#7](https://github.com/HuangYincan/epay-observer-android/issues/7) |
| [#23 实现后台调度、重启恢复与失联提示](https://github.com/HuangYincan/epay-observer-android/issues/23) | task / android | [#19](https://github.com/HuangYincan/epay-observer-android/issues/19)、[#21](https://github.com/HuangYincan/epay-observer-android/issues/21)、[#12](https://github.com/HuangYincan/epay-observer-android/issues/12) |
| [#24 实现状态页、事件状态与脱敏诊断导出](https://github.com/HuangYincan/epay-observer-android/issues/24) | task / android | [#12](https://github.com/HuangYincan/epay-observer-android/issues/12)、[#19](https://github.com/HuangYincan/epay-observer-android/issues/19)、[#21](https://github.com/HuangYincan/epay-observer-android/issues/21) |
| [#15 网关：增加设备、收款绑定、事件与证据模型](https://github.com/HuangYincan/epay-observer-android/issues/15) | task / gateway | [#10](https://github.com/HuangYincan/epay-observer-android/issues/10)、[#7](https://github.com/HuangYincan/epay-observer-android/issues/7)、[#8](https://github.com/HuangYincan/epay-observer-android/issues/8) |
| [#20 网关：实现事件认证、持久接收与重放防护](https://github.com/HuangYincan/epay-observer-android/issues/20) | task / gateway | [#15](https://github.com/HuangYincan/epay-observer-android/issues/15)、[#7](https://github.com/HuangYincan/epay-observer-android/issues/7)、[#17](https://github.com/HuangYincan/epay-observer-android/issues/17) |
| [#25 建立 Android／网关协议一致性与故障契约测试](https://github.com/HuangYincan/epay-observer-android/issues/25) | test / protocol | [#21](https://github.com/HuangYincan/epay-observer-android/issues/21)、[#20](https://github.com/HuangYincan/epay-observer-android/issues/20) |

## M3 · 自动充值测试闭环

唯一匹配、事务幂等、New API自动入账、异常测试与Release隔离已在合成原型范围验证。

Epic：[[Epic] epay-go 到 New API 自动充值测试闭环](https://github.com/HuangYincan/epay-observer-android/issues/41)

| Issue | 类型 / 范围 | 依赖 |
| --- | --- | --- |
| [#18 网关：实现选定的同金额占用与复用保护](https://github.com/HuangYincan/epay-observer-android/issues/18) | task / gateway | [#8](https://github.com/HuangYincan/epay-observer-android/issues/8)、[#15](https://github.com/HuangYincan/epay-observer-android/issues/15) |
| [#22 网关：实现账户范围内的唯一订单匹配](https://github.com/HuangYincan/epay-observer-android/issues/22) | task / gateway | [#20](https://github.com/HuangYincan/epay-observer-android/issues/20)、[#18](https://github.com/HuangYincan/epay-observer-android/issues/18) |
| [#26 网关：原子消费观察事件并复用订单记账事务](https://github.com/HuangYincan/epay-observer-android/issues/26) | task / gateway | [#22](https://github.com/HuangYincan/epay-observer-android/issues/22) |
| [#27 网关：可靠触发商户通知并验证 New API 幂等](https://github.com/HuangYincan/epay-observer-android/issues/27) | task / integration | [#26](https://github.com/HuangYincan/epay-observer-android/issues/26)、[#10](https://github.com/HuangYincan/epay-observer-android/issues/10) |
| [#28 网关：增加事件追踪、异常核对与暂停自动确认](https://github.com/HuangYincan/epay-observer-android/issues/28) | task / gateway | [#22](https://github.com/HuangYincan/epay-observer-android/issues/22)、[#26](https://github.com/HuangYincan/epay-observer-android/issues/26) |
| [#29 搭建隔离的 epay-go + New API 模拟器联调环境](https://github.com/HuangYincan/epay-observer-android/issues/29) | task / emulator | [#5](https://github.com/HuangYincan/epay-observer-android/issues/5)、[#11](https://github.com/HuangYincan/epay-observer-android/issues/11)、[#25](https://github.com/HuangYincan/epay-observer-android/issues/25)、[#27](https://github.com/HuangYincan/epay-observer-android/issues/27) |
| [#30 验收微信形态通知到 New API 自动充值闭环](https://github.com/HuangYincan/epay-observer-android/issues/30) | test / emulator | [#29](https://github.com/HuangYincan/epay-observer-android/issues/29)、[#13](https://github.com/HuangYincan/epay-observer-android/issues/13) |
| [#31 验收支付宝形态通知到 New API 自动充值闭环](https://github.com/HuangYincan/epay-observer-android/issues/31) | test / emulator | [#29](https://github.com/HuangYincan/epay-observer-android/issues/29)、[#14](https://github.com/HuangYincan/epay-observer-android/issues/14) |
| [#32 验收重复、同额、迟到、断网与崩溃故障场景](https://github.com/HuangYincan/epay-observer-android/issues/32) | test / emulator | [#30](https://github.com/HuangYincan/epay-observer-android/issues/30)、[#31](https://github.com/HuangYincan/epay-observer-android/issues/31)、[#28](https://github.com/HuangYincan/epay-observer-android/issues/28)、[#23](https://github.com/HuangYincan/epay-observer-android/issues/23) |
| [#34 验证 Release 不包含模拟来源与充值旁路](https://github.com/HuangYincan/epay-observer-android/issues/34) | test / security | [#9](https://github.com/HuangYincan/epay-observer-android/issues/9)、[#11](https://github.com/HuangYincan/epay-observer-android/issues/11)、[#20](https://github.com/HuangYincan/epay-observer-android/issues/20)、[#32](https://github.com/HuangYincan/epay-observer-android/issues/32) |

## M4 · 兼容性与发布准备

独立记录真实App／真机证据，完成运行与发布计划；不自动接生产或发布。

Epic：[[Epic] 真实兼容性验证与发布准备](https://github.com/HuangYincan/epay-observer-android/issues/42)

| Issue | 类型 / 范围 | 依赖 |
| --- | --- | --- |
| [#33 探索模拟器运行真实微信／支付宝及通知可达性](https://github.com/HuangYincan/epay-observer-android/issues/33) | research / research | [#30](https://github.com/HuangYincan/epay-observer-android/issues/30)、[#31](https://github.com/HuangYincan/epay-observer-android/issues/31) |
| [#35 制定真机双通道小额收款验收与准入门槛](https://github.com/HuangYincan/epay-observer-android/issues/35) | spec / research | [#32](https://github.com/HuangYincan/epay-observer-android/issues/32)、[#33](https://github.com/HuangYincan/epay-observer-android/issues/33) |
| [#36 完成配对、部署、诊断与故障恢复手册](https://github.com/HuangYincan/epay-observer-android/issues/36) | task / docs | [#32](https://github.com/HuangYincan/epay-observer-android/issues/32)、[#24](https://github.com/HuangYincan/epay-observer-android/issues/24)、[#28](https://github.com/HuangYincan/epay-observer-android/issues/28) |
| [#37 定义 APK 签名、可复现产物与发布检查清单](https://github.com/HuangYincan/epay-observer-android/issues/37) | spec / android | [#34](https://github.com/HuangYincan/epay-observer-android/issues/34)、[#36](https://github.com/HuangYincan/epay-observer-android/issues/36)、[#2](https://github.com/HuangYincan/epay-observer-android/issues/2) |

## 使用规则

- `status:proposed` 表示待实施／待评审；`status:blocked` 表示有未完成前置依赖。
- Epic 汇总子任务，不代替各任务验收。
- 网关工作在本仓库先建协调任务；实际后端实现位于 epay-go，并回链对应 Issue／PR。
- 关闭任务时同时更新相关 Spec、验收矩阵与兼容性证据；不因为代码编译通过就宣称真实收款可用。


## 真实链路后续批次

- Android #77 / epay-go #22：SPEC-033首次认证接收notification的不可入账候选诊断；真实收款解析/订单归属/生产自动匹配仍由#33/#8/#42跟进。
- Android #82 / epay-go #24：SPEC-036受控真实notification单订单事务结算；网关开发与合成测试独立交付，真实模板、Android准入、实际New API增额和Proposed试验政策仍待后续证据。#81后台诊断独立，不阻塞可完成的结算开发。
- Android #84：SPEC-037现存最多2条私有通知检查；实际读取后宿主处理失败，原文删除、used保留；原子发布/部分JSON回归已修复，不能未经新增范围重读。
