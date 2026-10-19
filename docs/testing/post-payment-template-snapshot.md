# 付款反馈后的现存通知检查

2026-10-19；#82/#84，SPEC-040 POST-01..03。用户在SPEC-039窗口内记录付款反馈，但实际窗口为120085ms/0回调；该报告不覆盖、不升级。用户随后要求代理直接查看模拟器收款账号，首页有App内收款提示，系统通知总开关及可见支付宝分类开启。查看未进入账号/交易详情、未修改设置，私有截图已删除；首页内容不是系统通知或订单归属证明。

本批不再要求付款、不监听新通知。新增固定POST_PAYMENT现存检查，保留原四代snapshot和已消费的live marker；前三代仍严格COMPLETE/2，field-roles允许COMPLETE/0..2。固定来源、两条上限、空posted回调、有限字段、角色脱敏与原子写入不变；新范围不可复用，不登记生产规则或订单。

## 验证和实际结果

- POST-01：新增完整宿主合成编排先因新范围尚未实现而失败，新增范围后通过；覆盖两条提取/脱敏/清理、第四代零条前置、前三代零条拒绝、live未消费或listener仍授权时拒绝。随后补充监听元数据瞬时/持续超时、首次变更前基线已落盘及写失败不变更回归，共57项宿主测试通过。合成结果不是实际兼容性证据。
- POST-02：既有依赖离线、strict verification的`:sample:assembleDebugAndroidTest :sample:lintDebug`通过；实际测试APK无网络权限、固定新范围及空posted回调、禁止通知身份/网络调用检查通过。测试APK SHA-256 `24c123304ee9207598e565164e29c45964717f7f5075acf65925a544a3f8ac59`；主sample APK仍为`b42a1e459571dd89442012275d5563bc3cebfbbf12da937d0174472cfa2257c3`，生产APK未改。
- POST-03 run1：实际COMPLETE/2，一条空分组摘要、一条收款标题子通知。子通知匹配完整标题语法及正的规范金额判断，标题骨架仍为`你已成功收款<number>元（<text>）`；括号为unknown_opaque_label，没有输出实际金额/姓名。正文有限模板包含`转入余额宝`，bigText为空，category/style为none，group child、messages_present=false、auto_cancel=true。payment_eligibility始终false。尚未取得括号角色/聊天反例或可靠订单归属，不能启用真实解析/结算；更不能把历史列表升级为SPEC-039有效窗口内的正向回调。

## 清理边界

run1取得并脱敏两条后，双方原文与consent/临时文件已删除，测试监听撤销/测试进程强停和四代旧状态保留成功；但原始报告的全监听列表相等校验为false，cleanup_ok=false。报告未保存当次完整before集合或错误类别，因此不能分辨实际集合变化与查询失败，不将此运行写成全部恢复成功。

独立后查确认test/sample两个内容listener都未授权；五代marker保留，状态计数依次2/2/2/0/2，全部无原文/consent/临时文件；live消费marker也保留且无原文。没有为恢复而改动其他listener权限，完整before集合不能重建，原失败报告不改写。前后及补查隔离账务仍原4订单/4消费/4收入/4topup，总增额30,000,000；不据此宣称实际线上New API余额状态。

随后改进宿主：任何安装/授权前先写独立私有listeners-before.json，清理后写listeners-after.json，集合也保留于私有报告但不打印。审查指出初版仅放入内存，补充首次安装/准备/授权时基线文件已存在的回归，实际复现失败后修复；基线写失败即停止设备变更。遇一次元数据超时只补读一次，不重新授权或采样；持续失败或实际集合不等仍失败。完整编排回归通过，实际scope未重跑，不能借修复升级run1清理结论。

实际私有证据：`/private/tmp/observer-active-snapshot-20261019-post-payment-run1`的原report/templates及独立`cleanup-recheck.json`。新的永久marker已消费，不重开或连续追加slot；旧零回调报告、失败尝试和账务不清除。原文不进stdout/模型上下文/公开仓库，公开报告仅保留脱敏研究结果。#82/#8/#33/#42与后台#81均不因本批关闭。
