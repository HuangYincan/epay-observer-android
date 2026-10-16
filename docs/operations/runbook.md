# 运行与故障处置规划

状态：隔离原型已演练。构建、实际配对与双渠道演示见[原型手册](prototype-demo.md)，运行证据见[最终报告](../testing/M3-prototype-report.md)。生产部署、真实账号与KEK重封装仍未验收。

## 应展示的健康信息

设备绑定与撤销状态、权限是否存在、监听服务是否连接、最近观察时间、队列积压、最近 ACK、解析规则版本、网关处理状态、商户通知结果。心跳不证明收款 App 正在投递通知，也不证明账户绑定仍正确。

## 故障定位顺序

1. 没有通知观察：检查权限、服务、支付 App 是否真实产生通知、系统／OEM 限制；不先改金额解析正则。
2. 有观察但 unsupported：检查脱敏诊断与规则版本，新增样本及回归任务；不在线放宽所有通知匹配。
3. 队列不上报：区分网络、TLS、时间、撤销、限流；不删除队列或改 event_id 逃避冲突。
4. 事件 accepted 未入账：查看匹配状态与候选，不把重新发送事件当作选新订单的方法。
5. 网关已入账但 New API 未增额：查看商户通知重试与验签／订单状态；不重新记账。

## 自动确认暂停

检测到账号变更、持续未知规则、时钟异常、异常重复或未解释余额变化时，使用`admin_trace.py <lab> <receiver> --pause --reason incident`按receiver暂停自动确认并保留事件接收/证据（SPEC-026）。恢复需验证原因及记录操作，不能简单清空事件表。

## 数据与升级

凭据撤销、轮换、设备重装、规则升级、队列 schema 升级和 APK 签名保持连续性均要有专门演练。历史事件不得用新规则静默重解释并自动入账。日志保留、导出脱敏和清理参数待发布准备阶段定稿。

## 凭据、账号变化和升级

设备丢失先用专用管理员HTTPS `POST /api/admin/observer/bindings/revoke`撤销绑定（schema_version、receiver_id、binding_version），再按SPEC-016发replace grant。轮换使用rotate grant和应用配对界面，pending恢复由KeyStore/Room处理；旧封套/旧绑定不再授权。grant路径/字段见SPEC-016，不使用商户API Key代替设备凭据。重装产生新设备身份，历史UNBOUND/旧事件不得补绑。真实支付App账号变化目前不能自动可靠检测，应暂停receiver并核对独立真实账单；本原型没有真实账号或人工到账入口。

回调失败先查notify_status/count及New API配置。当前商户API Key轮换必须同步New API EpayKey；worker按持久退避重试，不再消费事件或生成收入。已成功但丢响应可安全重试同一原订单。HTTP/return_url成功不是入账证明，必须检查topup和quota。

升级先保留私有备份与逐笔audit，停止该lab网关后更换已验证二进制再启动。版本化迁移保持历史约束；本批实际从schema5升级6并再次启动，四笔账务与暂停审计均保留。回退代码不能删除证据表/保护trigger；不得清库、清claim或改事件body解决异常。数据库恢复/KEK丢失的生产演练尚未完成，真实运营保持阻塞。
