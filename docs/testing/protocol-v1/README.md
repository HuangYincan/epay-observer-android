# observer-events-v1-rc1 离线黄金向量

关联 #7/#25、SPEC-002。vectors.json 内所有值均为公开合成夹具；key 为 00..1f 的已知测试序列，禁止用于服务凭据。base64 指定精确 body／签名输入，避免 Markdown、中文转义、末尾换行歧义。

执行：

```sh
python3 docs/research/experiments/verify_vectors.py
python3 docs/research/experiments/indistinguishable.py
```

首条命令也可追加现有 JDK java 的路径。Python 与 Java 独立计算 hash/HMAC 并与提交的 golden 值比较；12 个篡改检查只验证密码学不相等，不调用 HTTP 接收器。contract_cases 是未来双方实现必须通过的行为测试向量，不代表本轮已验证服务端状态码。

HTTP JSON 解析、权限、撤销、并发重放、事务、Kotlin/Go 一致性和 AVD 均 Not run。固定时钟值仅用于实验，不可把这些旧请求发送给当前服务。
