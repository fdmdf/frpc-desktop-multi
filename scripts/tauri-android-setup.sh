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

echo "==> 完成。构建 APK："
echo "  cd $APP && ui/node_modules/.bin/tauri android build --apk --target aarch64"
