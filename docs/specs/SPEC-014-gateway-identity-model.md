# SPEC-014 网关身份与证据存储

2026-10-16；#15、epay-go #6；实施基线。只交付模型、迁移及证据持久化原语，不开放HTTP接收/配对，不匹配订单或记账。

## 身份关系

- Environment：独立信任域；source_class固定为synthetic或notification，默认disabled。服务启动将来仍须固定允许环境，数据库中有行不代表可启用生产。
- Receiver：稳定 `(environment,provider,account_ref)`。account_ref是运营登记的非秘密、不透明账户代号，不保存真实手机号/账号；数据库只能防相同代号重复，不能证明两个不同代号实际不是同一账户。更换真实收款账号应新建receiver；相同账号不得通过新代号逃避历史保护。
- Channel alias：现有epay channel/provider到receiver的唯一引用。可有多个channel别名指向一个receiver；跨环境不能重复占用同一channel/provider。receiver不归属于手机或单个merchant，merchant仍由既有订单确定。别名映射创建后不改指向。
- Device：安装实例，只属于一个environment。重装需要新实例，旧事件不能修改device_id搬家。
- Binding：保留receiver/device/provider/generation历史；同receiver最多一条active，同device/provider最多一条active。更换设备先终止旧授权再以更大generation创建新授权，不能重新激活旧代次。
- Credential：key_id全局唯一，引用完整binding范围。只允许AES-256-GCM密文槽位（独立KEK ID、nonce+ciphertext+tag共60字节），不能存明文设备key，更不能引用商户key/notify_key。当前表仅保存密文元数据，真正加解密/发放由#17后续实现。默认pending，active/revoked状态及有效时间显式保存；身份与密文不可原位换写，轮换新key_id。

## 事件与冲突

observer_events唯一 `(environment,device_id,event_id)`；保存SPEC-002允许字段、精确body、SHA256、首次接收时间与首次key_id/request_id、pending/needs_review状态。绑定FK跨完整范围，source_class与environment一致。provider_transaction_id缺失为NULL，存在也只是证据；不填入旧订单api_trade_no，不建未经可信认证的“平台流水去重”捷径。

body和所有身份/金额/时间字段冻结，数据库trigger拒绝修改或删除；只允许处理状态变化。body hash在数据库用内建SHA256校验。冲突表以原事件+新body hash唯一，保存首次冲突请求/凭据/时间，保留原事件；同body返回duplicate，不重写首次时间。冲突只存摘要，不保存第二份通知/恶意原文。

RecordEvidence是签名校验后的存储原语：重新严格解析verified原字节，与所选credential的不可变范围核对，事务插入事件或冲突记录。它不等于接收API；没有凭据有效性/撤销检查、限流、请求重放登记或ACK。#20必须在同一接收事务内补齐这些步骤，不能将此原语直接挂到HTTP路由。接收冲突不回滚已保存的冲突证据；数据库错误则全部回滚，不返回成功。自建事务显式READ COMMITTED；外层接收事务调用时仅使用savepoint，最终持久性以外层commit为准，不能提前ACK。

## 迁移与兼容

在现有迁移后新增版本化SQL，独立事务+schema级advisory锁，保存脚本SHA256。重复运行校验版本/摘要；未知版本、摘要漂移或表名碰撞失败，不能删表重建。当前旧表列及数据不改写；外部FK引用channels.id，并仅对已有observer别名的channel禁止改为非epay插件；历史订单、余额、账本、旧回调逻辑不变。新表默认空且环境disabled，不代表观察器已上线。既有AutoMigrate仍按原机制执行，不把观察器DDL事务表述为整个旧迁移链路原子。

不提供删除证据的down迁移；代码回退可留下附加表，不自动删除业务历史。版本化SQL归网关仓库。SQL参数可能含敏感值，存储原语关闭SQL参数日志，只返回固定错误类别。

## 验收

MODEL-01：在真实PostgreSQL上迁移两次、并发迁移；旧订单/merchant余额/api_trade_no/channel配置保持原值；未知版本/改脚本/碰撞失败且DDL原子回滚。
MODEL-02：receiver代号唯一、通道别名/FK跨环境跨provider拒绝、单活跃receiver与device/provider约束；保留绑定历史，禁止身份/密文原位改写和旧代次复活。
MODEL-03：同事件并发只生成一个证据；换request保持duplicate；同event不同body保留原记录并持久化冲突；真实deferred trigger让commit失败时无部分证据/冲突。
MODEL-04：金额/UUID/时间/body预算/原字节hash约束、不可变事件和可选真实交易号；无订单入账或商户回调副作用。缺少EPAY_TEST_DATABASE_DSN时测试skip，不记Passed。

真实资金自动匹配仍受#8/#10约束；本规格不决定真实账号的识别或运营风险政策。
