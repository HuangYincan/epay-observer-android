# M2 事件状态、诊断与授权检查验证

2026-10-16；Android #24、epay-go #6；SPEC-019 STATUS-01/DIAG-01..04、SPEC-020 HEALTH-01..04。证据层为**模拟器合成通知**；授权检查与ACK都不证明支付或充值。

## 行为与边界

最近20个候选仅查询展示列，不加载通知payload。明确区分历史UNBOUND、身份冲突、待发送/重试/发送中、ACK、暂停和协议拒绝；未知状态和原因使用固定文案。业务记账和商户通知保持“未查询”。

诊断只允许固定汇总字段和队列枚举；不含金额、通知内容、任何事件/设备/receiver/key标识、网关地址或凭据。用户先预览再通过ACTION_CREATE_DOCUMENT选择content URI；保存同一冻结JSON，不请求存储权限、不添加FileProvider、不复制到剪贴板或分享。Activity重建通过有界savedInstanceState恢复草稿；丢失草稿明确失败，既不重新生成报告也不修改所选文件。

独立POST /api/observer/v1/health使用KeyStore和独立签名域；网关在授权事务提交后返回authorized，仅扣共享设备限流预算，不创建event/request/订单/账本/回调。重复health仍重新验证当前权限并扣预算。Android检查执行、发送前与响应后的配对状态和凭据快照，不修改队列、hold或重试计划。

## 实际验证

- strict构建、production JVM32 / fixture JVM146（零失败/跳过）、productionRelease和fixtureDebug lint通过。
- 最终默认信任主AVD42/42及8/8进程阶段全部通过（/tmp/observer-status-final-avd.txt）；审查修复后定向4/4通过，其中实际Activity.recreate期间保留冻结草稿，返回所选测试content URI后逐字节相等。测试拦截外部选择器，文件为测试自行创建/删除的pending Downloads JSON，不宣称自动操作真实文档提供者选择界面。
- KeyStore回归验证新nonce、暂停队列字节/重试数据不变、晚到旧授权结果失效、缺key不发送，以及排队/准备期间开始配对不发送。
- Go全仓go test -race ./...及go vet ./...通过。健康检查包含实际TLS、撤销/过期/跨环境、共享预算持久化、重复检查扣费、延迟提交失败不返回成功和业务表计数不变。
- 最终干净网关schema真实HTTPS：两渠道health成功；独立发送App产生两个已ACK候选，历史两个UNBOUND保持；故意丢失首次ACK后3请求仅2事件。未知CA、重定向与错误origin拒绝。桥39.13秒PASS并清理schema，两端payment_verified=false。
- 最终显式CA fixture和Release精确APK检查通过；APK和桥摘要见[artifacts.json](status-v1/evidence/artifacts.json)。私有产物保留于/tmp/epay-observer-health-final/artifacts；凭据、私钥、原始日志不提交。

证据：[Android结果](status-v1/evidence/upload-android-result.json)、[网关结果](status-v1/evidence/upload-server-result.json)、[汇总样例](status-v1/evidence/diagnostic-sample.json)。截图均为实际AVD：测试模式、双渠道ACK/UNBOUND、诊断预览、空队列及固定检查失败；短ID/金额仅来自合成数据。网络脚本结束撤销监听权限，因此截图显示“未授权/未连接”，与已保存ACK分开呈现。

复现沿用development/pairing-local.md的固定镜像、私有CA和upload模式；本批upload预期还要求health_authorized_channels=2与health_checks=2。最终默认APK已不带observerFixtureCa重建，fixtureDebug、productionRelease及独立发送器精确检查均通过（默认system-only）；不得将测试CA带入Release。

## Standards

原1项P2已修复：Activity重建丢失草稿。49cde41复审确认修复及回归成立，无新增实质问题或阻断项。

## Spec

原3项已修复：冻结JSON生命周期、检查排队期间配对状态、预览复制/分享入口。49cde41复审确认全部解决，无新增实质问题。实际网络与截图按本报告独立记录，不把代码复审代替运行。

## 尚未完成

M3永久金额claim、匹配、事务记账、持久回调、New API实际余额一次性增加与完整故障矩阵仍待实现。生产自动匹配保持关闭；真实微信/支付宝和实体真机未验证，Goal保持active。

## 跨库版本

Android实现af0ae58、修复49cde41；网关实现1fbf5ca、证据5e77b11，PR #17。最终远端CI与合并记录在PR完成后补充。

截图：[测试模式](status-v1/evidence/synthetic-mode.png)、[持久事件](status-v1/evidence/persistent-events.png)、[空队列](status-v1/evidence/empty-queue.png)、[诊断预览](status-v1/evidence/diagnostic-preview.png)、[检查未完成](status-v1/evidence/check-unavailable.png)。均已逐张检查，无密钥/grant/地址或真实收款资料。
