# M0 研究与规格收敛报告

日期：2026-10-15。这是 M0 阶段的研究记录；该阶段未进行功能开发。后续用户已批准 #9/#5 首批工程，进展见 [工程基线报告](../testing/M1-foundation-report.md)。M0 不宣称所有退出条件已批准，#8/#10 仍为开放项。

## 接手核对

观察器初始 HEAD `d432e576d5e79f4a0074c0de669f0dc0282ecd18`，epay-go HEAD `4d855060439df247fc60a529139e80c7e4b757c0`，两工作区均干净。已读 AGENTS、README、docs 全部规划、8 个 M0 子任务与 Epic #38，并检查 42 个观察器 Issues、5 个 Milestones 和网关现有 #5，未重复建立观察器任务。

环境已有 Studio、SDK/API37、JBR25 和 AVD。只读取版本/配置，不安装工具、启动 AVD、部署容器、操作支付账号或写网关源码。GitHub 访问曾被沙箱阻断本机代理；授权后读写成功，不能据此判断宿主机坏了。

## 逐项结论与证据

| Issue / 需求 | 收敛结果 | 证据与尚待工作 |
| --- | --- | --- |
| #1 / FR-01..08 | 双通道、全自动正常流程、模拟器优先；三类产品证据分层 | PRD、验收矩阵；工期/真机/生产仍 TBD |
| #2 / 复用边界 | 旧原创 MIT、内嵌 ZXing Apache-2.0；未复制旧代码；允许参考旧系列业务流程 | M0-source-audit；用户已确认新仓库 MIT，LICENSE 已添加 |
| #3 / 工程基线 | Kotlin/Views/Room/WorkManager，min29、compile/target37、AGP9.2.1、Gradle9.4.1、JBR25 | ADR-0004 的本机元数据与官方 POM；构建未运行，依赖锁在 #9 |
| #4 / FR-01/05 | 观察记录和事件分离、原 body 不变、冲突 uncertain，不把 key 当流水 | ADR-0005 的 ID-01..11；真实 App 文案/签名/投递未测 |
| #6 / FR-08 | 明确聊天伪装、多设备、账号切换、重放、丢失、SSRF、测试隔离及残余风险 | M0-threat-model，逐条映射验收 |
| #7 / FR-03/08 | events v1-rc1 字段、精确签名、范围授权、ACK/错误、replay 与 event 幂等 | SPEC-002、vectors.json，Python/JCA 离线一致；Kotlin/Go/DB 未运行 |
| #8 / FR-04/05 | 有限冷却期不充分；严格测试域永久金额 claim、不改尾数、不匹配未来订单 | ADR-0003、SPEC-003、5 组信息不可区分反例；真实自动准入未解决，保留开放 |
| #10 / FR-04/05 | 固定两端 SHA，确认现有网关持久回调/两端事务幂等机制 | 集成文档；New API 业务金额核对差异阻止 M3，待修正版本/补丁决策 |

既有通知 ACK 与充值状态已分开；事件消费、订单、余额、流水及持久通知条件要求同一网关事务。New API 是另一个数据库，通过回调至少一次投递及事务幂等达成最终一致。不能承诺跨库原子提交。

## 已执行实验

在已有 Python 3 与 Studio JBR 25 上执行：

```sh
python3 docs/research/experiments/verify_vectors.py
python3 docs/research/experiments/indistinguishable.py
git diff --check
```

- 3/3 黄金向量：微信合成 body、含中文 UTF-8 的支付宝 body、同事件新请求重试；Python 与 Java JCA 的 body SHA-256、待签字节与 HMAC 全部一致。
- 12/12 密码学篡改检查：路径、body 空白、LF/CRLF、末尾 LF 改变不会得到原签名。没有执行 HTTP 接收器，不据此声称 V-01..20 行为测试通过。
- 5/5 反例：相同观察可对应一次/两次付款；冷却期为 0、600、86400、31536000 秒时，旧码迟到付款与新单付款仍可有相同观察。实验只展示信息不足，不是生产 matcher 单元测试。
- 本轮文档还检查相对链接、Issue 追溯、敏感材料和状态一致性；具体结果以最后校验输出为准。

Android 构建、Keystore、模拟器夹具、真实支付 App、实体真机、PostgreSQL 并发／回滚、Go 协议实现、New API 充值、NET-01..04 全部 **Not run**。宿主未发现 PATH 中的 Go，本轮未安装，也未将 JVM 验证称为 Go 验证。

## 跨仓库追溯

| 观察器协调任务 | epay-go 对应任务 | 当前交付 |
| --- | --- | --- |
| #15/#20/#25 | [epay-go #6](https://github.com/HuangYincan/epay-go/issues/6) | 设备、授权、持久接收、签名向量；无功能 PR |
| #8/#18/#22/#32 | [epay-go #7](https://github.com/HuangYincan/epay-go/issues/7) | 金额占用／迟到反例／真实来源门槛；无功能 PR |
| #26/#27/#28 | [epay-go #8](https://github.com/HuangYincan/epay-go/issues/8) | 共享事务、observer evidence、复用通知恢复；无功能 PR |
| #10/#27/#29 | [epay-go #9](https://github.com/HuangYincan/epay-go/issues/9) | 固定基线、New API 契约准入、safehttp 测试链路；无部署 |

M0 Epic 的退出条件按研究交付评审，不要求提前完成 M2/M3 的功能 PR 或联调报告；那些证据仍是各实现任务的关闭条件。已写文档不能关闭未实现的功能 Issue。

## 必须明确的产品限制

1. 真实通知不能单独证明到账或订单归属。即使唯一同额候选、首次金额、合法设备签名，仍可能无关付款或伪装内容。真实事件全转人工也不算完成自动充值目标。因此生产策略保留阻塞，而不是宣布问题已解决。
2. 合成闭环可以验证系统工程正确性：按测试策略无订单提示、无人工逐单确认，双通道各自动一次；历史金额永久占用避免测试中旧事件串新单。它的吞吐／复用限制不能当作真实运营方案。
3. 固定 New API 的事务幂等不代表金额契约完整。建议保留 NEW-02 严格验收，后续选修正版本或经用户明确批准的最小兼容补丁；本轮未修改第三方项目。

## 建议首个实现范围（后续已批准 #9/#5）

先 #9 + #5：最小 Kotlin 工程、production/fixture 构建边界、Views 状态页骨架、现有 AVD 可复现记录、精确依赖锁和基础 CI；检查 release 无测试来源。接着 #11/#12：独立 `dev.epay.observer.fixture` 发微信/支付宝合成通知，监听权限、连接状态和最小观察落盘。该切片不包含网关记账、真实账号、服务部署或发布 APK。

后续按 #16→M2→M3 逐步推进；配对契约在 #17/#20 前定稿。真实来源策略与 New API 金额缺口不妨碍最小通知工程，但阻止宣称真实自动充值或完整 M3 验收。

用户于 2026-10-15 已确认 MIT、参考旧 V免签系列业务流程，并随后批准首批 #9/#5；旧代码仅供参考，其他系列仓库仍须独立审计。#11/#12、真实收款准入及 New API 修正方向仍待各自后续任务。
