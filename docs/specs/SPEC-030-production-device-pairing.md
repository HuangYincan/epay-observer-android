# SPEC-030 用户指定收款账户的持久设备配对

2026-10-18；Android #71 / #42，epay-go #5/#6/#21。沿用 SPEC-016/020，不改变网络协议。

用户已指定当前专用模拟器的支付宝为真实收款账户，并授权自动配对。receiver 是此指定收款账户的稳定引用，不是测试夹具、设备或通道；不读取账号页推断身份，不把此用户指定当作平台验证的账号证明。保留现有通道/New API，生产匹配关闭，正文/归属仍未准入。

## 工具边界

新增入口仅在 androidTest，显式 `observerProductionPairing=true`，目标必须为 productionDebug、AVD user 0，且未获通知访问。首次登记仅允许 alipay / notification / replace / expected generation 0，已有任何活跃绑定或 pending 时拒绝，不静默替换。不得请求事件接口或生成通知。

管理员通过正常 HTTPS 登录及 grants 接口发放5分钟单次授权。完整 scope 与用户指定的服务器私有登记记录一致后导出；Android 不接收管理员或商户凭据。短期 token 仅通过 stdin 写入应用私有 no_backup 文件，读取后立即删除；不进入参数、URL、共享存储、公开日志或仓库。完整 scope 比对通过后调用正常 PairingCoordinator.inspect/confirm；使用默认 observer.db / pairing journal / AndroidKeyStore，使真实应用可继续使用。APK 主代码和来源规则保持不变。

宿主必须显式允许持久配对、指定 serial 和新私有证据目录。pair 成功后强停并重新启动目标观察器进程，再独立 verify。verify 只读取既有凭据，不发新 grant、不换绑；可在失败诊断后单独补跑。默认应用恢复流程保留加密 pending；失败不清身份/账务或自动新建一代。

两阶段均要求：完整范围相符、活跃凭据仅指定支付宝、KeyStore key 不可导出、普通签名 health=AUTHORIZED；本地 observation/event/outbox 均0且前后不变。输出只含阶段、固定状态/计数/布尔值，不含 scope、设备标识、grant、key、签名、响应或异常文本。host finally 清理输入暂存，不删除持久授权。

## 验收

- PROV-01：strict Android 编译/lint与生产 APK 隔离；默认43项 smoke 排除该显式持久网络类，CI 不访问公网。
- PROV-02：宿主拒绝未知/重复/失败结果及错误范围；secret 仅经 stdin，verify 输入不含 token；失败保留证据。
- PROV-03：实际指定 AVD 正常 inspect/redeem/activate + KeyStore 签名健康；新进程再次健康且仅一代活跃绑定。
- PROV-04：服务器仅预期环境/receiver/授权/预算变化；alias/event/request/消费/测试订单/账本无工具创建，原隔离四笔审计不变。

配对与 health 只证明设备授权及持久性，不证明支付宝登录、通知投递、收款正文、订单归属或充值，也不证明自然后台长期可靠性。
