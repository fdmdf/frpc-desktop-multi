#!/usr/bin/env python3
"""生成 Android 自适应图标（adaptive icon）资源。

背景
----
`tauri icon` 生成的前景是「满幅的蓝色圆角方块」。Android 8+ 的启动器会把前景
按 108dp 画布裁掉外圈、只显示中心 72dp，再套上圆形 / 方圆角遮罩 —— 结果就是
「圆里套一个被切掉一圈的小方块」。这里改成标准做法：

  * 前景 = 白色标识（拱门 + 数据流箭头），透明底
  * 背景 = 品牌蓝 #0B57D0（见 values/ic_launcher_background.xml）

几何必须与 docs/assets/icon.svg（即桌面 icon.png）完全一致
--------------------------------------------------------
SVG 的 `stroke` 是**沿路径居中**画的：拱门半径 106、描边 34，实际覆盖半径
89~123。而 PIL 的 `ImageDraw.arc(width=)` 是**从外边界向内**描 —— 早期版本
直接用它，导致内径变成 106-34=72（少了 17px），拱门成了「肥厚的小拱」：
开口变小、竖腿与拱门之间还留了个台阶。桌面图标和安卓图标放一起就能看出差别。

现在改为蒙版绘制：外半圆（半径 R+STROKE/2）填满后挖掉内圆（R-STROKE/2），
竖腿用矩形 + 圆头补齐，与 SVG 的描边语义逐像素对齐。

尺寸口径
--------
108dp 画布中只有中心 72dp 可见，关键内容宜落在中心 66dp 的 keyline 圆内。
`CONTENT_RATIO` 指 **字形包围盒宽度 / 108dp 画布宽度**。

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

# 自适应图标前景各密度尺寸（108dp 基准）
DENSITIES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

# 旧版位图图标（API 24/25 与未做遮罩的第三方壳子会直接用这张）：48dp 基准
LEGACY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# 字形包围盒宽度占 108dp 画布的比例。
# 0.50 → 字形占可见区（72dp）约 75%，实测圆形遮罩零裁切（见 verify_ratios）。
CONTENT_RATIO = 0.50
# 旧版位图是满幅显示（没有遮罩裁切），按桌面 icon.png 的 48% 走
LEGACY_CONTENT_RATIO = 0.48

# 绘制精度：每 1 个 SVG 单位渲染成几个像素（512 单位 → 2048px 画布），
# 再下采样到各密度，圆弧不会出现锯齿。
PPU = 4

BRAND_BLUE = "#0B57D0"
BRAND_BLUE_TOP = "#1E88E5"


def glyph_mask(ppu: float = PPU) -> Image.Image:
    """按 SVG 语义绘制字形蒙版（白色 = 有内容），返回已裁到包围盒的 L 图。"""
    box = int(round(SIZE * ppu))
    mask = Image.new("L", (box, box), 0)
    d = ImageDraw.Draw(mask)

    half = STROKE / 2
    cx, cy = ARCH_CENTER

    def px(x: float, y: float):
        return (x * ppu, y * ppu)

    # 拱门上半圆：先填外圆（R+half），再挖内圆（R-half）—— 等价于居中描边
    r_out = (ARCH_R + half) * ppu
    r_in = (ARCH_R - half) * ppu
    ox, oy = px(cx, cy)
    d.pieslice([ox - r_out, oy - r_out, ox + r_out, oy + r_out], 180, 360, fill=255)
    d.ellipse([ox - r_in, oy - r_in, ox + r_in, oy + r_in], fill=0)

    # 两条竖腿（矩形 + 底部圆头），从拱门圆心高度接到 LEG_BOTTOM
    for x in (cx - ARCH_R, cx + ARCH_R):
        d.rectangle([*px(x - half, cy), *px(x + half, LEG_BOTTOM)], fill=255)
        d.ellipse(
            [*px(x - half, LEG_BOTTOM - half), *px(x + half, LEG_BOTTOM + half)],
            fill=255,
        )

    # 数据流：源点 + 横线（两端圆头）+ 箭头
    dx, dy = px(*DOT)
    rr = DOT_R * ppu
    d.ellipse([dx - rr, dy - rr, dx + rr, dy + rr], fill=255)

    fw = max(1, int(round(FLOW_W * ppu)))
    d.line([*px(FLOW_START, FLOW_Y), *px(FLOW_END, FLOW_Y)], fill=255, width=fw)
    fr = fw / 2
    ex, ey = px(FLOW_END, FLOW_Y)
    d.ellipse([ex - fr, ey - fr, ex + fr, ey + fr], fill=255)

    d.polygon([px(x, y) for x, y in ARROW], fill=255)

    bbox = mask.getbbox()
    if bbox is None:
        sys.exit("字形为空：绘制参数可能被改坏了")
    return mask.crop(bbox)


def white_glyph(ppu: float = PPU) -> Image.Image:
    """纯白 + 蒙版 alpha 的字形图（已裁到包围盒）。"""
    mask = glyph_mask(ppu)
    glyph = Image.new("RGBA", mask.size, (255, 255, 255, 255))
    glyph.putalpha(mask)
    return glyph


def place(glyph: Image.Image, size: int, ratio: float) -> Image.Image:
    """把字形按「宽度 = size*ratio」等比缩放并居中，输出 size×size 的透明画布。"""
    target_w = max(1, size * ratio)
    scale = target_w / glyph.width
    new = (
        max(1, round(glyph.width * scale)),
        max(1, round(glyph.height * scale)),
    )
    if new[1] > size:
        sys.exit(f"字形高度 {new[1]}px 超出画布 {size}px，检查 CONTENT_RATIO")
    small = glyph.resize(new, Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(small, (round((size - new[0]) / 2), round((size - new[1]) / 2)), small)
    return canvas


def render_foreground(size: int, ratio: float = CONTENT_RATIO) -> Image.Image:
    """自适应图标前景层（透明底，字形居中）。"""
    return place(white_glyph(), size, ratio)


def linear_gradient(size: int) -> Image.Image:
    """斜向品牌渐变（与 icon.svg 的 #1E88E5 → #0B57D0 同向），用于旧版位图。"""
    top, bottom = (30, 136, 229), (11, 87, 208)
    grad = Image.new("RGBA", (size, size))
    px = grad.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1)) if size > 1 else 0
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,)
    return grad


def render_legacy(size: int, circle: bool) -> Image.Image:
    """旧版位图图标：满幅蓝底（圆角方 / 圆形）+ 居中字形。"""
    ss = 4
    big = size * ss
    base = linear_gradient(big)
    shape = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(shape)
    if circle:
        d.ellipse([0, 0, big - 1, big - 1], fill=255)
    else:
        # 与 icon.svg 一致的 116/512 圆角
        d.rounded_rectangle([0, 0, big - 1, big - 1], radius=round(big * 116 / 512), fill=255)
    base.putalpha(shape)

    glyph = white_glyph()
    scaled_w = big * LEGACY_CONTENT_RATIO
    scale = scaled_w / glyph.width
    small = glyph.resize((round(glyph.width * scale), round(glyph.height * scale)), Image.LANCZOS)
    base.alpha_composite(small, (round((big - small.width) / 2), round((big - small.height) / 2)))
    return base.resize((size, size), Image.LANCZOS)


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


def write_legacy() -> None:
    """旧版位图 `ic_launcher.png`（圆角方）与 `ic_launcher_round.png`（圆形）。

    这两张是 `tauri icon` 的出厂产物，API 24/25（minSdk 24）会直接当图标用；
    当前清单没有声明 `android:roundIcon`，所以 `_round` 那张在 release 包里会被
    资源压缩剥掉，但保留在 `icons/android/` 里与其余密度保持一致，将来若声明
    roundIcon 也不至于拿到上一版几何。
    """
    for folder, size in LEGACY_SIZES.items():
        for name, circle in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
            out = os.path.join(ICONS, folder, name)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            render_legacy(size, circle).save(out, optimize=True)
        print("legacy     %-18s %dx%d (ic_launcher / ic_launcher_round)" % (folder, size, size))


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


def write_adaptive_xml() -> None:
    """写 anydpi-v26 / anydpi-v33 的 `ic_launcher.xml`。

    只写 `ic_launcher`：Tauri 模板的 AndroidManifest 只声明 `android:icon`，
    没有 `android:roundIcon` —— 用 aapt2 查过成品 APK 的资源表，里面根本不存在
    `mipmap/ic_launcher_round`（未引用的图标会被 release 的资源压缩直接剥掉）。
    所以不要再补 `ic_launcher_round.xml`，那只是打包时被裁掉的死资源。
    """
    for folder, mono in (("mipmap-anydpi-v26", False), ("mipmap-anydpi-v33", True)):
        out_dir = os.path.join(ICONS, folder)
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, "ic_launcher.xml")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n')
            f.write('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n')
            f.write('  <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n')
            f.write('  <background android:drawable="@color/ic_launcher_background"/>\n')
            if mono:
                # Android 13+ 主题图标：前景本身是「纯白 + alpha」，系统按 alpha 着色
                f.write('  <monochrome android:drawable="@mipmap/ic_launcher_foreground"/>\n')
            f.write("</adaptive-icon>\n")
        print("adaptive  ", path)


def verify_ratios() -> None:
    """自检：各候选比例在圆形 / 方圆角遮罩下被切掉的像素占比。"""
    size = DENSITIES["mipmap-xxxhdpi"]
    visible = size * 72 / 108  # 启动器可见区
    circles = []
    for ratio in (0.46, 0.48, 0.50, 0.52):
        img = render_foreground(size, ratio)
        alpha = img.split()[3]
        total = sum(alpha.getdata())
        for shape in ("circle", "squircle"):
            m = Image.new("L", (size, size), 0)
            d = ImageDraw.Draw(m)
            if shape == "circle":
                d.ellipse([(size - visible) / 2, (size - visible) / 2, (size + visible) / 2, (size + visible) / 2], fill=255)
            else:
                pad = (size - visible) / 2
                d.rounded_rectangle([pad, pad, size - pad, size - pad], radius=round(visible * 0.22), fill=255)
            kept = sum(a & v for a, v in zip(alpha.getdata(), m.getdata()))
            loss = (total - kept) / total * 100 if total else 0
            circles.append(f"ratio={ratio:.2f} {shape:<8} 裁切 {loss:.2f}%")
    print("遮罩自检（108dp 可见区 72dp）：")
    for line in circles:
        print("  " + line)


if __name__ == "__main__":
    if not os.path.isdir(ICONS):
        sys.exit(f"icons 目录不存在：{ICONS}（先跑 tauri icon）")
    write_foreground()
    write_legacy()
    write_background()
    write_adaptive_xml()
    verify_ratios()
    print("done")
