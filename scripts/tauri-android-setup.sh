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

echo "==> BuildTask 加 TAURI_SKIP_RUST_BUILD 守卫"
echo "   （Windows 非管理员无法创建符号链接：先 tauri android build 一次拿到"
echo "     cargo 产物并手工复制 libfrpc_app_lib.so，再据此跳过 CLI 回调）"
python - "$GEN" <<'EOF'
import sys
gen = sys.argv[1]
p2 = f"{gen}/buildSrc/src/main/java/io/github/fdmdf/frpc/kotlin/BuildTask.kt"
s2 = open(p2, encoding="utf-8").read()
old = """    @TaskAction
    fun assemble() {"""
new = """    @TaskAction
    fun assemble() {
        // 预构建模式：cargo 产物已由外部放置到 jniLibs，跳过 tauri CLI
        // （Windows 非管理员无法创建符号链接；如需恢复 CLI 构建请删除此 return）
        if (System.getenv("TAURI_SKIP_RUST_BUILD") != null) {
            println("BuildTask: skipped (TAURI_SKIP_RUST_BUILD set)")
            return
        }"""
if "TAURI_SKIP_RUST_BUILD" not in s2:
    assert old in s2, "BuildTask anchor not found"
    s2 = s2.replace(old, new, 1)
    open(p2, "w", encoding="utf-8", newline="").write(s2)
print("BuildTask guarded")
EOF

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
