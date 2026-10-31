#!/usr/bin/env bash
# Rust 核心层八协议 e2e：构建 frps/frpc（缓存源码），跑 frpc verify × 8 + 真实 TCP 隧道回环。
# 用法：bash scripts/tauri-e2e.sh
set -euo pipefail

FRP_VERSION="${FRP_VERSION:-v0.71.1}"
GO="${GO:-go}"
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cygpath -m "$REPO_ROOT" 2>/dev/null || echo "$REPO_ROOT")"
SRC_DIR="$REPO_ROOT/.build/frpc-engine/src/github.com/fatedier/frp@${FRP_VERSION}"
OUT_DIR="$REPO_ROOT/.build/e2e"
E2E_DIR="$OUT_DIR/web-frps-dist"

if [ ! -f "$SRC_DIR/go.mod" ]; then
  echo "==> frp 源码未缓存，先运行 scripts/build-frpc.sh desktop" >&2
  bash "$REPO_ROOT/scripts/build-frpc.sh" desktop
fi

mkdir -p "$OUT_DIR"
if [ ! -f "$SRC_DIR/web/frps/dist/index.html" ]; then
  mkdir -p "$SRC_DIR/web/frps/dist"
  printf '<!doctype html><meta charset="utf-8"><title>frps</title><p>e2e placeholder.</p>\n' > "$SRC_DIR/web/frps/dist/index.html"
fi

echo "==> 编译 frps（e2e 用）"
if [ ! -f "$OUT_DIR/frps.exe" ] && [ ! -f "$OUT_DIR/frps" ]; then
  exe=""
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) exe=".exe" ;;
  esac
  ( cd "$SRC_DIR" && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -tags frps \
      -o "$OUT_DIR/frps$exe" ./cmd/frps )
fi

# 引擎：优先 desktopApp/engine（build-frpc.sh 产物）
if [ ! -f "$REPO_ROOT/desktopApp/engine/frpc.exe" ] && [ ! -f "$REPO_ROOT/desktopApp/engine/frpc" ]; then
  echo "==> 引擎缺失，构建 frpc"
  bash "$REPO_ROOT/scripts/build-frpc.sh" desktop
fi

echo "==> 运行 Rust 核心 e2e（verify × 8 + 真实隧道）"
export FRPC_E2E=1
export FRPC_BIN="$REPO_ROOT/desktopApp/engine/frpc.exe"
[ -f "$FRPC_BIN" ] || export FRPC_BIN="$REPO_ROOT/desktopApp/engine/frpc"
export FRPS_BIN="$(ls "$OUT_DIR"/frps* | head -1)"

cargo test --manifest-path "$REPO_ROOT/tauri/Cargo.toml" -p frpc-core \
  --test e2e_protocols -- --nocapture
echo "==> e2e 完成"
