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

val appVersionRaw = System.getenv("APP_VERSION")
    ?: project.findProperty("appVersion")?.toString()
    ?: "1.3.3"
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

val engineDir = layout.projectDirectory.dir("engine")

tasks.processResources {
    from(engineDir) {
        include("frpc.exe", "frpc", "VERSION")
        into("engine")
    }
    from(versionResource) { into("") }
    from("icon.svg")
    dependsOn(writeVersionResource)
}

val hostIsMac = System.getProperty("os.name").lowercase().contains("mac")
afterEvaluate {
    if (!hostIsMac) {
        tasks.findByName("packageDmg")?.dependsOn("packageAppImage")
    }
}

compose.desktop {
    application {
        mainClass = "com.frpc.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Frpc-Android-Multi"
            packageVersion = appVersionRaw.split('.').let { parts ->
                (parts + List(3) { "0" }).take(3).joinToString(".")
            }
            description = "Frpc-Android-Multi - frp client (desktop)"
            copyright = "(c) 2026 Frpc-Android-Multi contributors"
            vendor = "Frpc-Android-Multi"

            windows {
                menuGroup = "Frpc-Android-Multi"
                shortcut = true
                dirChooser = true
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
