#!/usr/bin/env bash
# 重新生成全套应用图标，并修正 Android 自适应图标。
#
#   源图：docs/assets/icon.svg（蓝底白拱门+箭头）
#   1) tauri icon 生成桌面 / iOS / Android 全套
#   2) gen-android-adaptive-icon.py：Android 8+ 的自适应图标改成
#      品牌蓝底 + 白色标识，字形按「可见区 72dp」定标到画布 50%
#      （满幅图标会被启动器裁掉一圈；字形过小则会缩成蓝底上一个白点），
#      并补 Android 13+ 主题图标需要的 monochrome 层。
#
# 前置：tauri/app/ui 已 npm install（CLI 在 node_modules 里）、python 已装 Pillow。
# 用法：bash scripts/gen-icons.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/tauri/app"

echo "==> tauri icon"
ui/node_modules/.bin/tauri icon ../../docs/assets/icon.svg

echo "==> Android 自适应图标"
python "$ROOT/scripts/gen-android-adaptive-icon.py"

echo "==> 完成。改动在 tauri/app/src-tauri/icons/（需提交）"
