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
// 版本号口径：tag == androidApp versionName == 这里的兜底值，三者不一致 release.yml 会直接失败。
// 本次 Windows 桌面端 4 项修复发 1.3.4，对应的 androidApp versionCode = 8（只增不减）
val appVersionRaw = System.getenv("APP_VERSION")
    ?: project.findProperty("appVersion")?.toString()
    ?: "1.3.4"
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
    // 图标：src/main/resources 下的 icon/*.png 与托盘图会被默认源集一并打进 jar
    // （由 scripts/gen-tray-icons/GenTrayIcons.java 生成，改图标请改那份 Java 再重跑）。
    // 这里额外把 icon.svg 带上，只作设计稿随包留存，运行时不读它。
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
            // Windows 那一档的 Exe 为什么还在、CI 又为什么不用它：见下面 windows { } 块的说明。
            // Linux 出 deb/AppImage；macOS 出 Dmg。
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

            // ---------------------------------------------------------------------
            // Windows 的**安装包**（setup.exe）已经不再由 jpackage 产出：
            // CI 先跑 createDistributable 出 app-image，再由 Inno Setup 编译
            // packaging/windows/Frpc-Android-Multi.iss（见 .github/workflows/release.yml）。
            //
            // 为什么换掉 jpackage 的 packageExe：
            // 1. jpackage 的 --icon **只写进 app-image 里的 launcher exe**，它自己生成的
            //    installer exe 里一个图标字节都没有 —— 实测：launcher 9/9 命中 icon.ico 的
            //    PNG 条目，installer 0/9。这就是以前 Release 里的安装包没有图标的原因。
            //    **别再回去调 jpackage 的 iconFile，那条路走不通**（见下面 iconFile 的注释）。
            // 2. 安装向导没法本地化（jpackage 只有英文），中文向导只能靠 Inno 的 Languages。
            //
            // 那 targetFormats 里的 TargetFormat.Exe 为什么还留着 —— **不是死代码，别删**：
            // 它是 jpackage 的老路径，本机装了 WiX 时 `./gradlew :desktopApp:packageExe`
            // 仍然能自测出一个 .exe（只是 CI 不再用它出包）。删掉的话，本地想对比
            // 「jpackage 出的包 vs Inno 出的包」就少了一条对照路径。
            //
            // 架构背景见 docs/design/windows-desktop-fixes.md 第 4 节。
            // ---------------------------------------------------------------------
            windows {
                menuGroup = "Frpc-Android-Multi"
                shortcut = true
                dirChooser = true
                // 只影响 **app-image 里 launcher exe** 的图标（由 scripts/gen-tray-icons 生成，
                // 16..256 共 9 档）：装完之后开始菜单 / 桌面快捷方式指向的就是这个 exe，有图标。
                // 安装包本体（setup.exe）的图标与中文向导由
                // packaging/windows/Frpc-Android-Multi.iss 的 SetupIconFile + Languages 负责，
                // jpackage 这一个字节都管不到 —— 所以这里设了也不会让安装包有图标。
                val ico = file("icon.ico")
                if (ico.exists()) iconFile.set(ico)
                // 必须与 packaging/windows/Frpc-Android-Multi.iss 里的
                // `#define MyAppUUID` **逐一字符一致（含大小写）**：
                // Inno 靠 AppId 判定「这是同一个应用」来决定覆盖升级还是另装一份，
                // 两处不一致 → 用户装了新版，程序列表里还留着旧版。
                // 注意 .iss 里有两个变体：机器级 `{{MyAppUUID}}`、用户级 `{{MyAppUUID}-user}}`，
                // 这里对应**机器级**（不带 -user 后缀）。改一处必须同步另一处。
                upgradeUuid = "9f2b6f7c-0f4a-4a3f-9d1c-8f5b1d2a6c31"
            }
            macOS {
                bundleID = "com.frpc.dash"
                dockName = "Frpc-Android-Multi"
                // 不设 iconFile：jpackage 在 macOS 上只吃 .icns，PNG 会直接失败，
                // Dock/Finder 图标暂用默认（托盘图标是另做的模板图，已适配深浅色菜单栏）
            }
            linux {
                // Linux 的 jpackage 接受 PNG。用 icon/ 目录里那张 512 —— 与 icon.png 是
                // 同一批产物（生成器各画一次），放在 icon/ 下是为了和窗口多尺寸图统一目录。
                // 注意路径：图标在 src/main/resources 下，不是 desktopApp 根目录
                val png = file("src/main/resources/icon/icon-512.png")
                if (png.exists()) iconFile.set(png)
            }
        }
    }
}
