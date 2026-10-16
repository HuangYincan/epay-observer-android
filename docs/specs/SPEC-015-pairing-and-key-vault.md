# SPEC-015 配对恢复与设备密钥

2026-10-16；#17、epay-go #6；pairing-v1-rc1设计候选，Android HMAC vault子集实施基线。HTTP配对、服务端密钥加密和本地配对日志尚未实现，不能宣称完整#17通过。

## 候选配对契约

所有路由HTTPS、无query/redirect；普通日志不能记录token、resume secret、key或body。管理员在网关正常权限边界创建grant，固定environment/receiver/provider/source_class与当前binding generation，5分钟、一次消费。APK不持有管理员凭据；用户先查看网关origin和授予范围，再明确确认该次配对。

拟定POST /api/observer/v1/pairing/inspect及/redeem，Authorization: ObserverPairing <随机32字节base64url无padding token>。inspect只返回schema_version=1、grant_id、origin/environment/receiver_id/provider/source_class、expected_binding_version和expires_at_ms；服务端按token取范围，不接受客户端自报授权范围。

redeem body拟定为schema_version=1、enrollment_id(UUIDv4)、resume_secret(独立随机32字节base64url)。客户端在发请求前，将enrollment与resume secret用Android Keystore AES-GCM保护后写入配对日志；这个AES保护/日志属于后续实现，不用SharedPreferences明文代替。服务器事务锁grant/receiver，核对未过期、未撤销及预期generation，创建新device、递增binding代次、生成32字节设备HMAC key，以独立KEK加密保存；只存resume secret的摘要。

redeem响应拟定含schema_version、enrollment_id、device_id、receiver_id、binding_version、environment、provider、source_class、key_id、key_base64url、not_before_ms、expires_at_ms。密钥只在正常TLS下短暂进入进程，再导入HMAC Keystore。返回范围必须与用户确认的inspect快照一致。最终字段与严格JSON/错误向量在HTTP实施前须同步双方资产；当前不是可调用接口。

响应丢失时：在原5分钟窗口内，同grant+enrollment+resume secret返回同一device/key/代次，不再生成密钥、不延长授权；每次恢复仍核对grant撤销/到期、receiver当前代次、设备和凭据有效性，失效后不返回密钥。已消费grant遇不同enrollment或resume secret返回conflict；仅拿到旧token不足以取回已发密钥。窗口过期后不再返回密钥；本地转needs_repair，新grant经用户确认重新配对，服务器撤销/替代旧绑定。token在首次消费前被盗仍有抢先登记风险，必须短时/限速/安全展示并在管理员侧显示结果；不能宣称resume secret消除了这个风险。

## Android HMAC vault（本批）

别名为scope+key_id的长度前缀摘要，scope包含origin/environment/device/receiver/generation/provider/source_class。导入正好32字节HMAC-SHA256 key，仅SIGN/VERIFY，不设用户交互认证以支持后台签名；不强制StrongBox，不承诺模拟器硬件隔离。明文输入所有权转交vault，方法无论成功失败都清零该数组；不存入Room/偏好/日志。JCA/系统导入过程可能有短暂内部副本，输入清零不等于保证整个堆中从未出现明文；这些阻塞操作须在后台线程调用。

同别名重试：用固定域分隔挑战分别计算已有KeyStore key与输入key的HMAC，常量时间比较，相同才幂等成功；不同必须冲突，不能替换旧密钥。load返回平台SecretKey句柄，encoded应为null；别名不存在返回null，不重建、不落回软件明文key。轮换使用新key_id，旧key是否还能用由服务端授权期限决定；撤销顺序应先暂停本地outbox、再删除key。Vault本身不改变绑定或队列状态。

## 后续崩溃边界

KeyStore和Room不共享事务：必须先保存受保护pending日志→服务器兑换→幂等导入key→Room保存授权范围并激活→清理pending秘密。每一步都要可恢复；未完成本地activation不允许采集时绑定/发送。旧UNBOUND候选不能补绑。重装不沿用device_id；服务器撤销仍是权威，本地删除key不是远端撤销。

## 本批验收

VAULT-01：API37真实AndroidKeyStore导入公开合成key，actual RequestSigner与既有黄金值一致，getEncoded为空，输入数组清零。
VAULT-02：同key幂等、不同key冲突且旧key可用；scope/key_id变化隔离；删除/缺失不隐式重建；非法长度/标识拒绝且输入清零。
VAULT-03：跨真实进程重启后同alias可签相同golden，测试后仅删除自己命名的合成alias。Release来源/备份边界继续通过。

未验收：真实配对HTTPS、AES pending journal、服务端KEK/轮换、撤销联动、重装端到端、硬件KeyStore、API29/真机。#17保持开放。

## 核验资料

Android官方KeyStore文档及KeyProtection API用于确认SecretKeyEntry导入、SIGN/VERIFY与非导出句柄语义；不从模拟器通过推断硬件安全级别。具体HTTP错误码、轮换路由/签名域、严格JSON和双方配对向量在后续HTTP批次冻结。
