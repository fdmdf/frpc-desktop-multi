#!/usr/bin/env bash

set -euo pipefail

MODE="${1:-all}"
case "$MODE" in
  all|desktop|android) ;;
  *) echo "usage: $0 [all|desktop|android]" >&2; exit 1 ;;
esac

FRP_VERSION="${FRP_VERSION:-v0.71.1}"
GO="${GO:-go}"
# 多源按序 fallback（goproxy.cn 国内快；proxy.golang.org 供 CI 机房兜底）
export GOPROXY="${GOPROXY:-https://goproxy.cn,https://proxy.golang.org,direct}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cygpath -m "$REPO_ROOT" 2>/dev/null || echo "$REPO_ROOT")"

WORK_ROOT="$REPO_ROOT/.build/frpc-engine"
SRC_PARENT="$WORK_ROOT/src"
SRC_DIR="$SRC_PARENT/github.com/fatedier/frp@${FRP_VERSION}"
ZIP_FILE="$WORK_ROOT/frp-${FRP_VERSION}.zip"

DESKTOP_ENGINE_DIR="$REPO_ROOT/desktopApp/engine"
JNILIBS="$REPO_ROOT/androidApp/src/main/jniLibs"
ASSETS="$REPO_ROOT/androidApp/src/main/assets/frpc"

unzip_to() {
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
  # 多源按序尝试（goproxy.cn 国内快；proxy.golang.org 供 CI 机房兜底）
  local proxies="https://goproxy.cn https://proxy.golang.org"
  local px
  for px in $proxies; do
    echo "==> 通过 $px 拉取 frp $FRP_VERSION"
    if curl -sSL --max-time 600 -o "$ZIP_FILE" \
        "$px/github.com/fatedier/frp/@v/${FRP_VERSION}.zip" \
      && unzip_to "$ZIP_FILE" "$SRC_PARENT" \
      && [ -f "$SRC_DIR/go.mod" ]; then
      return
    fi
    echo "==> $px 拉取/解包失败，尝试下一个源" >&2
    rm -rf "$SRC_DIR"
  done
  echo "源码拉取失败（所有源均不可用）" >&2
  exit 1
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

build_frpc() {
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
