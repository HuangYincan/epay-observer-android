plugins { alias(libs.plugins.android.application) }
android {
    namespace = "dev.epay.observer.fixture"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.epay.observer.fixture"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-fixture"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
dependencyLocking { lockAllConfigurations() }
