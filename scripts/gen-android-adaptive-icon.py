#!/usr/bin/env python3
"""修正 Android 自适应图标（adaptive icon）。

`tauri icon` 生成的前景图是「满幅的蓝色圆角方块」，而 Android 8+ 的启动器会
把前景按 108dp 画布的安全区（中心 66dp）裁切后再套上圆形/方圆角遮罩，
结果就是"白底圆圈里套一个被切掉一圈的小方块"。

这里改成标准做法：
  * 前景 = 白色标识（拱门 + 数据流箭头），居中且只占画布 62%（落在安全区内）
  * 背景 = 品牌蓝 #0B57D0（与桌面端图标同一套观感）

用法：python scripts/gen-android-adaptive-icon.py
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICONS = os.path.join(ROOT, "tauri", "app", "src-tauri", "icons", "android")

# 与 docs/assets/icon.svg 一致的几何（原图 512x512）
SIZE = 512
ARCH_CENTER = (256, 266)
ARCH_R = 106
STROKE = 34
LEG_BOTTOM = 366
DOT = (196, 310)
DOT_R = 15
FLOW_START = 216
FLOW_END = 286
FLOW_Y = 310
FLOW_W = 22
ARROW = [(282, 286), (322, 310), (282, 334)]

# 自适应图标各密度尺寸（108dp 基准）
DENSITIES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}
# 前景内容占画布比例（安全区 66/108 ≈ 0.61，取 0.62 留一点余量）
SAFE = 0.62
BRAND_BLUE = "#0B57D0"


def draw_glyph(size: int) -> Image.Image:
    """白色标识（透明底），按 size 等比缩放。"""
    s = size / SIZE
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)

    def px(x: float, y: float) -> tuple[float, float]:
        return x * s, y * s

    # 拱门：上半圆 + 两条竖腿（圆头端点用椭圆补）
    box = [
        (ARCH_CENTER[0] - ARCH_R) * s,
        (ARCH_CENTER[1] - ARCH_R) * s,
        (ARCH_CENTER[0] + ARCH_R) * s,
        (ARCH_CENTER[1] + ARCH_R) * s,
    ]
    d.arc(box, 180, 360, fill=white, width=max(1, int(STROKE * s)))
    for x in (ARCH_CENTER[0] - ARCH_R, ARCH_CENTER[0] + ARCH_R):
        top = px(x, ARCH_CENTER[1])
        bottom = px(x, LEG_BOTTOM)
        d.line([top, bottom], fill=white, width=max(1, int(STROKE * s)))
        r = STROKE * s / 2
        d.ellipse([bottom[0] - r, bottom[1] - r, bottom[0] + r, bottom[1] + r], fill=white)

    # 数据流：源点 + 横线 + 箭头
    cx, cy = px(*DOT)
    r = DOT_R * s
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=white)
    p1 = px(FLOW_START, FLOW_Y)
    p2 = px(FLOW_END, FLOW_Y)
    d.line([p1, p2], fill=white, width=max(1, int(FLOW_W * s)))
    fr = FLOW_W * s / 2
    d.ellipse([p2[0] - fr, p2[1] - fr, p2[0] + fr, p2[1] + fr], fill=white)
    d.polygon([px(x, y) for x, y in ARROW], fill=white)
    return img


def write_foreground() -> None:
    for folder, size in DENSITIES.items():
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        inner = int(round(size * SAFE))
        glyph = draw_glyph(inner)
        canvas.paste(glyph, ((size - inner) // 2, (size - inner) // 2), glyph)
        out = os.path.join(ICONS, folder, "ic_launcher_foreground.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        canvas.save(out)
        print("foreground", out, f"{size}x{size}")


def write_background() -> None:
    """背景色改为品牌蓝（原为 #FFFFFF）。"""
    for path in (
        os.path.join(ICONS, "values", "ic_launcher_background.xml"),
        os.path.join(ICONS, "values-night", "ic_launcher_background.xml"),
    ):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n')
            f.write("<resources>\n")
            f.write(f'  <color name="ic_launcher_background">{BRAND_BLUE}</color>\n')
            f.write("</resources>\n")
        print("background", path)


if __name__ == "__main__":
    if not os.path.isdir(ICONS):
        sys.exit(f"icons 目录不存在：{ICONS}（先跑 tauri icon）")
    write_foreground()
    write_background()
    print("done")
