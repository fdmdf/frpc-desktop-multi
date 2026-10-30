import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
}

// 版本号：环境变量 APP_VERSION（CI） > -PappVersion（本地） > 默认值
// 同时写进 resources/VERSION，运行时读出来展示，避免界面上手写版本号又忘了改
val appVersionRaw = System.getenv("APP_VERSION")
    ?: project.findProperty("appVersion")?.toString()
    ?: "1.3.2"
val versionResource = layout.buildDirectory.file("generated/app-version/VERSION")
val writeVersionResource = tasks.register("writeVersionResource") {
    outputs.file(versionResource)
    inputs.property("version", appVersionRaw)
    doLast {
        versionResource.get().asFile.let {
            it.parentFile.mkdirs()
            it.writeText(appVersionRaw)
        }
    }
}

// 引擎随包分发：把 scripts/build-frpc.sh 产出的 frpc 可执行文件打进 resources/engine
val engineDir = layout.projectDirectory.dir("engine")

tasks.processResources {
    from(engineDir) {
        include("frpc.exe", "frpc", "VERSION")
        into("engine")
    }
    // 应用版本（packageVersion 用同一份值）
    from(versionResource) { into("") }
    // 窗口/托盘图标（与 Android 端同一份矢量渲染出的位图）
    from("icon.svg")
    dependsOn(writeVersionResource)
}

// CMP 1.12 的 packageDmg 不显式声明对 packageAppImage 的依赖，
// 同一次调用里会触发 Gradle 隐式依赖校验错误，这里补上。
// 注意：只在非 macOS 平台加这条边——macOS 上 packageAppImage 无法执行，
// 挂上去会让 packageDmg 直接失败。
val hostIsMac = System.getProperty("os.name").lowercase().contains("mac")
afterEvaluate {
    // 不能用 tasks.matching：惰性匹配会把 notarizeAppImage 也实例化，
    // 在未启用 AppImage 公证时直接抛 "AppImage cannot be notarized!"
    if (!hostIsMac) {
        tasks.findByName("packageDmg")?.dependsOn("packageAppImage")
    }
}

compose.desktop {
    application {
        mainClass = "com.frpc.desktop.MainKt"
        // Windows 默认 GBK：日志里有中文，统一按 UTF-8 输出/读写，避免控制台与导出文件乱码
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            // Windows 用 app-image（zip 内 Frpc-Android-Multi.exe 直接运行，自带 JRE）；
            // Linux 出 deb/AppImage；macOS 出 Dmg。Msi 需要 WiX，本地构建时可再加
            targetFormats(TargetFormat.Exe, TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Frpc-Android-Multi"
            // jpackage 只接受 MAJOR.MINOR.BUILD，缺位补 0，避免 "1" / "1.0" 直接失败
            packageVersion = appVersionRaw.split('.').let { parts ->
                (parts + List(3) { "0" }).take(3).joinToString(".")
            }
            // jpackage 的元信息会被写进 .cfg/注册表并按本地编码回读，
            // 非 ASCII 在 Windows(GBK) 下会抛 "Input length = 1"，这里保持纯 ASCII
            description = "Frpc-Android-Multi - frp client (desktop)"
            copyright = "(c) 2026 Frpc-Android-Multi contributors"
            vendor = "Frpc-Android-Multi"

            windows {
                menuGroup = "Frpc-Android-Multi"
                shortcut = true
                dirChooser = true
                // 安装包图标（由 docs/assets/icon.svg 生成，缺省时不指定）
                val ico = file("icon.ico")
                if (ico.exists()) iconFile.set(ico)
                upgradeUuid = "9f2b6f7c-0f4a-4a3f-9d1c-8f5b1d2a6c31"
            }
            macOS {
                bundleID = "com.frpc.dash"
                dockName = "Frpc-Android-Multi"
            }
        }
    }
}
