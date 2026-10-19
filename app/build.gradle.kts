import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.epay.observer"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.epay.observer"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("production") { dimension = "environment" }
        create("fixture") {
            dimension = "environment"
            applicationIdSuffix = ".fixtureclient"
            versionNameSuffix = "-fixture"
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    sourceSets["androidTest"].assets.srcDir(rootProject.file("docs/testing/protocol-v1"))
    sourceSets["test"].resources.srcDir(rootProject.file("docs/testing/protocol-v1"))
    sourceSets["test"].resources.srcDir(rootProject.file("docs/testing/parsing-v1"))
    sourceSets["test"].resources.srcDir(rootProject.file("docs/testing/pairing-v1"))
    sourceSets["test"].resources.srcDir(rootProject.file("docs/testing/pilot-operator-v1"))
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        if (variant.productFlavors.any { it.second == "fixture" }) {
            variant.enable = false
        }
    }
}

// Explicit local integration input, never registered as a production resource.
// Omission restores system-only trust even when old generated files exist.
providers.gradleProperty("observerFixtureCa").orNull?.let { certificatePath ->
    val certificate = file(certificatePath)
    val output = layout.buildDirectory.dir("generated/fixtureTrust/res")
    val generate = tasks.register("generateFixtureTrust") {
        inputs.file(certificate)
        outputs.dir(output)
        doLast {
            val pem = certificate.readBytes()
            val parsed = CertificateFactory.getInstance("X.509")
                .generateCertificate(pem.inputStream()) as X509Certificate
            require(parsed.basicConstraints >= 0 && !String(pem).contains("PRIVATE KEY"))
            parsed.checkValidity()
            val directory = output.get().asFile
            directory.resolve("raw").mkdirs(); directory.resolve("xml").mkdirs()
            directory.resolve("raw/observer_fixture_ca.pem").writeBytes(pem)
            directory.resolve("xml/network_security_config.xml").writeText("""
                <network-security-config>
                    <base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system" /></trust-anchors></base-config>
                    <domain-config cleartextTrafficPermitted="false">
                        <domain includeSubdomains="false">localhost</domain>
                        <trust-anchors><certificates src="@raw/observer_fixture_ca" /></trust-anchors>
                    </domain-config>
                    <domain-config cleartextTrafficPermitted="false">
                        <domain includeSubdomains="false">ip6-localhost</domain><domain includeSubdomains="false">127.0.0.1</domain>
                        <domain includeSubdomains="false">::1</domain><domain includeSubdomains="false">[::1]</domain>
                    </domain-config>
                </network-security-config>
            """.trimIndent())
        }
    }
    android.sourceSets.maybeCreate("fixtureDebug").res.srcDir(output.get().asFile)
    tasks.matching { it.name == "preFixtureDebugBuild" }.configureEach { dependsOn(generate) }
}

dependencyLocking { lockAllConfigurations() }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.work.runtime)
    ksp(libs.room.compiler)
    androidTestImplementation(libs.room.testing)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
}
