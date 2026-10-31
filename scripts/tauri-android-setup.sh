#!/usr/bin/env bash
# 一键重建 Tauri Android 工程（gen/ 不入库，用此脚本从零恢复）：
#   1. tauri android init 生成工程
#   2. 注入阿里云 Maven 镜像（国内直连 google() 不通）
#   3. gradle 补丁：NDK 版本（从 NDK_HOME 推导）/ 压缩打包 / 引擎声明式源目录 /
#      BuildTask 的 TAURI_SKIP_RUST_BUILD 守卫（Windows 非管理员符号链接限制）
# 前置：ANDROID_HOME / NDK_HOME 环境变量，rustup target aarch64-linux-android。
# 用法：bash scripts/tauri-android-setup.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/tauri/app"
GEN="$APP/src-tauri/gen/android"
OLD_JNI="$ROOT/tauri/engines/android/jniLibs"

: "${ANDROID_HOME:?请设置 ANDROID_HOME}"
export NDK_HOME="${NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"

echo "==> tauri android init"
cd "$APP" && ui/node_modules/.bin/tauri android init

echo "==> gradle 工程补丁"
python - "$GEN" "$NDK_HOME" "$APP/ui/node_modules/@tauri-apps/cli/tauri.js" <<'PYEOF'
import os
import re
import shutil
import sys

gen, ndk_home, cli_js = sys.argv[1], sys.argv[2], sys.argv[3]
ndk_ver = os.path.basename(ndk_home.rstrip("/\\")) or "27.2.12479018"

# ---- 1) app/build.gradle.kts：NDK 版本 / 压缩打包 / 引擎声明式源目录 ----
p = os.path.join(gen, "app", "build.gradle.kts")
s = open(p, encoding="utf-8").read()
if "ndkVersion" not in s:
    s = s.replace(
        "        minSdk = 24",
        '        ndkVersion = "%s"\n        minSdk = 24' % ndk_ver,
        1,
    )
if "abiFilters" not in s:
    # 只打 arm64：Rust 侧本次只编 aarch64，留着 x86_64 引擎会让 APK 多出约 7MB，
    # 且 x86_64 设备上只有引擎、没有 libfrpc_app_lib.so，装上运行即崩。
    s = s.replace(
        "        minSdk = 24",
        '        minSdk = 24\n        ndk { abiFilters += listOf("arm64-v8a") }',
        1,
    )
if "useLegacyPackaging" not in s:
    s = re.sub(
        r"            packaging \{\n(                jniLibs\.keepDebugSymbols\.add\([^)]*\)\n)+",
        "            packaging {\n"
        "                // native 库压缩存储（17.4MB 引擎 -> ~6.2MB，与旧版 androidApp 一致）\n"
        "                jniLibs.useLegacyPackaging = true\n",
        s,
        count=1,
    )
if "jniLibs.srcDir" not in s:
    srcdir_block = (
        "    sourceSets {\n"
        '        getByName("main") {\n'
        "            // frpc 引擎：仓库根下 tauri/engines/android/jniLibs（rootDir 上溯 5 级）\n"
        "            jniLibs.srcDir(\n"
        "                File(\n"
        "                    rootDir.parentFile.parentFile.parentFile.parentFile.parentFile,\n"
        '                    "tauri/engines/android/jniLibs",\n'
        "                ),\n"
        "            )\n"
        "        }\n"
        "    }\n\n    defaultConfig {"
    )
    s = s.replace("    defaultConfig {", srcdir_block, 1)
open(p, "w", encoding="utf-8", newline="").write(s)
print("app/build.gradle.kts patched (ndk %s)" % ndk_ver)

# ---- 2) buildSrc 镜像 ----
p2 = os.path.join(gen, "buildSrc", "build.gradle.kts")
s2 = open(p2, encoding="utf-8").read()
mirror = (
    '    maven("https://maven.aliyun.com/repository/google")\n'
    '    maven("https://maven.aliyun.com/repository/public")\n'
    '    maven("https://maven.aliyun.com/repository/gradle-plugin")\n'
)
if "aliyun" not in s2:
    s2 = s2.replace(
        "repositories {\n    google()\n    mavenCentral()\n}",
        "repositories {\n" + mirror + "    google()\n    mavenCentral()\n}",
        1,
    )
    open(p2, "w", encoding="utf-8", newline="").write(s2)
print("buildSrc mirrors injected")

# ---- 3) 根 build.gradle.kts 镜像 ----
p3 = os.path.join(gen, "build.gradle.kts")
s3 = open(p3, encoding="utf-8").read()
if "aliyun" not in s3:
    m6 = mirror.replace("    maven(", "        maven(")
    s3 = s3.replace(
        "buildscript {\n    repositories {\n        google()\n        mavenCentral()\n    }",
        "buildscript {\n    repositories {\n" + m6 + "        google()\n        mavenCentral()\n    }",
        1,
    )
    s3 = s3.replace(
        "allprojects {\n    repositories {\n        google()\n        mavenCentral()\n    }",
        "allprojects {\n    repositories {\n" + m6 + "        google()\n        mavenCentral()\n    }",
        1,
    )
    open(p3, "w", encoding="utf-8", newline="").write(s3)
print("root mirrors injected")

# ---- 4) BuildTask：TAURI_SKIP_RUST_BUILD 守卫 ----
p4 = os.path.join(
    gen, "buildSrc", "src", "main", "java",
    "io", "github", "fdmdf", "frpc", "kotlin", "BuildTask.kt",
)
s4 = open(p4, encoding="utf-8").read()
anchor = "    @TaskAction\n    fun assemble() {"
guard = (
    "    @TaskAction\n"
    "    fun assemble() {\n"
    "        // 预构建模式：cargo 产物已由外部放置到 jniLibs，跳过 tauri CLI\n"
    "        if (System.getenv(\"TAURI_SKIP_RUST_BUILD\") != null) {\n"
    "            println(\"BuildTask: skipped (TAURI_SKIP_RUST_BUILD set)\")\n"
    "            return\n"
    "        }"
)
orig = s4
if "TAURI_SKIP_RUST_BUILD" not in s4:
    assert anchor in s4, "BuildTask anchor not found"
    s4 = s4.replace(anchor, guard, 1)

# 生成的 args[0] 是裸 "tauri"，gradle 会以 cwd=src-tauri 执行 `node tauri ...`，
# node 把它当脚本路径解析 -> Cannot find module '<src-tauri>/tauri'。
# 指到真实的 CLI 入口（tauri.js）即可让 gradle 逐 ABI 调回 CLI 做 cargo 构建。
cli_js_kotlin = cli_js.replace("\\", "/")
old_args = 'val args = listOf("tauri", "android", "android-studio-script");'
new_args = 'val args = listOf("%s", "android", "android-studio-script");' % cli_js_kotlin
if old_args in s4:
    s4 = s4.replace(old_args, new_args, 1)
    print("BuildTask cli entry -> %s" % cli_js_kotlin)
elif cli_js_kotlin not in s4:
    print("WARN: BuildTask args line not matched, CLI 入口未改写")

if s4 != orig:
    open(p4, "w", encoding="utf-8", newline="").write(s4)
print("BuildTask guarded")

# ---- 5) 自适应图标：tauri android init 只复制 mipmap-{mdpi..xxxhdpi} 的 PNG，
#         mipmap-anydpi-v26（adaptive-icon）与 values/ic_launcher_background.xml 不会复制，
#         结果 Android 8+ 用满幅 ic_launcher.png 被启动器遮罩裁掉一圈。这里手动补齐。
icons_android = os.path.join(os.path.dirname(os.path.dirname(gen)), "icons", "android")
res_dir = os.path.join(gen, "app", "src", "main", "res")
copied = 0
for folder in os.listdir(icons_android):
    src = os.path.join(icons_android, folder)
    if not os.path.isdir(src):
        continue
    dst = os.path.join(res_dir, folder)
    os.makedirs(dst, exist_ok=True)
    for name in os.listdir(src):
        if os.path.isfile(os.path.join(src, name)):
            shutil.copy2(os.path.join(src, name), os.path.join(dst, name))
            copied += 1
print("icons copied into res: %d files (from %s)" % (copied, icons_android))

# ---- 6) MainActivity：补系统栏内边距（Android 15 强制 edge-to-edge，否则标题被状态栏压住）
#        模板只调 enableEdgeToEdge()，WebView 会画到状态栏/手势条底下；
#        WebView 的 env(safe-area-inset-*) 不可靠，改在原生侧补 padding。
activity_dir = os.path.join(gen, "app", "src", "main", "java", "io", "github", "fdmdf", "frpc")
activity_src = os.path.join(os.path.dirname(os.path.dirname(gen)), "android", "MainActivity.kt")
activity_dst = os.path.join(activity_dir, "MainActivity.kt")
os.makedirs(activity_dir, exist_ok=True)
shutil.copy2(activity_src, activity_dst)
print("MainActivity.kt installed (edge-to-edge insets padding)")
PYEOF

echo "==> 写 local.properties（SDK 路径，仅 Windows Git Bash 需要）"
if command -v cygpath >/dev/null 2>&1; then
  ANDROID_HOME_WIN=$(cygpath -w "$ANDROID_HOME" | sed 's/\\/\\\\/g')
  printf 'sdk.dir=%s\n' "$ANDROID_HOME_WIN" > "$GEN/local.properties"
else
  echo "   (非 Windows：跳过，gradle 直接使用 ANDROID_HOME)"
fi

echo "==> 完成。构建流程："
cat <<'NOTE'
  # 桌面（Linux/macOS 或开启 Windows 开发者模式）：直接
  #   tauri android build --apk --target aarch64
  #
  # Windows 非管理员（符号链接不可用）绕过：
  #   1) tauri android build --apk --target aarch64   # 跑到符号链接报错，cargo 产物已就绪
  #   2) cp tauri/target/aarch64-linux-android/release/libfrpc_app_lib.so \
  #        tauri/app/src-tauri/gen/android/app/src/main/jniLibs/arm64-v8a/
  #      （引擎 libfrpc.so 走声明式源目录，无需复制）
  #   3) TAURI_SKIP_RUST_BUILD=1 ./gradlew :app:assembleArm64Release \
  #        -Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort=
  #   4) zipalign + apksigner 签名（见 src-tauri/android/README.md）
NOTE
