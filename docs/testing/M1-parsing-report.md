# #13/#14 双通道合成解析验证

日期：2026-10-15。关联 SPEC-009、FR-01/02、PARSE-01..05；基线 PR #45 已合并（main `90e3696`）。网关仍为 `4d855060`，没有修改。

## 实现

两组规则 `fixture-wx-v1` / `fixture-ali-v1` 只存在 fixture 源码集；production 注册表为空。通过来源边界、标题／模板／category／channel／额外内容及多字段一致性检查后，纯函数输出整数分候选。候选身份始终 uncertain，没有 event_id、交易号、付款时间或发送能力。

金额使用 Long 整数运算，拒绝非法格式及超界值，不舍入。摘要、聊天、退款、支出、推广、聚合、未知样式和字段冲突均有明确原因。恢复／移除通知被忽略，revision > 1 停止认可，留待 #16 持久身份处理。

界面只显示分类计数与原因；两个通道独立开关、偏好可保存。开关改变只重新计算内存预览，不创建／补发事件。

## 验证

| 项目 | 结果 | 界限 |
| --- | --- | --- |
| 严格离线构建 | Pass | 三个应用 APK、AndroidTest；未新增依赖或开发工具 |
| production JVM | 9/9 Pass | 共同基线 8 + 正式解析注册表隔离 1 |
| fixture JVM | 124/124 Pass | 共同基线 8 + 116 个合成语料；两通道各 58 例 |
| API37 AVD | 6/6 Pass，31.301 秒 | 包括既有 5 项及新增实际投递解析／开关测试；本轮未重启或清除 AVD |
| 最终 APK | 三种均 Pass | fixture 文案／规则版本不进入 Release；无额外权限／网络旁路 |
| lint | 0 errors | 正式 0 warnings；夹具保留已有 1 条测试 UI 国际化 warning |
| GitHub CI | Pass | 实现提交 b8941af，run 38823578072；含两变体 JVM、lint、APK 隔离检查 |
| API29、真实 App、真机、自动充值 | Not run | 不主张真实收款兼容或完整 M1／M3 完成 |

AVD 正例验证 provider、1000 分、CNY 和独立规则版本。16 个新负例分别覆盖双通道金额冲突、外币、退款、聊天伪装、推广、Inbox 聚合、三位小数与 subText 隐藏金额；既有摘要／支出／畸形字段测试及 116 例语料共同补足边界。授权、来源签名过滤继续走真实系统回调，无订单号注入。

测试最后撤下夹具通知并撤销监听，恢复测试前两个解析开关。只触及本项目测试 App；无服务部署、真实支付账号操作、APK 发布或网关变更。

## 固定证据

- 实现提交：`b8941afa761f5d41821a7f6abffbe84cebda4a70`。
- [仪器测试完整输出](evidence/parsing/instrumentation.txt)。
- [APK SHA-256 与两变体 JVM 汇总](evidence/parsing/summary.json)；不包含通知原文、真实账号或证书私钥。
- [Linux CI 运行记录](evidence/parsing/ci-run.json)。
- [合成语料说明](parsing-v1/README.md) 与 `synthetic-cases.json`。

## 下一步与限制

#16 持久事件身份仍未实现。应先定义观察 revision、身份墓碑和不可变事件封存的事务关系，再接 #19 可靠队列；不能直接把本批候选预览用来匹配订单。#8/#10 的真实来源准入与 New API 金额契约缺口仍开放。
