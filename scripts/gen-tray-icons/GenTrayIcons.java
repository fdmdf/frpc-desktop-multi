/*
 * 桌面端图标资源生成器（一次性工具，入库以便随时重跑）。
 *
 * 背景：原来的 desktopApp/src/main/resources/icon.png 是 512x512 的 **24 位 RGB（无 alpha）**，
 * 圆角底板外面是纯白不透明像素 —— 托盘上就是一圈白底/白边；而且只有 512 一张，
 * 运行时由 AWT 缩到 16/20/24，插值又把白色边缘混进图形（发虚 + 白 fringe）。
 *
 * 本生成器改为：**每个尺寸单独绘制**（不是缩放 512），画布一律 ARGB 全透明，
 * 形状用不透明颜色填充，抗锯齿只作用在形状边缘的 alpha 上 —— 不会掺进白色。
 *
 * 用法：
 *   java scripts/gen-tray-icons/GenTrayIcons.java <仓库根目录> [--preview|--check]
 * 产物：
 *   desktopApp/src/main/resources/tray/tray-<n>.png        彩色透明（Windows / Linux 托盘）
 *   desktopApp/src/main/resources/tray/tray-mac.png        macOS 模板图 @1x（22px，纯黑 + alpha）
 *   desktopApp/src/main/resources/tray/tray-mac@2x.png     macOS 模板图 @2x（44px）
 *   desktopApp/src/main/resources/tray/menu/<n>.png        菜单项字形 @1x（16px，纯黑 + alpha）
 *   desktopApp/src/main/resources/tray/menu/<n>@2x.png     菜单项字形 @2x（32px）
 *   desktopApp/src/main/resources/icon.png                 512 带 alpha（窗口/任务栏图标）
 *   desktopApp/icon.ico                                    多尺寸 ICO（16..256）
 *   .build/icon-preview-{dark,light}.png                   深浅背景合成预览（--preview 时输出）
 *
 * 菜单字形为什么是「预渲染 PNG」而不是运行时读 SVG：Java 标准库没有 SVG 渲染器，
 * ImageIO 也不认 .svg（详见 AppTray.kt 顶部的格式说明）。字形统一存成纯黑 + alpha 的
 * 蒙版，运行时按菜单前景色重新着色，深浅色主题各出一份颜色，不需要两套资源。
 */
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

public class GenTrayIcons {

    /** 彩色图标尺寸：覆盖 Windows 96/120/144/192/240/288/384 DPI 与 Linux 22/24 系列 */
    static final int[] COLOR_SIZES = {16, 20, 22, 24, 32, 40, 48, 64, 128, 256};
    /** macOS 菜单栏模板图：@1x=22pt, @2x=44px */
    static final int MAC_1X = 22, MAC_2X = 44;
    /** ICO 内嵌尺寸（Windows Explorer / 任务栏各视图按需取用，避免缩放发虚） */
    static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    /** 菜单项字形：@1x=16（96/120 DPI）、@2x=32（192+ DPI 与 HiDPI 屏） */
    static final int[] GLYPH_SIZES = {16, 32};

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        boolean preview = args.length > 1 && ("--preview".equals(args[1]) || "--check".equals(args[1]));
        boolean check = args.length > 1 && "--check".equals(args[1]);
        File trayDir = new File(root, "desktopApp/src/main/resources/tray");
        File resDir = new File(root, "desktopApp/src/main/resources");

        trayDir.mkdirs();
        for (int n : COLOR_SIZES) {
            write(drawColor(n), new File(trayDir, "tray-" + n + ".png"));
        }
        write(drawTemplate(MAC_1X), new File(trayDir, "tray-mac.png"));
        write(drawTemplate(MAC_2X), new File(trayDir, "tray-mac@2x.png"));

        File menuDir = new File(trayDir, "menu");
        menuDir.mkdirs();
        for (Glyph g : Glyph.values()) {
            for (int n : GLYPH_SIZES) {
                write(drawGlyph(g, n), new File(menuDir, g.file + (n > 16 ? "@2x" : "") + ".png"));
            }
        }

        // 窗口/任务栏用的大图：保留完整细节（拱门 + 圆点 + 箭头），透明背景
        write(drawColor(512), new File(resDir, "icon.png"));

        Map<Integer, byte[]> icoPng = new LinkedHashMap<>();
        for (int n : ICO_SIZES) { icoPng.put(n, pngBytes(drawColor(n))); }
        writeIco(icoPng, new File(root, "desktopApp/icon.ico"));

        if (preview || check) {
            File previewDir = new File(root, ".build");
            previewDir.mkdirs();
            write(previewSheet(false), new File(previewDir, "icon-preview-light.png"));
            write(previewSheet(true), new File(previewDir, "icon-preview-dark.png"));
            write(menuSheet(false), new File(previewDir, "menu-preview-light.png"));
            write(menuSheet(true), new File(previewDir, "menu-preview-dark.png"));
            System.out.println("  -> .build/icon-preview-{light,dark}.png（深浅背景放大对照图）");
            System.out.println("  -> .build/menu-preview-{light,dark}.png（菜单字形 × 深浅背景）");
        }
        if (check) {
            for (int n : COLOR_SIZES) { checkPng(new File(trayDir, "tray-" + n + ".png")); }
            checkPng(new File(trayDir, "tray-mac.png"));
            checkPng(new File(trayDir, "tray-mac@2x.png"));
            checkPng(new File(resDir, "icon.png"));
        }
        System.out.println("done");
    }

    /**
     * 白边自查：透明通道是否存在、四角是否完全透明、最外 2px 有没有近白像素。
     * 三项全过才算干净（旧 icon.png 在第一步就挂：color type 2，压根没有 alpha）。
     */
    static void checkPng(File f) throws Exception {
        BufferedImage img = ImageIO.read(f);
        int w = img.getWidth(), h = img.getHeight();
        boolean hasAlpha = img.getColorModel().hasAlpha();
        int[] corners = {
            img.getRGB(0, 0), img.getRGB(w - 1, 0), img.getRGB(0, h - 1), img.getRGB(w - 1, h - 1),
        };
        int cornerAlpha = 0;
        for (int c : corners) { cornerAlpha = Math.max(cornerAlpha, (c >>> 24) & 0xff); }
        int edgeWhite = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= 2 && y >= 2 && x < w - 2 && y < h - 2) continue;
                int argb = img.getRGB(x, y);
                if (((argb >>> 24) & 0xff) < 16) continue;
                int r = (argb >>> 16) & 0xff, g = (argb >>> 8) & 0xff, b = argb & 0xff;
                if (r > 200 && g > 200 && b > 200) { edgeWhite++; }
            }
        }
        boolean ok = hasAlpha && cornerAlpha == 0 && edgeWhite == 0;
        System.out.printf("  [%s] %-28s %dx%d  alpha=%b 角落alpha=%d 边缘近白=%d%n",
            ok ? "PASS" : "FAIL", f.getName(), w, h, hasAlpha, cornerAlpha, edgeWhite);
    }

    // ---------- 绘制 ----------

    /** 彩色版：蓝色渐变圆角底板 + 白色隧道拱门与箭头 */
    static BufferedImage drawColor(int size) {
        return downsampled(size, size <= 24, false);
    }

    /**
     * macOS 模板图：alpha 决定形状、颜色被系统忽略（规范是黑色）。
     * 做法是「底板减去字形」的镂空剪影，系统按深浅色自动反色。
     */
    static BufferedImage drawTemplate(int size) {
        return downsampled(size, size <= 24, true);
    }

    /**
     * 先在 8 倍画布上画矢量图形，再面积平均降到目标尺寸。
     *
     * 不直接在小画布上画，是因为 Java2D 在 16/20/24px 上填充圆角矩形会产生「抗锯齿过覆盖」：
     * 圆角外的角落会被涂上 36% / 21% / 6% 的底色（32px 起才是 0），托盘上就变成方块感 + 半透明雾点。
     * 大图绘制没有这个问题（512px 角落 alpha=0），降采样后角落依然干净，边缘还是平滑的。
     */
    static BufferedImage downsampled(int size, boolean simplified, boolean template) {
        int superSize = Math.min(512, size * 8);
        BufferedImage big = render(superSize, simplified, template);
        if (superSize == size) { return big; }
        return areaAverage(big, size);
    }

    /**
     * 面积平均降采样。**不能用 Image.SCALE_AREA_AVERAGING**：它对不预乘的 ARGB
     * 处理有 bug，透明像素的 RGB 会渗进结果（实测 16px 角落被染上 38% 的蓝色）。
     * 这里改为按 alpha 加权累加 RGB —— 全透明像素（a=0）对颜色没有贡献，角落就还是干净的 0。
     */
    static BufferedImage areaAverage(BufferedImage src, int size) {
        int sw = src.getWidth(), sh = src.getHeight();
        int[] sp = src.getRGB(0, 0, sw, sh, null, 0, sw);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int[] op = new int[size * size];
        for (int y = 0; y < size; y++) {
            int y0 = y * sh / size, y1 = Math.max(y0 + 1, (y + 1) * sh / size);
            for (int x = 0; x < size; x++) {
                int x0 = x * sw / size, x1 = Math.max(x0 + 1, (x + 1) * sw / size);
                long sa = 0, sr = 0, sg = 0, sb = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int argb = sp[yy * sw + xx];
                        int a = (argb >>> 24) & 0xff;
                        sa += a;
                        sr += ((argb >>> 16) & 0xff) * a;
                        sg += ((argb >>> 8) & 0xff) * a;
                        sb += (argb & 0xff) * a;
                    }
                }
                int oa = (int) (sa / ((long) (x1 - x0) * (y1 - y0)));
                int or = sa == 0 ? 0 : (int) (sr / sa);
                int og = sa == 0 ? 0 : (int) (sg / sa);
                int ob = sa == 0 ? 0 : (int) (sb / sa);
                op[y * size + x] = (oa << 24) | (or << 16) | (og << 8) | ob;
            }
        }
        out.setRGB(0, 0, size, size, op, 0, size);
        return out;
    }

    static BufferedImage render(int size, boolean simplified, boolean template) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = setup(img);
        float s = size;
        if (template) {
            Area a = new Area(plate(s));
            a.subtract(new Area(glyph(s, simplified)));
            g.setComposite(AlphaComposite.Src); // 只写形状，不与底色混合
            g.setColor(Color.BLACK);
            g.fill(a);
        } else {
            Paint grad = new GradientPaint(0, 0, new Color(0x1E, 0x88, 0xE5), s, s, new Color(0x0B, 0x57, 0xD0));
            g.setPaint(grad);
            g.fill(plate(s));
            g.setPaint(Color.WHITE);
            g.fill(glyph(s, simplified));
        }
        g.dispose();
        return img;
    }

    static Graphics2D setup(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }

    /**
     * 圆角底板。注意 Java 的 RoundRectangle2D 最后两个参数是**圆角直径**（不是半径）：
     * SVG 里 rx=116/512=0.2266 是半径，换算成 Java 的 arcWidth 要乘 2。
     * 之前直接传 0.23 当半径用，圆角只有原设计的一半，角落被底板填满（托盘上就是方块感）。
     */
    static RoundRectangle2D plate(float s) {
        float d = s * 0.4533f; // 直径 = 2 × 0.2266
        return new RoundRectangle2D.Float(0, 0, s, s, d, d);
    }

    /**
     * 白色字形：隧道拱门 + 数据流箭头。
     * 小尺寸（<=24）去掉源点圆点并加粗笔画，否则 16px 下会糊成一团。
     */
    static Area glyph(float s, boolean simplified) {
        Area a = new Area();
        float archW, arrowW = 0f, archL, archR, baseY, topY, cx, cy;
        if (simplified) {
            // 小尺寸：柱子更细、开口更大，只放一个居中的实心箭头（不再塞横线和圆点）。
            // 半径由 (archR-archL)/2 决定，所以 topY 必须 > r，否则拱顶会被顶出画布
            archW = s * 0.100f; archL = s * 0.160f; archR = s * 0.840f;
            baseY = s * 0.860f; topY = s * 0.500f; cy = s * 0.660f;
        } else {
            archW = s * 0.066f; arrowW = s * 0.043f;
            archL = s * 0.293f; archR = s * 0.707f; baseY = s * 0.714f; topY = s * 0.520f;
            cy = s * 0.605f;
        }
        cx = (archL + archR) / 2f;
        float r = (archR - archL) / 2f;

        // 拱门：左柱 → 半圆 → 右柱
        Path2D arch = new Path2D.Float();
        arch.moveTo(archL, baseY);
        arch.lineTo(archL, topY);
        // 必须是 (180, -180)：从左柱顶出发、经 12 点方向到右柱顶，这样后面的 lineTo 才接得上右柱。
        // 用 (0, ±180) 会从左柱顶先拉一条线到右端；用 (180, +180) 画出来是下半圆。
        arch.append(new java.awt.geom.Arc2D.Float(
            archL, topY - r, archR - archL, r * 2f, 180f, -180f, java.awt.geom.Arc2D.OPEN), true);
        arch.lineTo(archR, baseY);
        a.add(new Area(new BasicStroke(archW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            .createStrokedShape(arch)));

        if (simplified) {
            // 只有一个居中的实心箭头：左右各留 ~2px 空隙，不会和柱子粘连
            float halfW = s * 0.130f, halfH = s * 0.140f;
            Path2D head = new Path2D.Float();
            head.moveTo(cx - halfW, cy - halfH);
            head.lineTo(cx + halfW, cy);
            head.lineTo(cx - halfW, cy + halfH);
            head.closePath();
            a.add(new Area(head));
            return a;
        }

        // 大尺寸：横线 + 三角头，向右穿出拱门
        float ax1 = s * 0.559f;
        Path2D line = new Path2D.Float();
        line.moveTo(s * 0.422f, cy);
        line.lineTo(ax1, cy);
        a.add(new Area(new BasicStroke(arrowW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            .createStrokedShape(line)));

        float tip = s * 0.078f, half = s * 0.047f;
        Path2D head = new Path2D.Float();
        head.moveTo(ax1 - tip * 0.35f, cy - half);
        head.lineTo(ax1 + tip * 0.65f, cy);
        head.lineTo(ax1 - tip * 0.35f, cy + half);
        head.closePath();
        a.add(new Area(head));

        // 源点：只在 >=32 时出现，16/20/24 画了只会变成噪点
        float rr = s * 0.030f;
        a.add(new Area(new java.awt.geom.Ellipse2D.Float(
            s * 0.383f - rr, cy - rr, rr * 2f, rr * 2f)));
        return a;
    }

    // ---------- 菜单项字形 ----------

    /** 托盘菜单里每一项左侧的小图标；file 决定输出文件名 */
    enum Glyph {
        WINDOW("window"),   // 显示 / 隐藏主窗口
        PLAY("play"),       // 启动 frpc
        STOP("stop"),       // 停止 frpc
        RELOAD("reload"),   // 重载配置
        WEB("web"),         // 打开状态页
        GEAR("gear"),       // 设置
        UPDATE("update"),   // 检查更新
        INFO("info"),       // 关于
        QUIT("quit"),       // 退出
        BOX("box"),         // 未勾选（菜单里的开关项）
        BOX_CHECK("box-check"); // 已勾选

        final String file;
        Glyph(String file) { this.file = file; }
    }

    /**
     * 菜单字形：纯黑 + alpha 的蒙版（运行时按前景色着色），8 倍超采样后降采样。
     * 全部按 1.0 归一化坐标绘制，线宽统一 0.085~0.095，16px 下仍有 1.3~1.5px 实边。
     */
    static BufferedImage drawGlyph(Glyph kind, int size) {
        int ss = Math.min(256, size * 8);
        BufferedImage big = new BufferedImage(ss, ss, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = setup(big);
        g.setComposite(AlphaComposite.Src); // 只写形状，透明区保持 0
        g.setColor(Color.BLACK);
        float u = ss;
        switch (kind) {
            case WINDOW: {
                g.setStroke(new BasicStroke(u * 0.090f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // 窗口外框 + 标题栏分隔线（不画标题栏实心块：16px 下会和外框糊在一起）
                g.draw(new RoundRectangle2D.Float(
                    u * 0.14f, u * 0.20f, u * 0.72f, u * 0.60f, u * 0.16f, u * 0.16f));
                g.draw(new java.awt.geom.Line2D.Float(u * 0.14f, u * 0.40f, u * 0.86f, u * 0.40f));
                break;
            }
            case PLAY: {
                Path2D p = new Path2D.Float();
                p.moveTo(u * 0.32f, u * 0.18f);
                p.lineTo(u * 0.82f, u * 0.50f);
                p.lineTo(u * 0.32f, u * 0.82f);
                p.closePath();
                g.fill(p);
                break;
            }
            case STOP: {
                g.fill(new RoundRectangle2D.Float(
                    u * 0.24f, u * 0.24f, u * 0.52f, u * 0.52f, u * 0.14f, u * 0.14f));
                break;
            }
            case RELOAD: {
                float cx = u * 0.5f, cy = u * 0.5f, r = u * 0.32f;
                g.setStroke(new BasicStroke(u * 0.095f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // 缺口开在右下（280°~350°，70°），箭头接在弧的末端 280° 处，尖端朝缺口方向伸出。
                // 箭头必须比弧线明显更宽（half 0.10 > 描边半宽 0.0475），否则会被弧线吞掉看不出来
                g.draw(new java.awt.geom.Arc2D.Float(
                    cx - r, cy - r, r * 2f, r * 2f, 350f, 290f, java.awt.geom.Arc2D.OPEN));
                // 屏幕坐标：角度 θ 对应 (cx + r·cosθ, cy - r·sinθ)；θ 增大 = 视觉上逆时针
                float th = (float) Math.toRadians(280);
                // 半径外扩一点：箭头要明显探到圆弧之外，否则会和弧线叠在一起看不出来
                float rr = r + u * 0.040f;
                float px = (float) (cx + rr * Math.cos(th));
                float py = (float) (cy - rr * Math.sin(th));
                float tx = (float) -Math.sin(th), ty = (float) -Math.cos(th); // 切线（θ 增大方向）
                arrowHead(g, px, py, tx, ty, u * 0.13f, u * 0.100f);
                break;
            }
            case WEB: {
                g.setStroke(new BasicStroke(u * 0.080f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new java.awt.geom.Ellipse2D.Float(u * 0.13f, u * 0.13f, u * 0.74f, u * 0.74f));
                g.draw(new java.awt.geom.Line2D.Float(u * 0.13f, u * 0.50f, u * 0.87f, u * 0.50f));
                g.draw(new java.awt.geom.Ellipse2D.Float(u * 0.31f, u * 0.13f, u * 0.38f, u * 0.74f));
                break;
            }
            case GEAR: {
                // 齿轮 = 8 条朝外的粗短齿 + 一圈轮体 + 中间挖掉一个轮毂孔。
                // （早先用「虚线粗描边绕圆」画齿，实际出来是一圈断掉的散点，识别度很差）
                float cx = u * 0.5f, cy = u * 0.5f;
                g.setStroke(new BasicStroke(u * 0.130f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    g.draw(new java.awt.geom.Line2D.Float(
                        cx + ca * u * 0.24f, cy + sa * u * 0.24f,
                        cx + ca * u * 0.41f, cy + sa * u * 0.41f));
                }
                g.setStroke(new BasicStroke(u * 0.150f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                float r = u * 0.255f;
                g.draw(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));
                // 轮毂孔用 DstOut 挖：涂背景色会留下不透明像素，托盘上就是个白点
                g.setComposite(AlphaComposite.DstOut);
                float ir = u * 0.100f;
                g.fill(new java.awt.geom.Ellipse2D.Float(cx - ir, cy - ir, ir * 2f, ir * 2f));
                g.setComposite(AlphaComposite.Src);
                break;
            }
            case UPDATE: {
                g.setStroke(new BasicStroke(u * 0.090f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // 竖线 + 实心箭头（下载/更新）+ 底线托盘
                g.draw(new java.awt.geom.Line2D.Float(u * 0.5f, u * 0.20f, u * 0.5f, u * 0.66f));
                Path2D head = new Path2D.Float();
                head.moveTo(u * 0.5f, u * 0.82f);
                head.lineTo(u * 0.27f, u * 0.56f);
                head.lineTo(u * 0.73f, u * 0.56f);
                head.closePath();
                g.fill(head);
                g.draw(new java.awt.geom.Line2D.Float(u * 0.24f, u * 0.88f, u * 0.76f, u * 0.88f));
                break;
            }
            case INFO: {
                g.setStroke(new BasicStroke(u * 0.085f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new java.awt.geom.Ellipse2D.Float(u * 0.12f, u * 0.12f, u * 0.76f, u * 0.76f));
                g.fill(new java.awt.geom.Ellipse2D.Float(u * 0.44f, u * 0.25f, u * 0.12f, u * 0.12f));
                g.setStroke(new BasicStroke(u * 0.105f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new java.awt.geom.Line2D.Float(u * 0.5f, u * 0.46f, u * 0.5f, u * 0.72f));
                break;
            }
            case BOX:
            case BOX_CHECK: {
                // 开关项的勾选框：BOX 只画外框，BOX_CHECK 是实心框 + 用 DstOut 挖出的对勾
                // （对勾必须挖成透明，涂背景色会在深色菜单上留下一个方块）
                g.setStroke(new BasicStroke(u * 0.090f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                RoundRectangle2D box = new RoundRectangle2D.Float(
                    u * 0.15f, u * 0.15f, u * 0.70f, u * 0.70f, u * 0.20f, u * 0.20f);
                if (kind == Glyph.BOX_CHECK) {
                    g.fill(box);
                    g.setComposite(AlphaComposite.DstOut);
                    Path2D tick = new Path2D.Float();
                    tick.moveTo(u * 0.29f, u * 0.52f);
                    tick.lineTo(u * 0.43f, u * 0.67f);
                    tick.lineTo(u * 0.73f, u * 0.33f);
                    g.setStroke(new BasicStroke(u * 0.115f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(tick);
                    g.setComposite(AlphaComposite.Src);
                } else {
                    g.draw(box);
                }
                break;
            }
            case QUIT: {
                float cx = u * 0.5f, cy = u * 0.54f, r = u * 0.30f;
                g.setStroke(new BasicStroke(u * 0.095f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // 电源符号：缺口留在正下方（240°~300°），中间一根竖线
                g.draw(new java.awt.geom.Arc2D.Float(
                    cx - r, cy - r, r * 2f, r * 2f, 300f, 300f, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Line2D.Float(cx, cy - u * 0.04f, cx, u * 0.20f));
                break;
            }
        }
        g.dispose();
        return ss == size ? big : areaAverage(big, size);
    }

    /** 在 (px,py) 处沿 (tx,ty) 方向画一个实心箭头（tip 在前，底边在两侧） */
    static void arrowHead(Graphics2D g, float px, float py, float tx, float ty, float len, float half) {
        float nx = -ty, ny = tx; // 法线
        Path2D p = new Path2D.Float();
        p.moveTo(px + tx * len, py + ty * len);
        p.lineTo(px - tx * len * 0.2f + nx * half, py - ty * len * 0.2f + ny * half);
        p.lineTo(px - tx * len * 0.2f - nx * half, py - ty * len * 0.2f - ny * half);
        p.closePath();
        g.fill(p);
    }

    // ---------- 输出 ----------

    static void write(BufferedImage img, File f) throws Exception {
        f.getParentFile().mkdirs();
        if (!ImageIO.write(img, "png", f)) { throw new IllegalStateException("png writer missing: " + f); }
        System.out.println("  -> " + f.getPath() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    static byte[] pngBytes(BufferedImage img) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** 手写 ICO：ICONDIR + 若干 ICONDIRENTRY + PNG 数据（Vista 起支持 PNG 条目） */
    static void writeIco(Map<Integer, byte[]> images, File f) throws Exception {
        int n = images.size();
        ByteBuffer dir = ByteBuffer.allocate(6 + 16 * n).order(ByteOrder.LITTLE_ENDIAN);
        dir.putShort((short) 0);          // reserved
        dir.putShort((short) 1);          // type: icon
        dir.putShort((short) n);          // count
        int offset = 6 + 16 * n;
        for (Map.Entry<Integer, byte[]> e : images.entrySet()) {
            int size = e.getKey();
            byte[] data = e.getValue();
            dir.put(size >= 256 ? 0 : (byte) size);   // width
            dir.put(size >= 256 ? 0 : (byte) size);   // height
            dir.put((byte) 0);                        // color count
            dir.put((byte) 0);                        // reserved
            dir.putShort((short) 1);                  // planes
            dir.putShort((short) 32);                 // bit count
            dir.putInt(data.length);
            dir.putInt(offset);
            offset += data.length;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(dir.array());
            for (byte[] data : images.values()) { out.write(data); }
        }
        System.out.println("  -> " + f.getPath() + " (" + n + " sizes: "
            + images.keySet() + ", " + f.length() + " bytes)");
    }

    /** 预览：把 16/20/24/32/48 放大 6 倍，分别合成到浅色与深色托盘背景上 */
    static BufferedImage previewSheet(boolean dark) {
        int[] sizes = {16, 20, 24, 32, 48};
        int zoom = 6, pad = 10;
        int cell = 48 * zoom + pad * 2;
        BufferedImage sheet = new BufferedImage(cell * sizes.length, cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color bg = dark ? new Color(0x2B, 0x2B, 0x2B) : new Color(0xF3, 0xF3, 0xF3);
        for (int i = 0; i < sizes.length; i++) {
            int x = i * cell;
            g.setColor(bg);
            g.fillRect(x, 0, cell, cell);
            BufferedImage icon = drawColor(sizes[i]);
            int px = x + (cell - sizes[i] * zoom) / 2;
            int py = (cell - sizes[i] * zoom) / 2;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(icon, px, py, sizes[i] * zoom, sizes[i] * zoom, null);
        }
        g.dispose();
        return sheet;
    }

    /**
     * 菜单字形预览：一行 16px（放大 5 倍）+ 一行 32px（放大 3 倍），
     * 字形按「深色背景用浅色字形、浅色背景用深色字形」着色后合成，就是托盘菜单的实际观感。
     */
    static BufferedImage menuSheet(boolean dark) {
        Glyph[] glyphs = Glyph.values();
        int cols = 3, src = 32, zoom = 4, cell = src * zoom + 24;
        int rows = (glyphs.length + cols - 1) / cols;
        int w = cell * cols, h = cell * rows;
        BufferedImage sheet = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        Color bg = dark ? new Color(0x2B, 0x2B, 0x2B) : new Color(0xF7, 0xF7, 0xF7);
        Color fg = dark ? new Color(0xEE, 0xEE, 0xEE) : new Color(0x1F, 0x1F, 0x1F);
        g.setColor(bg);
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < glyphs.length; i++) {
            BufferedImage img = tint(drawGlyph(glyphs[i], src), fg);
            int x = (i % cols) * cell + 12, y = (i / cols) * cell + 12;
            g.drawImage(img, x, y, src * zoom, src * zoom, null);
        }
        g.dispose();
        return sheet;
    }

    /** 与 AppTray 运行时一致的着色：保留 alpha，RGB 换成前景色 */
    static BufferedImage tint(BufferedImage src, Color fg) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                out.setRGB(x, y, (a << 24) | (fg.getRed() << 16) | (fg.getGreen() << 8) | fg.getBlue());
            }
        }
        return out;
    }
}
