#!/usr/bin/env bash
# 同步 docs/wiki/*.md 到 GitHub Wiki
#
# 用法: bash scripts/sync-wiki.sh <owner> <repo>
#
# 会做三件事：
#   1. 克隆（或复用）wiki 仓库
#   2. 复制页面，并把仓库内相对链接改写为 wiki/仓库绝对链接
#   3. 提交并推送（GitHub Wiki 默认分支为 master）

set -euo pipefail

OWNER="${1:-}"
REPO="${2:-}"
if [ -z "$OWNER" ] || [ -z "$REPO" ]; then
  echo "用法: bash scripts/sync-wiki.sh <owner> <repo>"
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/docs/wiki"
REPO_URL="https://github.com/${OWNER}/${REPO}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 克隆 wiki 仓库"
if ! git clone "https://github.com/${OWNER}/${REPO}.wiki.git" "$WORK/wiki" 2>/dev/null; then
  echo "错误：wiki 仓库不存在。请先在 ${REPO_URL}/wiki 创建首页。"
  exit 1
fi

echo "==> 复制并改写链接"
for f in "$SRC"/*.md; do
  name="$(basename "$f")"
  sed -e "s|(\.\./\.\./releases/latest)|(${REPO_URL}/releases/latest)|g" \
      -e "s|(\.\./\.\./issues/new/choose)|(${REPO_URL}/issues/new/choose)|g" \
      -e "s|(\.\./\.\./README\.md)|(${REPO_URL})|g" \
      -e 's|](\([^)]*\)\.md)|](\1)|g' \
      "$f" > "$WORK/wiki/$name"
  echo "    $name"
done

cd "$WORK/wiki"
git add -A
if git diff --cached --quiet; then
  echo "==> 无变化，跳过提交"
  exit 0
fi
git -c user.name="wiki-sync" -c user.email="wiki-sync@local" commit -q -m "docs: sync wiki from repository"
git push -q origin HEAD
echo "==> 已发布: ${REPO_URL}/wiki"
