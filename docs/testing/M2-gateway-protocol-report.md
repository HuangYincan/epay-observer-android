# #20/#25 网关协议核心（部分交付）

日期：2026-10-16，SPEC-013 / GATE-PROTO-01..03；epay-go PR #10、Issue #6，与Android #21核心对应。研究基线仍为4d855060；本批实现位于epay-go/pkg/observer，不复制后端到Android仓库。

Go纯库按固定路由/单值headers/原body HMAC/时间窗验证，再严格校验JSON重复或未知字段、整数金额、UUID和Unicode。返回VerifiedRequest，不注册路由、不发送ACK、不更新订单。body防御性复制，错误和默认诊断不回显凭据/通知。旧个人码回调和safehttp没有改动。

## 执行证据

两仓库vectors.json的SHA256均为 `5430764f20b5d8427f7751b1824d7050d4367bd52ff722bb585af1d2100a45ed`；三个golden同时覆盖实际Kotlin、API37 Android JCA和Go验证器。向量为公开合成值，不在生产使用。

宿主PATH与常见位置未找到Go，Docker无现成编译镜像；未安装Go或拉取本机编译镜像。沿用epay-go既有GitHub CI：临时runner执行Go/race/PostgreSQL既有测试、vet、build和镜像构建，没有部署应用服务。

在 `epay-go@f7c4097` / run `38834865589` 中，全部Go包race测试、vet、build、gofmt及新包fuzz通过；3秒预算实际约4秒完成23,178次输入、54个新interesting corpus，无崩溃。[CI摘要](evidence/gateway-protocol/initial-ci-excerpt.txt)。这不是长期fuzz、数据库观察器事务或对外HTTP测试。

完整CI当时被既有安全依赖阻塞：前端Vue/source-map-js、Go1.26.8/x/net0.59。独立epay-go Issue #11 / PR #12修复，保留npm audit/govulncheck。最终合并/CI结果仍须按最终head补充，不能把上述功能测试通过写成整个PR绿色。

## 仍缺少

设备授权/撤销、环境/receiver范围校验、重放与事件同事务、限流、事件冲突持久记录、真实ACK、HTTP接收入口、匹配/记账和New API回调。#15/#17/#20/#21/#25均不因本报告关闭；生产匹配准入与New API金额契约仍保留#8/#10。
