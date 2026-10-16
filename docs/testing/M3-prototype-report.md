# M3 模拟器合成通知自动充值验收

2026-10-16；Android #10/#17/#18/#22/#26–32/#34/#36，epay-go #6–9；SPEC-016–026。**独立发送App→Android真实通知/持久队列→实际HTTPS→网关匹配/收入→持久TLS回调→New API额度已双渠道通过。** 证据仅属于API37模拟器合成通知，真实微信/支付宝、实体设备、生产安全及APK发布未验收。

## 干净复现与余额

使用`start_lab/configure_lab/recharge_avd/callback_faults/audit_recharge`从新目录、新PostgreSQL数据库运行；没有借用旧claim或手工修改成功状态。当前私有lab `/tmp/epay-observer-demo-final`。普通New API充值入口返回的参数经普通网关submit创建订单；通知没有订单号、平台流水或隐藏选单元数据。

| 阶段 | 实际New API quota | 消费/收入/成功充值 |
| --- | --- | --- |
| 普通setup初值 | 100,000,000 | 0 / 0 / 0 |
| 微信/支付宝各10元 | 110,000,000 | 2 / 2 / 2 |
| 双渠道各20元回调恢复 | 130,000,000 | 4 / 4 / 4 |
| 重复、篡改、并发及暂停40元通知 | 130,000,000 | 4 / 4 / 4 |

额度按New API原topup.Amount×500000计算，不能把金额元直接当quota。逐笔审计验证event、decision、consumption、order、income和topup的关联、唯一性、provider及payment_method、金额/费用与总额度；不是仅比较总数。[脱敏证据](recharge-v1/evidence/audit-result.json)及[首次闭环](recharge-v1/evidence/recharge-result.json)。

## 故障矩阵与证据层次

| 场景 | 证据层及结果 |
| --- | --- |
| EMU-01/02双渠道 | 同一AVD与空数据lab，真实网络及真实两端数据库，各一次充值 |
| EMU-03负文案、EMU-04同键更新/重投 | 116例语料及实际AVD身份套件；拒绝或冻结，见M1解析/身份报告 |
| 新key同内容 | 实际AVD通知、完整lab：duplicate_suspected，零收入/额度 |
| EMU-05离线50条 | Room/AVD持久队列验收；后台双渠道真实网络恢复见M2-background-report。不是50笔成功充值 |
| EMU-06提交后ACK丢失/重放 | 实际Android HTTPS上传报告；原event/body不变、新封套，网关一次持久接收；已credited重复ACK由Go真实TLS测试 |
| EMU-07同事件20并发 | PostgreSQL匹配测试20个并发处理同event，仅一条消费/收入；另20个实际TLS回调并发均success但New API零追加额度。是分层验证，不声称Android同时发20个请求 |
| EMU-08同额冲突/跨账户渠道 | PostgreSQL永久claim与匹配矩阵，0/1/2候选、跨alias/merchant竞争、receiver/provider隔离；冲突不串单 |
| EMU-09迟到/过期/金额复用 | PostgreSQL明确边界：10分钟窗口、120秒离线、2秒未来偏差、旧事件/同毫秒后建单、已付/退款/换绑定/归档不释放永久claim |
| EMU-10进程/设备重启、强停、Doze、撤权 | M2实际AVD报告与最终43主测试+8进程阶段；未补回系统未投递通知 |
| EMU-11撤销/越权/篡改/旧封套 | 实际配对/上传TLS、PostgreSQL授权与防重放测试；KeyStore不备份，旧UNBOUND不追溯绑定 |
| 账务关键崩溃 | 每写入点/commit回滚，真实Go子进程commit前后os.Exit恢复，见M3-matching-credit-report |
| EMU-12首次503 | 完整lab实际网关SIGKILL后普通持久重试，2次投递、一笔充值 |
| EMU-12已充值响应丢失 | TLS代理真实转发New API并丢回包；网关和New API均SIGKILL重启，重试不重复充值 |
| 当前商户key轮换 | 普通API换key，旧New API设置失败；普通设置更新后持久重试成功，零追加额度 |
| 错签/合法签名错金额/未重签篡改/缺金额 | 实际TLS handler返回fail，额度不变；三数据库补丁验证见回调基础报告 |
| EMU-13/14测试隔离 | 默认Go拒绝synthetic matcher/本地callback，production来源拒绝；Release/default/独立发送器APK精确检查通过 |
| ADMIN-01..04 | 管理权限/TLS/事务/并发/不可变审计；现有lab升级两次启动保留四笔收入；暂停40元通知ACK但终局待核对，恢复/重启不升级 |

[实际回调故障](recharge-v1/evidence/callback-fault-results.json)、[管理暂停/审计](recharge-v1/evidence/admin-acceptance-result.json)。测试时没有加速真实callback退避，没有修改New API成功状态或quota。公开证据为字段白名单，完整日志/私钥/设备标识留在私有目录。

## 失败记录与修复

第一次闭环的Android恒定输出uncertain，被网关正确判为needs_review/unqualified_evidence，收入与额度均为0。原数据库保留在旧lab，不升级历史事件、不重写body。SPEC-025先定义隔离资格，再在fixture首次持久身份边界批准新鲜、已绑定、无恢复/缺口的synthetic证据；production继续uncertain并拒绝distinct编码。Room回归先失败再通过，双方共享实际Android编码向量增至5组。

审查发现部分启动清理及审计关联不足，已修复且实际重复清理验证。SPEC-026查询缺订单状态已修复，候选与已消费订单字段独立；已credited后退款状态可查而历史收入不改。默认AVD恢复验证曾因完整lab遗留的已配对状态与主套件“未绑定”前置条件冲突而失败；仅在归档后清空专用debug客户端重跑，43+8通过，服务端历史保持。测试聚合初版把14个无测试文件package记为skip，现按原JSON分列package与真实测试事件。没有通过放宽金额/身份/TLS/签名断言通过测试。

## 检查、版本与产物

- Android最终strict构建、production/fixture JVM、lint通过；默认AVD43/43及8/8进程阶段通过。显式CA APK完成真实联调后归档，默认system-only构建再次生成并检查。5组共享签名向量覆盖实际Android编码。
- 网关最终默认全仓race211项、observersynthetic322项，0测试失败/跳过；每构建另有14个无测试文件package，JSON单独列出，不能与测试skip混计；两构建vet通过，20并发匹配包括在内。默认与专用构建完全分开。
- New API基线633b4c5、本地42c3424d补丁：SQLite3.50.4/PG16.15/MySQL8.4.11签名GET/POST各14项，model Epay51项、完整model/controller race及vet通过，详见[M3回调基础](M3-callback-foundation-report.md)。
- Android功能8ff7f40、脚本79f6cf4/812517f；网关最终9a96f72；New API42c3424d。Go1.26.9、PG16.15、Redis7、Bun1.4.0镜像摘要由脚本及产物清单固定；不依据“最新版”。
- 私有可保留交付在`build/prototype-delivery/`，备份位于当前lab `artifacts/`；包含APK、二进制、补丁、AGPL许可和哈希。[公共产物摘要](recharge-v1/evidence/artifacts.json)。没有APK上传到GitHub或正式签名发布。
- 构建/启动/演示/清理见[操作步骤](../operations/prototype-demo.md)。真实实验日志 `/tmp/observer-clean-*`、`/tmp/observer-admin-*`、`/tmp/observer-final-*`；禁止原样公开可能含认证参数的日志。

## 审查与边界

Standards：已修复清理/跨库审计和历史评审发现，最终无实质遗留。Spec：已修复synthetic资格缺口与订单追踪状态缺口，最终无实质遗留。文档/脚本最终复审和CI关联在本报告的交付记录补充。

真实App签名/通知样本、真实付款归属、真机后台行为未验证；生产自动匹配关闭，#8及M4真实兼容性任务继续阻塞。永久金额占用是保守的合成策略，不能无限复用金额运营。当前没有人工到账旁路；待核对需独立真实账单，不能用同一通知证明自身。KEK重封装及生产发布运维不在该隔离原型交付内。
