# Third-party components

Original project code is MIT licensed. Third-party components retain their own licenses.
No vmqApk / V免签 implementation has been copied into this project.

| Component | Version / scope | License evidence |
| --- | --- | --- |
| Gradle wrapper and generated launch scripts | 9.4.1, build only | Apache-2.0; generated from checksum-verified official distribution; original script headers retained; [NOTICE](third_party/Gradle-NOTICE.txt) |
| Android Gradle Plugin | 9.2.1, build only | Apache-2.0, published POM |
| Kotlin stdlib | 2.2.10, app runtime | Apache-2.0, published POM and v2.2.10 upstream [copyright](third_party/Kotlin-COPYRIGHT.txt) / [notice](third_party/Kotlin-NOTICE.txt) |
| JetBrains annotations | 23.0.0, runtime dependency graph (R8 may strip it) | Apache-2.0, published POM |
| AndroidX Room runtime/common | 2.8.5, app runtime | Apache-2.0; Google Maven POM/module metadata, publisher checksum recorded in identity evidence |
| AndroidX SQLite | 2.6.2, app runtime | Apache-2.0; Google Maven POM |
| AndroidX collection / arch-core / annotations | 1.5.0 / 2.2.0 / 1.9.1 (experimental 1.5.0), runtime graph | Apache-2.0; Google Maven POMs |
| Kotlin coroutines | 1.8.1, app runtime through Room | Apache-2.0; Maven Central POM |
| JSpecify annotations | 1.0.0, runtime graph | Apache-2.0; Maven Central POM |
| Room compiler/testing + KSP | 2.8.5 + 2.3.6, build/test only | Apache-2.0; Google Maven / Maven Central POMs; not shipped as runtime compiler |
| JUnit | 4.13.2, tests only | EPL-1.0, published POM |
| JSON-java | 20250517, JVM tests only | Public Domain, published POM |
| AndroidX Test runner / JUnit extension | 1.7.0 / 1.3.0, instrumentation only | Apache-2.0, published POMs |

The [Apache-2.0 license text](third_party/Apache-2.0.txt) is included for the Apache components.
The exact resolved component graph and hashes are recorded in app/gradle.lockfile and
gradle/verification-metadata.xml. Test/build tools have further transitive dependencies;
their POM/license metadata must remain available in the dependency cache. This table is
not a claim that every build-only transitive license was manually audited.

No test library is added to the application runtime configurations. APK publication is
outside #9/#5: before distribution, package the applicable runtime licenses/notices with
the artifact and update the inventory for any new dependencies.
