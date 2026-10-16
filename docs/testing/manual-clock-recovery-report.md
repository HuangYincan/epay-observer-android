# 人工配对时钟漂移与恢复

2026-10-16；#59/#42，SPEC-016/020/027。用户人工界面显示微信未绑定、配对未完成，支付宝拒绝授权。保留原lab和全部历史，没有重跑充值或清数据。

## 原因和复现

22:35:51 UTC宿主时，AVD为22:28:38 UTC，慢约433秒；网关与宿主时间一致。自动时间设置仍开启。实际临时Android探针使用现有支付宝KeyStore凭据与正常HealthCheck/HTTPS，断言AUTHORIZED，得到DENIED（0.422秒，1失败），复现用户症状。服务端支付宝凭据仍active；微信第2代binding有pending credential，说明replace已经兑换但未完成激活。

配对not_before最多等待5秒，设备请求最多允许5分钟偏差；当前时差不满足两者。此前只按短期授权过期处理，漏查了设备时钟，导致用户反复重试。实际不只是token过期。

正常网络时间强制刷新返回false；提交当前宿主时间的临时network suggestion也未改变系统时间，探针仍DENIED。之后正常冷启动同一个AVD、保留userdata/KeyStore/数据库，不加载旧快照，恢复原localhost reverse；时差约0.3秒，同一支付宝凭据实际探针变为AUTHORIZED（0.998秒，1通过）。没有改凭据或放宽时间/签名/TLS规则。为何该次AVD自动时间未追上宿主尚未单独定位，不声称已修复模拟器底层时钟实现。

## 恢复微信

校准后，原微信replace grant确实已经过期。本次通过管理员正常TLS撤销明确的第2代pending绑定，然后用新replace grant调用现有PairingCoordinator.inspect/confirm，经过正常HTTPS兑换、KeyStore安装、激活及Room提交。临时恢复测试同时断言两渠道AUTHORIZED及pending日志清除，1项通过；不是改数据库/伪造响应，也不算用户手工UI轮换通过。

恢复前后跨库audit均为4条消费、4条收入、4笔成功topup，quota增加30,000,000。未创建新的收款通知或订单。支付宝绑定与凭据未替换；历史UNBOUND、旧event/body及claim没有追溯升级。

## 防复发与回归

manual_pairing_grant.py在正常TLS管理员登录后、grant创建前，以可信响应Date为基准对指定AVD时间预检；默认emulator-5554，可显式--serial，实体设备拒绝。超过3秒近似偏差、无效Date、ADB不可用停止发放并提示修复时钟。预检不设置设备时间，不扩大生产窗口，不延长grant，不打印token；原0600/scope/无代理/无redirect边界保留。

Python回归先运行旧代码：漂移/缺失Date共6个子场景失败；修正测试临时目录随机下划线与合法prefix夹具的不匹配后，最终5个顶层测试及其子场景通过（未放宽生产prefix校验）。覆盖双向±433/±4秒拒绝且未创建grant、缺失/非法Date不发放、±3秒边界/对齐正常且输出无token/文件0600、实体设备和ADB不可用拒绝。CI加入该测试命令；实际修复后另经TLS运行新版辅助脚本，结果单独记录。

命令：`python3 -m unittest discover -s scripts/prototype/tests -p 'test_*.py'`。临时诊断/恢复instrumentation在验证后移除，并重建安装不含临时类的原测试runner；没有向正式APK加入恢复接口。原始探针/恢复日志仅私有/tmp保留，公开报告不含grant、设备ID或密钥。

用户后续可直接点击“检查网关授权”复核两渠道状态。手工轮换仍需在新grant有效期内完成；没有未完成日志时才按rotate流程开始，不能用反复发token覆盖过期pending。
