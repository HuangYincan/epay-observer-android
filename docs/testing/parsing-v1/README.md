# 双通道合成解析语料 v1

关联 SPEC-009、#13/#14、PARSE-01..03。本目录的 `synthetic-cases.json` 是人工定义的合成预期，不来自真实通知；116 例，两个 provider 各 58 例。金额预期是整数分，所有候选均身份未确认。

每例包含完整字段、必要的来源／生命周期变体和预期 Candidate / Ignored / Unsupported。测试器不能根据实现重新生成 expected。金额非法、币种不符、冲突、额外文本、不同样式、禁用、恢复、修订及未知来源分别有明确原因码。

`FixtureParserCorpusTest` 逐条读取，断言结果类型、原因码或 provider／金额／币种／规则版本；不接受“没有崩溃”代替结果校验。production 的 `ProductionParserIsolationTest` 单独确认开关不能激活夹具规则。

```sh
./gradlew --no-daemon --offline --dependency-verification strict \
  :app:testFixtureDebugUnitTest :app:testProductionDebugUnitTest
```

语料是 JVM test resources，不是 APK main resources；最终 Release 还要运行 `scripts/verify_apk.py` 排除夹具包、文案、版本和旁路。AVD 的真实系统投递结果单独记录，不能用这些 JVM 结果替代。
