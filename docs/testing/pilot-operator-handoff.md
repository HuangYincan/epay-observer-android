# 受控试验operator交接验证

2026-10-19；SPEC-043 / #82 / epay-go #28。服务端已有结算原语缺本地操作入口，本批补默认只读、完整review显式apply及精确Android私有handoff。

共享合成向量`pilot-operator-v1/handoff.json`已由Android实际严格codec读取，两套JVM通过；不arm，不涉及设备/正文/资金。网关隔离数据库测试包含默认CLI零写入、原子规则+pilot、写入失败回滚、绑定origin、严格review、并发同范围恢复不续期、真实CLI私有文件权限/独占/symlink及同结果恢复。初始CLI子进程测试未正确选择隔离schema，保留失败并修正测试设置后通过，不放宽产品门控。

私有证据：`/private/tmp/observer-pilot-operator-20261019`。使用既有golang:1.26.9/postgres:16，独立网络/DB、无公开端口，不安装全局工具。原lab未修改；线上网关和New API均未变更，未登记实际rule/pilot。SPEC-042对照服务仍在线WAITING，实际对照未开始。

真实准入仍缺：收款/聊天对照与可靠边界、通知身份/金额归属政策、线上New API金额与处理事务契约。此前只读版本审计发现回调金额未与原充值比较、success先于充值处理、status与quota非同一事务；不能把M3独立补丁或本轮软件测试套在线上。当前不填`newapi_contract_verified=1`，不补确认旧订单，不催用户再付款。

规格审查发现恢复路径的检查/取锁之间存在并发缺口，已改为锁内复验全准入，并补设备/订单/通道/商户四类确定性交错；相关默认race与只读preflight回归通过。

本批CI/合并状态与两项独立审查在最终交付时核对，未部署、实际付款后New API增额仍Not run。
