plugins {
    alias(libs.plugins.android.application)
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
    sourceSets["test"].resources.srcDir(rootProject.file("docs/testing/protocol-v1"))
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

dependencyLocking { lockAllConfigurations() }

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
}
