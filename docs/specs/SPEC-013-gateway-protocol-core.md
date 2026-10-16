# SPEC-013 网关协议校验核心

状态：2026-10-16，epay-go #6 与观察器 #20/#25 的独立子集。实现位于 epay-go/pkg/observer；以 SPEC-002 的 observer-events-v1-rc1 为唯一事件契约。

纯库校验调用方提供的HTTP请求元数据、原始body、已查找设备key及接收时间：固定method/path，无query/redirect/path编码变体，单值headers，32字节独立HMAC key，原字节摘要、常量时间MAC、±300000ms窗口；之后严格解析JSON字段和数值。拒绝重复/未知字段、尾随内容、BOM/无效UTF8/孤立surrogate、浮点/指数/字符串金额、非法范围与伪造event_id作为交易号。错误只给固定code，不回显body或密钥。

返回值叫VerifiedRequest，表示签名字节和结构通过，不叫Accepted。这个库不查数据库，不验证key是否撤销/授权范围，不登记request重放、事件幂等或ACK，不匹配订单、不调用记账和旧notify路由。后续接收器仍须检查有效凭据、环境/绑定/provider/parser范围、限流，并将request/event/pending同事务提交后才能ACK。库不能单独作为支付确认依据。

调用方必须提供实际收到且已限长的body；库二次检查1..16384字节。不会读无限HTTP流或重序列化后验签。密钥只接受32字节，接口不接受商户key或旧notify_key的语义替代。已签名的body副本与hash一并返回供接收事务使用；后续handler必须避免在验证后改字节。

验收 GATE-PROTO-01：Go与已提交Python/JCA/Kotlin三组黄金向量一致，包括中文与重试；GATE-PROTO-02：签名/路由/header/时间边界；GATE-PROTO-03：严格JSON金额/Unicode/重复/未知字段反例。沿用epay-go现有CI的Go测试、race、vet、build；不在宿主安装Go，不运行服务或支付账号。CI临时数据库是既有测试作业，不是部署验收。

此子集不关闭#20/#25，也不解除#8/#10。数据库事务、撤销、同event冲突、限流、对外HTTP入口及端到端仍Not run。跨仓库版本与PR在报告中记录。
