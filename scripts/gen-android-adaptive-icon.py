#!/usr/bin/env python3
"""生成 Android 自适应图标（adaptive icon）的前景层。

背景
----
`tauri icon` 生成的前景是「满幅的蓝色圆角方块」。Android 8+ 的启动器会把前景
按 108dp 画布裁掉外圈、只显示中心 72dp，再套上圆形 / 方圆角遮罩 —— 结果就是
「圆里套一个被切掉一圈的小方块」。这里改成标准做法：

  * 前景 = 白色标识（拱门 + 数据流箭头），透明底
  * 背景 = 品牌蓝 #0B57D0（见 values/ic_launcher_background.xml）

尺寸口径（重要）
----------------
108dp 画布中只有中心 72dp 可见，关键内容宜落在中心 66dp 的 keyline 圆内。
本脚本的 CONTENT_RATIO 指的是 **字形包围盒宽度 / 108dp 画布宽度**。

旧实现把 SAFE=0.62 乘在 512 的绘制画布上，而字形只占该画布的 48%，
实际落进图标只有 0.62×0.48 ≈ 30%，桌面显示成「蓝底上一个小白标」。
现在改为先裁到字形包围盒、再按 CONTENT_RATIO 缩放，并真正居中。

用法：python scripts/gen-android-adaptive-icon.py
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICONS = os.path.join(ROOT, "tauri", "app", "src-tauri", "icons", "android")

# 与 docs/assets/icon.svg 一致的几何（原图 512x512 坐标系）
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

# 字形包围盒宽度占 108dp 画布的比例。
# 0.50 → 字形占可见区（72dp）约 77%，与桌面图标一致（桌面版占圆角方块 48%）。
# 实测（432px 画布）：0.52 时圆形遮罩切掉 0.08% 像素（竖腿圆头贴到边上），
# 0.56 时切掉 3.3%（圆头被切平），0.50 及以下零裁切 —— 取零裁切里最饱满的一档。
CONTENT_RATIO = 0.50
# 超采样倍数：先按 4 倍绘制再缩回去，避免圆弧与圆头端点出现锯齿
SS = 4
BRAND_BLUE = "#0B57D0"


def draw_glyph() -> Image.Image:
    """在 512 坐标系下绘制字形，返回已裁到包围盒的 RGBA 图。"""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)

    # 拱门：上半圆 + 两条竖腿（竖腿底部用圆头收尾）
    box = [
        ARCH_CENTER[0] - ARCH_R,
        ARCH_CENTER[1] - ARCH_R,
        ARCH_CENTER[0] + ARCH_R,
        ARCH_CENTER[1] + ARCH_R,
    ]
    d.arc(box, 180, 360, fill=white, width=STROKE)
    for x in (ARCH_CENTER[0] - ARCH_R, ARCH_CENTER[0] + ARCH_R):
        d.line([(x, ARCH_CENTER[1]), (x, LEG_BOTTOM)], fill=white, width=STROKE)
        r = STROKE / 2
        d.ellipse([x - r, LEG_BOTTOM - r, x + r, LEG_BOTTOM + r], fill=white)

    # 数据流：源点 + 横线（带圆头）+ 箭头
    cx, cy = DOT
    d.ellipse([cx - DOT_R, cy - DOT_R, cx + DOT_R, cy + DOT_R], fill=white)
    d.line([(FLOW_START, FLOW_Y), (FLOW_END, FLOW_Y)], fill=white, width=FLOW_W)
    fr = FLOW_W / 2
    d.ellipse([FLOW_END - fr, FLOW_Y - fr, FLOW_END + fr, FLOW_Y + fr], fill=white)
    d.polygon(ARROW, fill=white)

    bbox = img.split()[3].getbbox()
    if bbox is None:
        sys.exit("字形为空：绘制参数可能被改坏了")
    return img.crop(bbox)


def render_foreground(size: int, ratio: float = CONTENT_RATIO) -> Image.Image:
    """按目标画布尺寸渲染前景层（透明底，字形居中）。"""
    glyph = draw_glyph()

    # 超采样：先放大到 size*SS，再整体缩回，边缘更干净
    big = size * SS
    target_w = big * ratio
    scale = target_w / glyph.width
    target_h = glyph.height * scale
    glyph = glyph.resize(
        (max(1, round(glyph.width * scale)), max(1, round(glyph.height * scale))),
        Image.LANCZOS,
    )

    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    canvas.paste(
        glyph,
        (round((big - glyph.width) / 2), round((big - glyph.height) / 2)),
        glyph,
    )
    if target_h > big:
        sys.exit("字形高度超出画布，检查 CONTENT_RATIO")
    return canvas.resize((size, size), Image.LANCZOS)


def write_foreground() -> None:
    for folder, size in DENSITIES.items():
        img = render_foreground(size)
        out = os.path.join(ICONS, folder, "ic_launcher_foreground.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        img.save(out, optimize=True)
        bbox = img.split()[3].getbbox()
        print(
            "foreground %-18s %dx%d  content=%.1f%%  offset=(%+.0f,%+.0f)px"
            % (
                folder,
                size,
                size,
                (bbox[2] - bbox[0]) / size * 100,
                (bbox[0] + bbox[2]) / 2 - size / 2,
                (bbox[1] + bbox[3]) / 2 - size / 2,
            )
        )


def write_background() -> None:
    """背景色固定为品牌蓝（原模板是白色）。"""
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


def write_monochrome_resources() -> None:
    """Android 13+ 主题图标（themed icon）：用同一份白色前景做单色层。

    前景已经是「纯白 + alpha」，系统着色时会按 alpha 上色，可直接复用。
    """
    os.makedirs(os.path.join(ICONS, "mipmap-anydpi-v33"), exist_ok=True)
    path = os.path.join(ICONS, "mipmap-anydpi-v33", "ic_launcher.xml")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n')
        f.write('  <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n')
        f.write('  <background android:drawable="@color/ic_launcher_background"/>\n')
        f.write('  <monochrome android:drawable="@mipmap/ic_launcher_foreground"/>\n')
        f.write("</adaptive-icon>\n")
    print("monochrome", path)


if __name__ == "__main__":
    if not os.path.isdir(ICONS):
        sys.exit(f"icons 目录不存在：{ICONS}（先跑 tauri icon）")
    write_foreground()
    write_background()
    write_monochrome_resources()
    print("done")
