plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.frpc.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.frpc.android"
        minSdk = 26
        targetSdk = 36
        // versionCode 只增不减：已装 1.3.3（versionCode=7）的用户若装到更小的号会
        // INSTALL_FAILED_VERSION_DOWNGRADE，必须先卸载。本次 Windows 桌面端修复发 1.3.4
        versionCode = 8
        versionName = "1.3.4"
    }

    signingConfigs {
        // CI/本地配置环境变量后自动签名；未配置时 release 构建保持未签名（可用于验证）
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
        // frpc 引擎以 libfrpc.so 形式随 APK 分发，安装时解压到 nativeLibraryDir 后执行。
        // 必须开启 legacyPackaging（extractNativeLibs=true）：APK 内压缩存储、安装时落盘，
        // 否则 lib 以未压缩页对齐形式留在 APK 内无法 execve。
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // 未引入 lifecycle-viewmodel-compose：本 App 没有 ViewModel，状态由 FrpcSupervisor /
    // 各 Repository 以 StateFlow 暴露、Compose 用 collectAsStateWithLifecycle 收集。

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    // 未引入 ui-tooling / ui-tooling-preview：代码里没有任何 @Preview，强大的调试工具
    // 也用不上，留着只会让 debug 包白白多一份依赖。以后要加预览时把这两条加回来即可。

    testImplementation(kotlin("test"))
}
