# 已标注扫码收款样本的字段核验

2026-10-18；#82/#84，SPEC-038 ROLE-01..04。用户已明确确认SPEC-037 classification child对应真实扫码收款；用户标签私有单独保存，不改写旧报告为平台验签，不对历史事件/订单补确认。

## 实现与验证

新增固定field-roles一次性范围，最多2条现存通知；仅沿用三项文本与有限类型元数据，不新增账号/key/channel或group字符串读取。Android固定scope表由同一prefix派生consent/marker，拒绝多个并存consent，并检查前面全部三代COMPLETE/2与marker/raw状态。宿主同序检查，结束复核原记录。

本机对完整标题语法和正的规范金额作布尔判断，不输出实际金额；括号只逐字匹配有限公共词，或标注掩码姓名字形，其他保持unknown。正文仅公共词和占位符。公共词是研究探测词，并非已经验证支付宝支持的支付方式清单；掩码字形不等于实名/账号核验。所有结果仍payment_eligibility=false，没有生产解析/上传/确认改动。

- ROLE-01：原7项状态/超时/脱敏合成回归与新增4项字段角色/隐私/拒绝用例通过。首次新增测试因合成姓名中含白名单公共词而期望不一致；改用不含该词的合成姓名，未扩大输出范围来通过测试。
- ROLE-02：严格依赖、离线AndroidTest构建/sample lint通过；最终manifest/dex/固定scope字符串/posted空方法/无网络/备份排除检查通过。APK SHA-256 `019358c8b58ae1abb5dacf6da7c35aed2477c52af55d69ee6f08960a815270d8`。
- ROLE-03 run1：准备测试进程时ADB超时；尚未准备consent或读取正文，field-roles范围未消费。新APK实际安装完成；撤权、强停、监听与三代旧记录复核成功。失败报告保留。
- ROLE-03 run2：精确hash相同跳过重装；实际COMPLETE/0、templates=[]。当前列表没有匹配通知，未获得新增字段证据，不能推断通知何时/为何撤下，也不能判通知可达性或支付兼容性通过。field-roles范围已永久消费，不重置或自动重开。
- 清理与审计：run2宿主/设备原文及临时文件删除、撤权/强停、监听列表恢复，三代旧marker/COMPLETE状态保留；前后独立只读审计仍4订单/4消费/4收入/4topup、总增额30,000,000。
- ROLE-04：真实新增字段匹配Not exercised，完整解析/聊天伪装边界仍Blocked；未登记线上规则/试验、未部署、未付款/充值。已询问用户括号属于支付方式还是姓名，仅请求类型或公共文案，不请求具体姓名/金额；回答未取得前不猜测。

私有证据`/private/tmp/observer-active-snapshot-20261018-field-roles-run1`、`run2`与`/private/tmp/observer-receipt-field-roles-20261018/apk-check.json`。先前真实扫码样本的用户标签位于原classification私有目录的独立user-label.json，原文已删除。旧生命周期、普通消息回调与合成充值验收未重跑。#82/#8/#33/#42及后台#81保持各自证据边界。
