# 补充收款模板采样准备

2026-10-18；#82/#84，SPEC-039，承接SPEC-038。用户确认历史样本对应真实扫码收款，但不记得括号内容；当前历史原文已删、现存通知为0。新范围只准备一次本地明确开始的120秒/最多2条title/text采样，不能确认真实订单或给New API加余额，付款由用户自行决定和操作。

实现：独立永久marker保留原used/scan/unpaid-retry与测试包四代snapshot记录；默认停止、刷新不开始、每会话最多开始一次。新范围Android容量为2，120秒/来源/断连等门控复用既有实现。宿主强制本地控制、旧标记前置、2条状态/提取上限、完整本机角色判定，成功/失败删原文与恢复监听。精确APK hash相同才跳过重装。新范围开始前及每秒状态采样检查支付宝进程存在；PID不输出，不检查账号页，也不声称保活或登录已验证。

验证已完成：严格依赖离线sample主/测试APK构建、JVM单测与lint；主APK隔离检查；50项宿主合成测试；ego-browser纯页面WAITING→绿色119秒→结束/刷新不可重开的模拟流程，页面明确“仅补模板、不给New API加余额”。模拟页面不连接AVD、不代表真实付款或通知结果。

两轴独立审查发现新容量为2，而宿主旧校验要求LIMIT必须为32，正常两条终态会在提取前失败并进入原文清理。先新增贯穿execute的合成回归，实际复现失败；修复为按范围传入容量，新范围LIMIT/2可以提取、脱敏、清理与恢复监听，旧范围仍要求LIMIT/32，拒绝新范围超限。两轴复审均无剩余阻塞；这不是实际采样或真实支付验证。

实际设备只运行prepareOnly，不授权listener、不准备或消费新范围、不读取通知正文。run1测试进程crash、run2准备成功；因改进PID读取资源关闭方式和增加准备时进程验证，最终产物另做run3，其ADB前置查询超时。保留同一userdata正常终止旧AVD后冷启动，独立确认旧进程退出与唯一新实例完成启动；未清数据或恢复snapshot。run4安装最终runner后prepareOnly超时，监听列表恢复；支付宝退出历史当时新增启动ANR，但不能据此解释历史后台退出或宣称保活修复。run5精确APK hash相同跳过重装，最终prepareOnlyReceiptTemplate实际OK (1 test)，官方来源/原三个live marker/新marker未用/原文不存在/门控停止/未授权listener均通过，随后强停自有两包且监听列表不变。不检查账号页，不把进程存在当登录核验。

run5补充保留审计前两次有ADB命令超时，失败记录保留；最终按范围分组的只读复核通过：四代snapshot marker均存在，前三代COMPLETE/2、field-roles COMPLETE/0，全部无raw/consent/临时文件；原三个live marker保留，新live marker及原文不存在。独立账务仍4订单/4消费/4收入/4topup、总增额30,000,000。shell/private-access最小反馈后续恢复，但不能认定超时根因或长期稳定性已解决。

最终sample APK SHA-256 `b42a1e459571dd89442012275d5563bc3cebfbbf12da937d0174472cfa2257c3`；runner `cc9a7c9426f744166f7a6bf3ccf92487155b01dda5ae21bb5faeba865c9b5c8c`。PR #88代码版本`5eca32d`全CI成功，文档最终提交检查以GitHub为准。

实际新窗口为Not run，生产来源/解析/上传与网关规则启用均未修改。准备条件已具备，可交付保持WAITING的实际本地页，仅用户明确点击后开始一次；不要求立即付款，不把等待页当兼容性通过。私有证据位于`/private/tmp/observer-receipt-field-roles-20261018`的prepare/diagnosis子文件与目录，最终为`fresh-prepare-run5/fresh-prepare-report.json`及`final-preservation-audit-retry.json`；不提交原文或令牌。
