plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.frpc.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    // 经典 KMP + AGP 组合（AGP 8.x）：androidTarget() + 独立 android {} 块
    androidTarget()
    jvm()

    // 两端统一到 JDK 17：桌面端 jpackage 与 Android（AGP 要求 17）共用同一个工具链
    jvmToolchain(17)

    sourceSets {
        // android 与 jvm 共享 JVM API（java.net / java.io）的中间源集
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        val jvmCommonTest by creating { dependsOn(commonTest.get()) }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)
        jvmTest.get().dependsOn(jvmCommonTest)
        androidUnitTest.get().dependsOn(jvmCommonTest)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktoml.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
