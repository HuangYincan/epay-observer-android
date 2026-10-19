# 有界真实通知联调接线

2026-10-19；#82 / epay-go #26（承接#24），SPEC-041 WIRE-01..05。用户要求补齐真实通知识别、订单关联和网关结算；本批实现debug默认停止的受控入口及双方准入检查，不重复历史生命周期或采样窗口，不补确认已付款旧单。

## 实现与边界

productionDebug新增固定官方package/opPkg、同user、posting UID、212310版本/证书校验；单次120秒/2条在extras前限额。读取与停止串行，读取前再次检查时钟/截止/断连许可；每个permit只能读取一次并冻结字段摘要。恢复列表不读取正文。回调入口冻结来源、时间、binding与核验许可，沿用Room身份/不可变outbox及既有签名，不发送正文、账号、通知key或订单提示；出站始终notification。会话停止不追溯解释历史身份。

标题只全串解析规范正金额，括号仍为不透明字段；正文只比较有限词表形成的精确脱敏骨架，摘要、聊天/未知样式、附加文本与未知模板拒绝。当前语法是脱敏研究结果的代码表达，不是已核验支付规则；聊天伪装、正文数字角色、通知身份和金额归属风险仍须独立核验。五项review事实缺一项即不能启动，工具不生成/补填这些事实。Release与fixture编译为不能arm；最终Release排除本轮控制输入、marker及parser标记。

受信本机instrumentation控制面提供prepareOnly与runReviewedOnce。准备不消费marker、不启动采集、不改变监听权限；实际启动须私有完整handoff、匹配当前有效配对与已连接监听，并永久消费used-notification-pilot-v1。正式APK无额外Activity/广播/URL启动入口。真实启动未执行；instrumentation会影响进程优先级，不能证明自然后台可靠性。

网关独立只读CLI已实现迁移/环境/授权/规则/订单/通道/商户/历史同额/证据/alias/pilot/暂停检查；零业务写入。实际Arm/接收仍重新核验。当前现网运行旧代码，CLI实查ready=false/schema_not_ready，未迁移或启用规则，临时远端工具已删除。详见epay-go docs/observer/notification-preflight.md。

## 验证状态

- WIRE-01/02：production JVM合成测试及fixture隔离通过；规范金额与notification wire、每项review缺失、2条/120秒/服务端提前截止、时钟异常、停止、extras前再次检查与单次读取、未知字段/聊天/摘要/样式、跨binding/来源/金额、严格handoff和历史拒绝均覆盖。没有伪造系统支付宝通知，也没有把合成字段当真实兼容性。
- WIRE-03：网关独立PostgreSQL race测试含13类阻塞、只读隔离级别及零业务写入通过；相关vet/linux-amd64 CLI构建通过。实际现网缺schema结果符合预期，不代表链路可用。
- WIRE-04：严格离线构建/JVM/lint/最终APK检查首轮通过；最终修复后productionDebug 43项、fixtureDebug 152项JVM通过，productionDebug/Release与productionDebug AndroidTest严格离线构建、两变体lint及最终APK检查通过。Release成品中本轮debug控制marker/parser均不存在。Release unit-test task在当前AGP配置不存在，首次命令失败保留；不能声称执行了该任务，Release边界依赖实际成品检查。
- WIRE-05：完整真实模板/聊天排除/通知身份与风险政策、新订单真实付款及New API前后余额仍Not run/Blocked。旧SPEC-039零回调和SPEC-040历史通知/清理失败不改写；既有真实订单未确认、未回调、未改余额，本批不要求用户重复付款。

私有构建日志observer-pilot-*及现网只读结果保存在/private/tmp，新证据目录独立，真实订单号不入本报告。后台#81、#8/#33/#42保持开放。

首次独立审查：Spec轴无阻塞；Standards轴发现P2，停止/awaitIdle/clear可能插在回调读取后、写入预览前。已增加同回调主线程的屏障，再等待持久化并在该线程清空预览；意外回调线程在extras前拒绝。新增可控交错回归暂停在途回调，确认清理等待它完成且最终无预览，屏障失败不报清理成功。另补字段读取抛错仍消费permit的单次性回归。修复后的受影响JVM/构建/lint通过，最终复审与模拟器结果另记。词表两端重复维护属于非阻塞维护建议，本批不扩大范围重构。

## 实际新验证与交付

仅对现有emulator-5554安装保留数据的本轮productionDebug/runner更新，未更动支付宝或sample/probe数据。新NotificationPilotPersistenceTest在独立内存DB完成合成候选→实际Room身份/outbox，验证历史恢复记录不升级、重复只一条及修订冻结；单独prepareOnly确认固定安装来源匹配，ready=false、input不存在、review未就绪、scope marker未消费，监听未授权/未连接。两测试分别OK (1 test)，未执行runReviewedOnce、未读取真实正文或新通知key。监听完整集合在安装前与结束后均私有落盘且相等。原隔离账务复核仍4订单/4消费/4收入/4topup，总增额30,000,000；不能据此评价实际线上余额。

私有新证据/private/tmp/observer-pilot-avd-20261019-w7g4qgie与/private/tmp/observer-pilot-readiness-20261019-1l6y97pz。Android APK SHA-256 ec018c761bf0f8dd14bacd55a961c11b3e33c304f80b1dec735163d64ddb3be3，runner a8bc4d562d714c41146b31f290f24bbfb3da23023eace119b71bd014d48e2568。现场准备不采样，因此没有消耗/重置任何历史采样授权或marker。

独立标准增量复审确认P2解除、无剩余阻塞；Spec复审及最终CI状态以PR记录为准。生产仍运行旧镜像，未迁移/登记规则或pilot。安全启用还需真实模板/聊天排除/通知身份和明确金额归属政策，不能把研究语法的五个review位人为填写true来制造可用。
