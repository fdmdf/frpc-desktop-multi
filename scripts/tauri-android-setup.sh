#!/usr/bin/env bash
# 一键重建 Tauri Android 工程（gen/ 不入库，用此脚本从零恢复）：
#   1. tauri android init 生成工程
#   2. 注入阿里云 Maven 镜像（国内直连 google() 不通）
#   3. 复制旧 Android 工程预编译的 libfrpc.so 到 jniLibs（nativeLibraryDir 可执行）
# 前置：ANDROID_HOME / NDK_HOME 环境变量，rustup android targets（见 README）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/tauri/app"
GEN="$APP/src-tauri/gen/android"
OLD_JNI="$ROOT/androidApp/src/main/jniLibs"

: "${ANDROID_HOME:?请设置 ANDROID_HOME}"
export NDK_HOME="${NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"

echo "==> tauri android init"
cd "$APP" && ui/node_modules/.bin/tauri android init

echo "==> 注入阿里云镜像"
python - "$GEN" <<'EOF'
import sys
gen = sys.argv[1]
mirror = (
    '        maven("https://maven.aliyun.com/repository/google")\n'
    '        maven("https://maven.aliyun.com/repository/public")\n'
    '        maven("https://maven.aliyun.com/repository/gradle-plugin")\n'
)
p = f"{gen}/build.gradle.kts"
s = open(p, encoding="utf-8").read()
s = s.replace(
    "buildscript {\n    repositories {\n        google()\n        mavenCentral()\n    }",
    "buildscript {\n    repositories {\n" + mirror + "        google()\n        mavenCentral()\n    }",
)
s = s.replace(
    "allprojects {\n    repositories {\n        google()\n        mavenCentral()\n    }",
    "allprojects {\n    repositories {\n" + mirror + "        google()\n        mavenCentral()\n    }",
)
open(p, "w", encoding="utf-8", newline="").write(s)

p2 = f"{gen}/buildSrc/build.gradle.kts"
s2 = open(p2, encoding="utf-8").read()
s2 = s2.replace(
    "repositories {\n    google()\n    mavenCentral()\n}",
    "repositories {\n" + mirror + "    google()\n    mavenCentral()\n}",
)
open(p2, "w", encoding="utf-8", newline="").write(s2)
print("mirrors injected")
EOF

echo "==> 复制 libfrpc.so 到 jniLibs"
for abi in arm64-v8a x86_64; do
  if [ -f "$OLD_JNI/$abi/libfrpc.so" ]; then
    mkdir -p "$GEN/app/src/main/jniLibs/$abi"
    cp "$OLD_JNI/$abi/libfrpc.so" "$GEN/app/src/main/jniLibs/$abi/"
  fi
done
ls -la "$GEN/app/src/main/jniLibs/"*/ || true

echo "==> 写 local.properties（SDK 路径）"
ANDROID_HOME_WIN=$(cygpath -w "$ANDROID_HOME" | sed 's/\\/\\\\/g')
printf 'sdk.dir=%s\n' "$ANDROID_HOME_WIN" > "$GEN/local.properties"

echo "==> gradle 工程补丁（init 重新生成后必须重打）"
python - "$GEN" <<'PYEOF'
import sys
gen = sys.argv[1]

# 1) app/build.gradle.kts：NDK 版本 / 压缩打包 / 引擎源目录
p = f"{gen}/app/build.gradle.kts"
s = open(p, encoding="utf-8").read()
if "ndkVersion" not in s:
    s = s.replace("        minSdk = 24",
                  '        ndkVersion = "27.2.12479018"\n        minSdk = 24', 1)
if "useLegacyPackaging" not in s:
    import re
    s = re.sub(
        r"            packaging \{\n(                jniLibs\.keepDebugSymbols\.add\([^)]*\)\n)+",
        "            packaging {\n"
        "                // native 库压缩存储（17.4MB 引擎 → ~6.2MB，与旧版 androidApp 一致）\n"
        "                jniLibs.useLegacyPackaging = true\n",
        s, 1)
if "androidApp/src/main/jniLibs" not in s:
    s = s.replace("    defaultConfig {",
                  "    sourceSets {\n"
                  "        getByName("main") {\n"
                  "            // frpc 引擎复用旧工程预编译产物（仓库根 = rootDir 上溯 5 级）\n"
                  "            jniLibs.srcDir(\n"
                  "                File(rootDir.parentFile.parentFile.parentFile.parentFile.parentFile,\n"
                  "                     "androidApp/src/main/jniLibs")\n"
                  "            )\n"
                  "        }\n"
                  "    }\n\n    defaultConfig {", 1)
open(p, "w", encoding="utf-8", newline="").write(s)
print("app/build.gradle.kts patched")

# 2) BuildTask：TAURI_SKIP_RUST_BUILD 守卫（Windows 非管理员符号链接限制）
p2 = f"{gen}/buildSrc/src/main/java/io/github/fdmdf/frpc/kotlin/BuildTask.kt"
s2 = open(p2, encoding="utf-8").read()
old = """    @TaskAction
    fun assemble() {"""
new = """    @TaskAction
    fun assemble() {
        // 预构建模式：cargo 产物已由外部放置到 jniLibs，跳过 tauri CLI
        if (System.getenv("TAURI_SKIP_RUST_BUILD") != null) {
            println("BuildTask: skipped (TAURI_SKIP_RUST_BUILD set)")
            return
        }"""
if "TAURI_SKIP_RUST_BUILD" not in s2:
    assert old in s2
    s2 = s2.replace(old, new, 1)
    open(p2, "w", encoding="utf-8", newline="").write(s2)
print("BuildTask guarded")
PYEOF

echo "==> 写 local.properties（SDK 路径）"
ANDROID_HOME_WIN=$(cygpath -w "$ANDROID_HOME" | sed 's/\\/\\\\/g')
printf 'sdk.dir=%s\n' "$ANDROID_HOME_WIN" > "$GEN/local.properties"

echo "==> 完成。完整构建流程（Windows 非管理员绕过符号链接限制）："
cat <<'NOTE'
  # 1) 先跑一次 tauri CLI 让它编译 Rust（到符号链接报错即停下，Rust 产物已就绪）：
  #    注意：这一步会把 jniLibs 里手工放的文件清掉！
  #    tauri android build --apk --target aarch64
  # 2) 手工放置两个动态库（jniLibs 已被 CLI 清空，需重新复制）：
  #    cp tauri/target/aarch64-linux-android/release/libfrpc_app_lib.so \
  #       tauri/app/src-tauri/gen/android/app/src/main/jniLibs/arm64-v8a/
  #    cp androidApp/src/main/jniLibs/arm64-v8a/libfrpc.so \
  #       tauri/app/src-tauri/gen/android/app/src/main/jniLibs/arm64-v8a/
  # 3) 直接驱动 gradle（TAURI_SKIP_RUST_BUILD=1 跳过 CLI 回调；若全局
  #    ~/.gradle/gradle.properties 有过期代理，用空 -D 参数覆盖为直连）：
  #    TAURI_SKIP_RUST_BUILD=1 ./gradlew :app:assembleArm64Release \
  #      -Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort=
  # 4) zipalign + apksigner 签名（密钥见 src-tauri/android/README.md）。
  # 提示：开启 Windows 开发者模式后可直接 tauri android build，无需以上绕过。
NOTE
