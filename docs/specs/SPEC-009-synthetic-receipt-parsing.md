# SPEC-009 双通道合成收款候选解析

状态：#13/#14 实施基线；2026-10-15。依据 FR-01/02、SPEC-001/008、ADR-0005；#11/#12 已由 PR #45 合并。

## 输入、信任与输出

解析器消费已经过 Capture 来源过滤的 NotificationObservation，仍复核 package/opPkg、有效的本地 user serial（当前用户一致性由 Capture 核验）、非空签名元数据和夹具版本 1。此复核不代替系统签名核验。production 的 ParserRegistry 始终返回 NO_VERIFIED_RULE，不能通过通道开关启用测试规则。fixture 规则、文案、版本只存在 fixture 源码集。

结果是 Candidate / Ignored(reason) / Unsupported(reason)。Candidate 只包含 provider、amountMinor: Long、currency=CNY、ruleVersion、identityUncertain=true；没有 event_id、订单号、支付流水号、支付时间、发送队列或自动确认能力。不把通知时间作为到账时间。UI 的解析预览是对至多 100 条内存观察的纯函数计算，不保存或发送候选。

微信与支付宝规则分别命名 `fixture-wx-v1` / `fixture-ali-v1`，不是旧项目或真实 App 规则。两组配置独立；关闭某通道不会禁用另一通道。设置保存到本地偏好，通知回调不读写偏好；重新启用只重算诊断，不创建事件。没有已验证规则的 production 显示禁用的解析开关。

## 严格语法

| 规则 | title（必须完全相等） | text / bigText（全字段匹配） |
| --- | --- | --- |
| fixture-wx-v1 | 微信形态 · 合成收款 | 收款 AMOUNT 元 |
| fixture-ali-v1 | 支付宝形态 · 合成到账 | 到账 AMOUNT 元 |

- channel 必须为 `synthetic-v1`，category 必须为 status；template 只接受 Android BigTextStyle。无 group，且不含 GROUP_SUMMARY。Inbox/Messaging/未知样式不推测。
- text / bigText 至少一个非空；存在的每个字段都必须独立合法，金额必须一致。不能忽略非法 text 而使用合法 bigText。标题不匹配时整体 unsupported，不能从标题抽取金额兜底。
- additional text（subText、summaryText、textLines、messages、historicMessages、title.big）存在即 unsupported，避免忽略隐藏的第二笔金额／聚合内容。只保存“存在”布尔值，不保存这些额外内容。
- AMOUNT 只接受 ASCII `0` 或非零开头的整数，最多 10 位，可有 1 或 2 位小数。以整数运算转换分；范围 1..999999999999，与 SPEC-002 一致。这是协议范围，不是生产收款限额。
- 零、负数、正号、负零、指数、逗号、前导零、空白、全角数字、不可见字符、三位小数、溢出一律拒绝，不做浮点、四舍五入或宽松归一化。单位仅“元”；其他单位输出 CURRENCY。
- 预先识别的聊天／退款／支出／推广／聚合形态可 ignored；未知文本／格式必须 unsupported。任何一项检查失败都不产生 Candidate。

## 生命周期保守边界

removed 或恢复快照 ignored；revision > 1 unsupported（等待 #16 持久身份裁决）。相同 key 的完全重复观察不增加 journal 记录；不同 key 同文案可分别成为文案候选，但始终 identityUncertain，不能证明两笔真实付款。持久事件创建、不可变 body 与出队事务仍由 #16/#19 后续实现。

## 可解释结果与界面

原因码稳定，不包含通知原文或金额。显示候选／忽略／未支持的计数及原因汇总，明确“候选不是到账证明”。不显示通知 key、金额、证书或原文。未知来源在 Capture 层被拒，不进入解析预览。

## 验收与证据层次

- PARSE-01：两组独立正例；整数／一位／两位小数、最小和上限、text-only/bigText-only、多字段一致。
- PARSE-02：两组分别覆盖退款、支出、聊天伪装、推广、摘要／聚合、多金额、币种冲突、未知模板、额外内容、畸形与超长字段；逐项明确原因。
- PARSE-03：金额非法集合与边界；无浮点／舍入；两字段一个非法时不降级接受；禁用通道及重新启用互不串扰。
- PARSE-04：AVD 实际通知回调到解析结果；双通道候选、冲突金额、币种、推广／聚合／聊天负例、更新／恢复停止认可，开关 UI 和本地配置可用。
- PARSE-05：最终 productionRelease 无两组夹具规则、版本、文案、包查询和旁路；production JVM 验证注册表不能被开关启用。

全部是合成解析测试；真实微信／支付宝、API29、实体真机、完整 EMU-01..14 充值仍未验收。没有部署服务、支付账号操作或新开发工具安装。
