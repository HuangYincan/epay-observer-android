# 收款与普通聊天对照准备

2026-10-19；SPEC-042 / #82 / #84。基线main `ee5d0b8`。本批不再付款、不补确认旧单、不修改线上余额。

## 已验证

- 新test-only listener固定来源，最多1条现存child及首个posted child，120秒截止；有独立永久marker，旧listener的posted回调仍为空。
- 实际Kotlin字段语法已提为共用研究接口，研究返回值不能生成permit/Candidate/outbox。production完整review/source/观察身份/精确金额门控保留。
- 69项宿主测试通过，其中12项为本批控制/有限脱敏/异常清理/确认时效测试。JVM production共45项（44通过、1私有输入研究默认跳过），fixture共154项（153通过、1默认跳过），sample共13项通过；均为合成或软件契约测试。
- 严格离线依赖Android构建/JVM/lint成功；最终APK检查分别核验主sample、受保护的无网络/存储权限test listener、Release隔离。AndroidX test runner已有REORDER_TASKS权限，不称test APK完全无权限。
- ego-browser纯页面检查经过WAITING→绿色→反馈→停止→结束，不连接AVD；真实采集开始按钮未点击。
- 实际Kotlin私有研究调用以两条合成输入完成端到端自检：历史形态syntax-only、新消息category=msg拒绝CHAT，原文删除；这仍是合成输入，不证明支付宝规则。
- 唯一现有AVD无采样准备run2成功：两个debug APK保留数据更新、准备后强停test package，监听集合不变、五代历史snapshot marker/status与四个旧live marker保留，设备旧原文不存在，新范围unused；原四笔隔离账务仍为4/4/4/4、总增额30,000,000（不是线上余额）。

## 私有证据与失败记录

- `/private/tmp/observer-comparison-prepare-20261019-run2`：prepare.json与私有before/after集合；ready=true、content_read=false、marker_used=false。
- run1在取得控制锁时发现旧已完成控制服务器仍持锁；无设备采集，错误日志保留。核对旧会话FINISHED/cleanup_ok=true后正常停止旧服务器，再用新目录准备；没有重跑旧采样。
- 构建、宿主和APK检查日志保留在本机临时目录；初次APK检查的SDK/JBR环境缺失及test runner权限断言失败均未升级为通过，配置和断言修正后分别重验。

## 用户操作与尚缺证据

运行 `scripts/receipt_chat_control.py --lab <existing-lab> --directory <fresh-private-directory>` 后只生成WAITING控制页。页面明确本次有限字段范围。用户自行点击开始，等绿色提示后，用另一个支付宝账号发送页面随机短语，再同页点“我已发送”。第一条posted child即结束采集，不要求重复发送；清理阶段允许在最近RUNNING不超过12秒且原宿主截止内补反馈，不续采，不证明精确发送时点。

实际对照：**Not run**。即使发现nonce匹配的聊天被语法拒绝，也不能自动认定所有聊天伪装已排除；没有可靠区分依据时chatBoundaryVerified仍为false。历史通知无付款时间与订单归属资格，线上订单/New API实际增额仍未验收，生产自动运营继续关闭。
