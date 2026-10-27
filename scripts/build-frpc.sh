#!/usr/bin/env bash
# 统一引擎构建脚本：一份 frp 源码同时产出「桌面端引擎」与「Android jniLibs 引擎」。
#
# 用法：
#   bash scripts/build-frpc.sh             # 两端都编（当前平台）
#   bash scripts/build-frpc.sh desktop     # 只编桌面端
#   bash scripts/build-frpc.sh android     # 只编 Android
#
# 环境变量：
#   FRP_VERSION   frp 版本（默认 v0.71.1）
#   TARGETS       桌面端目标平台：current（默认）/ windows / linux / darwin / all
#   SKIP_X86_64   1（默认）只编 arm64-v8a；0 额外编 x86_64（需 NDK，模拟器用）
#   GO            go 可执行文件（默认 go）
#   GOPROXY       Go 模块代理（默认 goproxy.cn）
#
# 产物：
#   desktopApp/engine/frpc[.exe] + VERSION
#   androidApp/src/main/jniLibs/<abi>/libfrpc.so
#   androidApp/src/main/assets/frpc/VERSION
#
# 说明：
#   - 源码按版本缓存在 .build/frpc-engine/，重复构建不再下载。
#   - armeabi-v7a 未提供：Go 对 android/arm 32 位不支持 CGO_ENABLED=0 静态链接，
#     上游 frp 官方发布版同样只提供 android/arm64。
#   - web/frpc/dist 优先使用仓库内置官方控制台产物（scripts/frpc-web-dist），
#     否则放占位页保证 go:embed 可编译（App 只用 admin JSON API，不需要 Web 面板）。

set -euo pipefail

MODE="${1:-all}"
case "$MODE" in
  all|desktop|android) ;;
  *) echo "usage: $0 [all|desktop|android]" >&2; exit 1 ;;
esac

FRP_VERSION="${FRP_VERSION:-v0.71.1}"
GO="${GO:-go}"
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cygpath -m "$REPO_ROOT" 2>/dev/null || echo "$REPO_ROOT")"

WORK_ROOT="$REPO_ROOT/.build/frpc-engine"
SRC_PARENT="$WORK_ROOT/src"
SRC_DIR="$SRC_PARENT/github.com/fatedier/frp@${FRP_VERSION}"
ZIP_FILE="$WORK_ROOT/frp-${FRP_VERSION}.zip"

DESKTOP_ENGINE_DIR="$REPO_ROOT/desktopApp/engine"
JNILIBS="$REPO_ROOT/androidApp/src/main/jniLibs"
ASSETS="$REPO_ROOT/androidApp/src/main/assets/frpc"

unzip_to() { # <zip> <dest>
  local py="python3"
  command -v python3 >/dev/null 2>&1 || py="python"
  "$py" -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$1" "$2"
}

fetch_source() {
  if [ -d "$SRC_DIR" ] && [ -f "$SRC_DIR/go.mod" ]; then
    echo "==> 使用缓存源码 $SRC_DIR"
    return
  fi
  mkdir -p "$SRC_PARENT"
  echo "==> 通过模块代理拉取 frp $FRP_VERSION"
  curl -sSL --max-time 600 -o "$ZIP_FILE" \
    "https://goproxy.cn/github.com/fatedier/frp/@v/${FRP_VERSION}.zip"
  unzip_to "$ZIP_FILE" "$SRC_PARENT"
  [ -f "$SRC_DIR/go.mod" ] || { echo "源码解包失败：$SRC_DIR" >&2; exit 1; }
}

prepare_web_assets() {
  local dist="$SRC_DIR/web/frpc/dist"
  mkdir -p "$dist"
  if [ -f "$REPO_ROOT/scripts/frpc-web-dist/index.html" ]; then
    echo "==> 使用内置官方控制台 scripts/frpc-web-dist"
    cp -r "$REPO_ROOT/scripts/frpc-web-dist/." "$dist/"
  else
    echo "==> 未找到控制台产物，使用占位页"
    printf '<!doctype html><meta charset="utf-8"><title>frpc</title><p>admin web assets not bundled.</p>\n' \
      > "$dist/index.html"
  fi
}

build_frpc() { # <goos> <goarch> <out>
  local goos="$1" goarch="$2" out="$3"
  echo "==> 编译 frpc $goos/$goarch -> $out"
  mkdir -p "$(dirname "$out")"
  ( cd "$SRC_DIR" && CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
      "$GO" build -trimpath -ldflags "-s -w" -tags frpc -o "$out" ./cmd/frpc )
}

current_goos() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) echo windows ;;
    Darwin) echo darwin ;;
    *) echo linux ;;
  esac
}

current_goarch() {
  case "$(uname -m)" in
    arm64|aarch64) echo arm64 ;;
    *) echo amd64 ;;
  esac
}

build_desktop() {
  local targets="${TARGETS:-current}"
  local goos goarch out
  mkdir -p "$DESKTOP_ENGINE_DIR"
  case "$targets" in
    windows) goos=windows; goarch=amd64; out="$DESKTOP_ENGINE_DIR/frpc.exe" ;;
    linux)   goos=linux;   goarch=amd64; out="$DESKTOP_ENGINE_DIR/frpc" ;;
    darwin)  goos=darwin;  goarch="$(current_goarch)"; out="$DESKTOP_ENGINE_DIR/frpc" ;;
    current) goos="$(current_goos)"; goarch="$(current_goarch)"
             if [ "$goos" = "windows" ]; then out="$DESKTOP_ENGINE_DIR/frpc.exe"
             else out="$DESKTOP_ENGINE_DIR/frpc"; fi ;;
    all)     build_frpc windows amd64 "$WORK_ROOT/out/frpc.exe"
             build_frpc linux   amd64 "$WORK_ROOT/out/frpc-linux-amd64"
             build_frpc darwin  arm64 "$WORK_ROOT/out/frpc-darwin-arm64"
             build_frpc darwin  amd64 "$WORK_ROOT/out/frpc-darwin-amd64"
             echo "$FRP_VERSION" > "$DESKTOP_ENGINE_DIR/VERSION"
             echo "==> TARGETS=all：跨平台引擎在 $WORK_ROOT/out"
             return ;;
    *) echo "未知 TARGETS=$targets" >&2; exit 1 ;;
  esac

  build_frpc "$goos" "$goarch" "$out"
  echo "$FRP_VERSION" > "$DESKTOP_ENGINE_DIR/VERSION"
  ls -la "$DESKTOP_ENGINE_DIR"
}

build_android() {
  mkdir -p "$ASSETS"
  echo "$FRP_VERSION" > "$ASSETS/VERSION"
  build_frpc android arm64 "$JNILIBS/arm64-v8a/libfrpc.so"

  if [ "${SKIP_X86_64:-1}" != "1" ]; then
    local ndk_bin
    ndk_bin="$(android_clang)"
    echo "==> 编译 android/amd64（cgo，NDK）"
    mkdir -p "$JNILIBS/x86_64"
    ( cd "$SRC_DIR" && CGO_ENABLED=1 CC="$ndk_bin/x86_64-linux-android21-clang" \
        GOOS=android GOARCH=amd64 \
        "$GO" build -trimpath -ldflags "-s -w" -tags frpc \
        -o "$JNILIBS/x86_64/libfrpc.so" ./cmd/frpc )
  fi
  ls -la "$JNILIBS"/*/
}

android_clang() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  local host="linux-x86_64"
  case "$(uname -s)" in
    Darwin) host="darwin-x86_64" ;;
    MINGW*|MSYS*|CYGWIN*) host="windows-x86_64" ;;
  esac
  local latest
  latest="$(ls "$sdk/ndk" 2>/dev/null | sort -V | tail -1)"
  [ -n "$latest" ] || { echo "未找到 NDK（$sdk/ndk）" >&2; exit 1; }
  echo "$sdk/ndk/$latest/toolchains/llvm/prebuilt/$host/bin"
}

fetch_source
prepare_web_assets

case "$MODE" in
  desktop) build_desktop ;;
  android) build_android ;;
  all)     build_desktop; build_android ;;
esac

echo "==> 完成（frp ${FRP_VERSION}，模式 ${MODE}）"
