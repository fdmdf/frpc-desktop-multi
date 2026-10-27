#!/usr/bin/env bash
# Frpc-Android-Multi 端到端协议测试：真跑 frps + frpc，逐协议验证「能连上、能访问」。
#
#   bash scripts/e2e/e2e-protocols.sh
#
# 覆盖 8 种代理类型：tcp / udp / http / https / stcp / xtcp / tcpmux / sudp
#
# 流程：
#   1. 编译 frps（与 App 内置引擎同一个 frp 版本）
#   2. 用 **App 自己的编码器** 把档案 JSON 导出成 frpc.toml（:desktopApp:run --export-toml）
#   3. frpc verify 校验配置（frp 官方校验器）
#   4. 起后端（scripts/e2e/harness.go serve）→ 起 frps → 起 frpc(A: 8 个 proxy) + frpc(B: 3 个 visitor)
#   5. 等 admin API 显示全部代理 running，再用 harness check 逐个协议真实访问
#
# 依赖：go、python3、JDK（跑 gradle）。Windows(Git Bash)/Linux/macOS 通用。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REPO_ROOT="$(cygpath -m "$REPO_ROOT" 2>/dev/null || echo "$REPO_ROOT")"
cd "$REPO_ROOT"

FRP_VERSION="${FRP_VERSION:-v0.71.1}"
GO="${GO:-go}"
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"

# python3 在 Windows 上通常只有 python
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
else echo "需要 python3/python"; exit 1; fi

WORK="$REPO_ROOT/.build/e2e"
SRC_DIR="$REPO_ROOT/.build/frpc-engine/src/github.com/fatedier/frp@${FRP_VERSION}"
export EXE=""
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac

FRPC="$REPO_ROOT/desktopApp/engine/frpc${EXE}"
FRPS="$WORK/frps${EXE}"
HARNESS="$WORK/harness${EXE}"

PIDS=()
FAILED=0

log()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAILED=1; }

stop_pid() {
  local pid="$1"
  [ -n "${pid:-}" ] || return 0
  kill "$pid" 2>/dev/null
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) taskkill //F //PID "$pid" >/dev/null 2>&1 || true ;;
  esac
}

cleanup() {
  for pid in "${PIDS[@]:-}"; do stop_pid "$pid"; done
}
trap cleanup EXIT INT TERM

mkdir -p "$WORK"

# ---------------------------------------------------------------- 1. 前置检查
log "检查前置条件"
[ -x "$FRPC" ] || [ -f "$FRPC" ] || { echo "缺少引擎 ${FRPC}，请先运行 bash scripts/build-frpc.sh desktop"; exit 1; }
[ -d "$SRC_DIR" ] || { echo "缺少 frp 源码缓存，请先运行 bash scripts/build-frpc.sh desktop"; exit 1; }
ok "引擎 $("$FRPC" -v | head -1)"

# ---------------------------------------------------------------- 2. 编译 frps
log "编译 frps（${FRP_VERSION}）"
mkdir -p "$SRC_DIR/web/frps/dist"
[ -f "$SRC_DIR/web/frps/dist/index.html" ] || \
  printf '<!doctype html><meta charset="utf-8"><title>frps</title>\n' > "$SRC_DIR/web/frps/dist/index.html"
( cd "$SRC_DIR" && CGO_ENABLED=0 "$GO" build -trimpath -ldflags "-s -w" -tags frps -o "$FRPS" ./cmd/frps ) \
  || { echo "编译 frps 失败"; exit 1; }
ok "frps $("$FRPS" -v | head -1)"

log "编译测试台 harness"
( cd "$REPO_ROOT/scripts/e2e" && "$GO" build -o "$HARNESS" harness.go ) || { echo "编译 harness 失败"; exit 1; }
ok "harness 就绪"

# ---------------------------------------------------------------- 3. frps 配置
cat > "$WORK/frps.toml" <<'EOF'
bindPort = 17000
auth.method = "token"
auth.token = "e2etoken"
vhostHTTPPort = 17080
vhostHTTPSPort = 17443
# tcpmux(httpconnect) 必须在服务端显式开端口，否则 frpc 会报 feature not enabled
tcpmuxHTTPConnectPort = 17081
subDomainHost = "test.local"
transport.maxPoolCount = 5
allowPorts = [{ start = 17000, end = 17600 }]
EOF

# ---------------------------------------------------------------- 4. 档案 JSON
# 直接写成 App 的持久化格式（List<ServerProfile>），由 App 的编码器转 TOML
cat > "$WORK/profiles.json" <<'EOF'
[
  {
    "id": "e2e-a",
    "name": "e2e-server-side",
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000,
    "lastUsedAt": 0,
    "config": {
      "serverAddr": "127.0.0.1",
      "serverPort": 17000,
      "natHoleStunServer": "127.0.0.1:17900",
      "loginFailExit": true,
      "auth": { "method": "token", "token": "e2etoken" },
      "webServer": { "addr": "127.0.0.1", "port": 17400, "user": "admin", "password": "admin" },
      "proxies": [
        { "name": "e2e-tcp",  "type": "tcp",  "localIP": "127.0.0.1", "localPort": 18001, "remotePort": 17001 },
        { "name": "e2e-udp",  "type": "udp",  "localIP": "127.0.0.1", "localPort": 18002, "remotePort": 17002 },
        { "name": "e2e-http", "type": "http", "localIP": "127.0.0.1", "localPort": 18003, "customDomains": ["e2e-http.test"] },
        { "name": "e2e-https","type": "https","localIP": "127.0.0.1", "localPort": 18004, "customDomains": ["e2e-https.test"] },
        { "name": "e2e-stcp", "type": "stcp", "localIP": "127.0.0.1", "localPort": 18005, "secretKey": "e2esecret" },
        { "name": "e2e-xtcp", "type": "xtcp", "localIP": "127.0.0.1", "localPort": 18006, "secretKey": "e2esecret" },
        { "name": "e2e-sudp", "type": "sudp", "localIP": "127.0.0.1", "localPort": 18007, "secretKey": "e2esecret" },
        { "name": "e2e-mux",  "type": "tcpmux", "localIP": "127.0.0.1", "localPort": 18008,
          "multiplexer": "httpconnect", "customDomains": ["e2e-mux.test"] }
      ]
    }
  },
  {
    "id": "e2e-b",
    "name": "e2e-visitor",
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000,
    "lastUsedAt": 0,
    "config": {
      "serverAddr": "127.0.0.1",
      "serverPort": 17000,
      "natHoleStunServer": "127.0.0.1:17900",
      "loginFailExit": true,
      "auth": { "method": "token", "token": "e2etoken" },
      "webServer": { "addr": "127.0.0.1", "port": 17401, "user": "admin", "password": "admin" },
      "visitors": [
        { "name": "e2e-stcp-visitor", "type": "stcp", "serverName": "e2e-stcp", "secretKey": "e2esecret",
          "bindAddr": "127.0.0.1", "bindPort": 17005 },
        { "name": "e2e-xtcp-visitor", "type": "xtcp", "serverName": "e2e-xtcp", "secretKey": "e2esecret",
          "bindAddr": "127.0.0.1", "bindPort": 17006 },
        { "name": "e2e-sudp-visitor", "type": "sudp", "serverName": "e2e-sudp", "secretKey": "e2esecret",
          "bindAddr": "127.0.0.1", "bindPort": 17007 }
      ]
    }
  }
]
EOF

# ---------------------------------------------------------------- 5. App 编码器导出 TOML
log "用 App 自己的编码器导出 frpc.toml"
GRADLE_ARGS="--export-toml $WORK/profiles.json $WORK"
if ./gradlew -q --console=plain :desktopApp:run --args="$GRADLE_ARGS" > "$WORK/export.log" 2>&1; then
  grep '\[export-toml\]' "$WORK/export.log" || true
  ok "导出成功"
else
  tail -40 "$WORK/export.log"
  echo "导出 TOML 失败"; exit 1
fi
A_TOML="$WORK/profile-0.toml"
B_TOML="$WORK/profile-1.toml"

log "frpc verify 校验生成的配置"
"$FRPC" verify -c "$A_TOML" && ok "配置 A 通过官方校验" || bad "配置 A 校验失败"
"$FRPC" verify -c "$B_TOML" && ok "配置 B 通过官方校验" || bad "配置 B 校验失败"
[ "$FAILED" = "0" ] || { echo "配置校验未通过，终止"; exit 1; }

# ---------------------------------------------------------------- 6. 起服务
log "启动后端 / frps / frpc"
"$HARNESS" serve > "$WORK/harness.log" 2>&1 &
PIDS+=($!)
"$FRPS" -c "$WORK/frps.toml" > "$WORK/frps.log" 2>&1 &
PIDS+=($!)

wait_port() { # <port> <name> <timeout_s>
  $PY - "$1" "$2" "$3" <<'PY'
import socket, sys, time
port, name, timeout = int(sys.argv[1]), sys.argv[2], float(sys.argv[3])
deadline = time.time() + timeout
while time.time() < deadline:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            print(f"  ok {name} :{port}")
            sys.exit(0)
    except OSError:
        time.sleep(0.2)
print(f"  timeout {name} :{port}")
sys.exit(1)
PY
}

wait_port 18001 "后端" 15 || bad "后端未就绪"
wait_port 17000 "frps" 20 || bad "frps 未就绪"

"$FRPC" -c "$A_TOML" > "$WORK/frpc-a.log" 2>&1 &
PIDS+=($!)
"$FRPC" -c "$B_TOML" > "$WORK/frpc-b.log" 2>&1 &
PIDS+=($!)

# ---------------------------------------------------------------- 7. 等代理全部 running
log "等待 admin API 报告全部代理 running"
$PY - "$WORK" <<'PY'
import base64, json, sys, time, urllib.request

def status(port):
    req = urllib.request.Request(f"http://127.0.0.1:{port}/api/status")
    req.add_header("Authorization", "Basic " + base64.b64encode(b"admin:admin").decode())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=3) as r:
        return json.loads(r.read().decode())

expect = {"tcp": 1, "udp": 1, "http": 1, "https": 1, "stcp": 1, "xtcp": 1, "sudp": 1, "tcpmux": 1}
deadline = time.time() + 60
last = ""
while time.time() < deadline:
    try:
        data = status(17400)
        running = {t: sum(1 for i in v if i.get("status") == "running") for t, v in data.items()}
        last = json.dumps(running, ensure_ascii=False)
        if all(running.get(t, 0) >= n for t, n in expect.items()):
            print("  全部代理 running:", last)
            sys.exit(0)
    except Exception as e:
        last = f"{type(e).__name__}: {e}"
    time.sleep(1)
print("  超时，最后状态:", last)
sys.exit(1)
PY
[ $? -eq 0 ] && ok "代理已全部注册" || bad "代理未全部 running"
# visitor 侧也确认一下
$PY - 17401 <<'PY'
import base64, json, sys, urllib.request
port = sys.argv[1]
req = urllib.request.Request(f"http://127.0.0.1:{port}/api/status")
req.add_header("Authorization", "Basic " + base64.b64encode(b"admin:admin").decode())
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
data = json.loads(opener.open(req, timeout=3).read().decode())
running = {t: len(v) for t, v in data.items()}
print("  visitor 侧:", json.dumps(running, ensure_ascii=False))
PY

sleep 2

# ---------------------------------------------------------------- 8. 逐协议真实访问
log "逐协议真实访问校验"
"$HARNESS" check
CHECK_RC=$?

echo
if [ "$CHECK_RC" -eq 0 ] && [ "$FAILED" -eq 0 ]; then
  printf '\033[32m全部协议通过（tcp/udp/http/https/stcp/xtcp/tcpmux/sudp）\033[0m\n'
  exit 0
fi
echo "---- frps.log (tail) ----"; tail -25 "$WORK/frps.log"
echo "---- frpc-a.log (tail) ----"; tail -25 "$WORK/frpc-a.log"
echo "---- frpc-b.log (tail) ----"; tail -20 "$WORK/frpc-b.log"
printf '\033[31m协议测试失败\033[0m\n'
exit 1
