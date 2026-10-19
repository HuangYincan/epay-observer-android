# SPEC-042 收款与普通聊天的有限对照

2026-10-19；承接 #82/#84、SPEC-040/041。目标是补充实际字段证据，不付款、不确认历史订单、不增加余额。

## 范围

- 独立 androidTest listener，无网络。用户本地明确点击开始才授权；等待、刷新、prepare 不读取内容。独立永久 marker `used-receipt-chat-comparison-v1`，消费后不重置；保留全部旧 scope 的 marker/status，不覆盖失败报告。
- 最长120秒，从设备连接开始使用 monotonic 时钟。最多1条现存支付宝非摘要通知和1条 posted 非摘要通知；摘要仅检查 flags 并跳过，不读 extras。第一个符合来源的 posted child 即消费新槽，即使不是聊天也不再续采。无样本、重复投递、来源变化、断连和超时分别如实记录，不推断新付款或消息送达。
- extras 前核对固定官方包/opPkg/user/UID、版本212310/12.12.30.8000及既定证书；每次读取前复查来源和截止。只读有界 title/text/bigText、完整 flags、有限 category/style、group/channel 非空及额外文本字段非空位。channel 仅保存带本轮随机盐的私有指纹以比较是否相同，不保存原值；不读通知 key/id/tag/posttime、账号或交易详情。
- 本地页给出随机非资金校验短语。代理不代发；用户等绿色提示后自行发送并同页确认。只有 posted 正文或标题实际含短语且用户在首个RUNNING已实际观测后、最近RUNNING不超过12秒、原宿主截止内确认（不能已取消/失败），才标记受控聊天样本。首个posted使采集结束后，清理阶段仍可在上述新鲜状态内补用户反馈，不能续采；终局页面不再接收确认。反馈不是独立测量的发送时间。任意缺项为 inconclusive。callback 身份/恢复未知，nonce 对照不能证明所有聊天形态均已排除。
- 原文仅本机私有；通过实际 Kotlin 字段语法研究接口检查后，只存有限脱敏结构、理由及布尔结果并删除原文。研究接口不生成 Candidate/permit，不填 review 位、不写 outbox/访问网关。
- 所有退出路径撤销自己的 listener、强停自己的 test package、删除原文/consent、核对监听集合、旧 marker/status 和原四笔账务；失败逐项保留。不盲改其他 listener。

## 验收

| 编号 | 条件 |
| --- | --- |
| COMP-01 | 字段研究接口与 production analyze 共用语法，研究成功不能绕过 permit/review/金额/source/身份 gate |
| COMP-02 | 两槽、120秒、摘要跳过、默认停止/永久 marker、来源/断连/错误在字段前关闭 |
| COMP-03 | 本地页面等待不采集，绿灯失联关闭，单次开始，nonce+有效用户确认才标受控聊天 |
| COMP-04 | 有限脱敏、双方原文删除、监听恢复、旧记录保留及四笔审计；宿主/严格构建/lint/APK检查 |
| COMP-05 | 实际收款与受控聊天对照及可靠区分条件：待用户触发，未运行不能写通过；真实充值仍未验收 |

只见 metadata 差异不自动升级 chatBoundaryVerified；未知规则/归属继续阻塞受控真实结算。
