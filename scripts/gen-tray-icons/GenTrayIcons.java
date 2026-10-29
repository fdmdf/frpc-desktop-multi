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
 * ## 唯一生成源
 * 改图标一律改本文件头部的**几何常量**，然后：
 *   java scripts/gen-tray-icons/GenTrayIcons.java . --check
 *   java scripts/gen-tray-icons/GenTrayIcons.java . --sync-svg   # 把图标 -a 同步回设计稿
 * `desktopApp/icon.svg` 由 --sync-svg 从常量反向生成，**禁止手改**；
 * `--check` 会比对 SVG 与常量是否同步，漂了就报 FAIL。
 *
 * 用法：
 *   java scripts/gen-tray-icons/GenTrayIcons.java <仓库根目录> [--preview|--check|--sync-svg]
 * 产物：
 *   desktopApp/src/main/resources/tray/tray-<n>.png        彩色透明（Windows / Linux 托盘）
 *   desktopApp/src/main/resources/tray/tray-mac.png        macOS 模板图 @1x（22px，纯黑 + alpha）
 *   desktopApp/src/main/resources/tray/tray-mac@2x.png     macOS 模板图 @2x（44px）
 *   desktopApp/src/main/resources/icon/icon-<n>.png        窗口 / 任务栏多尺寸（见下）
 *   desktopApp/src/main/resources/icon.png                 512 带 alpha（向后兼容：
 *                                                          linux iconFile 与 AppIcons 的兜底路径）
 *   desktopApp/icon.ico                                    多尺寸 ICO（16..256）
 *   desktopApp/icon.svg                                    --sync-svg 时反向生成
 *   .build/icon-preview-{dark,light}.png                   深浅背景合成预览（--preview / --check 时输出）
 *
 * ## 为什么窗口图标要单独出一套多尺寸
 * 以前窗口只有一张 512，AWT 按场景把它缩到 16/20/24/32 —— 缩出来的图既有插值模糊，
 * 细节也会消失（用户看到的就是"标题栏/任务栏发虚"）。AWT 只有拿到**多尺寸列表**
 * （Window.iconImages）才会按场景挑最接近的原图，所以这里为每个尺寸单独绘制。
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
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class GenTrayIcons {

    /**
     * 彩色托盘图标尺寸：覆盖 Windows 96/120/144/192/240/288/384 DPI 与 Linux 22/24 系列。
     * 必须与 desktopApp/.../ui/AppIcons.kt 的 COLOR_SIZES 一致（改一边要同步另一边）。
     */
    static final int[] COLOR_SIZES = {16, 20, 22, 24, 32, 40, 48, 64, 128, 256};
    /**
     * 窗口 / 任务栏图标尺寸：交给 Window.iconImages，让 AWT 按场景挑原图而不是缩放 512。
     * 必须与 desktopApp/.../ui/AppIcons.kt 的 WINDOW_SIZES 一致（改一边要同步另一边）。
     */
    static final int[] WINDOW_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256, 512};
    /** macOS 菜单栏模板图：@1x=22pt, @2x=44px */
    static final int MAC_1X = 22, MAC_2X = 44;
    /** ICO 内嵌尺寸（Windows Explorer / 任务栏各视图按需取用，避免缩放发虚） */
    static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    // ---------- 几何常量（单位：画布边长的比例） ----------
    // 这里是整张图标的唯一真源：所有尺寸的 PNG 与 desktopApp/icon.svg 都由它派生。
    // 数值是「占边长多少」，所以把 size 乘上去就能得到任何画布上的绝对坐标。

    /** 圆角底板半径。注意 Java 的 RoundRectangle2D 要的是**圆角直径**，乘 2 才是这个值对应的参数 */
    static final float PLATE_R = 0.2266f;
    /** 渐变底板两端色 */
    static final int GRAD_START = 0x1E88E5, GRAD_END = 0x0B57D0;

    /** 拱门左柱 x / 右柱 x */
    static final float ARCH_L = 0.293f, ARCH_R = 0.707f;
    /** 柱顶所在的水平线（也是半圆的圆心线） / 柱底 */
    static final float ARCH_TOP_Y = 0.520f, ARCH_BASE_Y = 0.714f;
    /** 拱门笔画宽 */
    static final float ARCH_W = 0.066f;

    /** 数据流（圆点 + 箭头）所在的水平线 */
    static final float FLOW_Y = 0.605f;
    /** 源点圆心 x / 半径 */
    static final float DOT_CX = 0.383f, DOT_R = 0.030f;
    /** 箭头横线起点 / 终点（终点也是箭尖的基准 x） */
    static final float ARROW_X0 = 0.422f, ARROW_X1 = 0.559f;
    /** 横线笔画宽 / 箭尖长度 / 箭尖半高 */
    static final float ARROW_W = 0.043f, ARROW_TIP = 0.078f, ARROW_HALF = 0.047f;

    /**
     * 小尺寸时已有元素的最大放大系数（取值见 [smallBoost]）。
     * 16px 上按比例画出来的圆点直径不到 1px，降采样后 alpha 会掉到看不见，
     * 于是托盘上又变成"和主窗口不是同一个东西" —— 这正是本轮要修的现象。
     */
    static final float MAX_SMALL_BOOST = 1.20f;
    /** 超过这个尺寸就完全按原比例画（多了也不需要：>=48 每个元素都有好几个像素宽） */
    static final int BOOST_OFF_SIZE = 48;

    /** 反向生成 icon.svg 用的画布边长（同时也是向后兼容的那张 icon.png 的尺寸） */
    static final int SVG_SIZE = 512;

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        String mode = args.length > 1 ? args[1] : "";
        boolean check = "--check".equals(mode);
        boolean preview = check || "--preview".equals(mode);
        boolean syncSvg = "--sync-svg".equals(mode);
        File trayDir = new File(root, "desktopApp/src/main/resources/tray");
        File iconDir = new File(root, "desktopApp/src/main/resources/icon");
        File resDir = new File(root, "desktopApp/src/main/resources");
        File svg = new File(root, "desktopApp/icon.svg");

        trayDir.mkdirs();
        iconDir.mkdirs();
        for (int n : COLOR_SIZES) {
            write(drawColor(n), new File(trayDir, "tray-" + n + ".png"));
        }
        write(drawTemplate(MAC_1X), new File(trayDir, "tray-mac.png"));
        write(drawTemplate(MAC_2X), new File(trayDir, "tray-mac@2x.png"));

        // 窗口 / 任务栏：每个尺寸单独绘制。AWT 只有拿到多尺寸列表才会按场景选原图，
        // 给单张 512 让它自己缩，缩出来的就是一根棍加两个模糊的点。
        for (int n : WINDOW_SIZES) {
            write(drawColor(n), new File(iconDir, "icon-" + n + ".png"));
        }
        // 512 单张保留：build.gradle.kts 的 linux iconFile 与 AppIcons 的兜底路径还在用它
        write(drawColor(SVG_SIZE), new File(resDir, "icon.png"));

        if (syncSvg) writeSvg(svg);

        Map<Integer, byte[]> icoPng = new LinkedHashMap<>();
        for (int n : ICO_SIZES) { icoPng.put(n, pngBytes(drawColor(n))); }
        writeIco(icoPng, new File(root, "desktopApp/icon.ico"));

        if (preview) {
            File previewDir = new File(root, ".build");
            previewDir.mkdirs();
            write(previewSheet(false), new File(previewDir, "icon-preview-light.png"));
            write(previewSheet(true), new File(previewDir, "icon-preview-dark.png"));
            System.out.println("  -> .build/icon-preview-{light,dark}.png（深浅背景放大对照图）");
        }

        int failures = 0;
        if (check) {
            for (int n : COLOR_SIZES) { failures += checkPng(new File(trayDir, "tray-" + n + ".png")); }
            failures += checkPng(new File(trayDir, "tray-mac.png"));
            failures += checkPng(new File(trayDir, "tray-mac@2x.png"));
            for (int n : WINDOW_SIZES) { failures += checkPng(new File(iconDir, "icon-" + n + ".png")); }
            failures += checkPng(new File(resDir, "icon.png"));
            failures += checkSvg(svg);
            System.out.println((failures == 0 ? "check: OK（全部产物 + SVG 与常量同步）"
                : "check: " + failures + " 项 FAIL"));
        }
        System.out.println("done");
        if (failures > 0) System.exit(1);
    }

    /**
     * 白边自查：透明通道是否存在、四角是否完全透明、最外 2px 有没有近白像素。
     * 三项全过才算干净（旧 icon.png 在第一步就挂：color type 2，压根没有 alpha）。
     */
    static int checkPng(File f) throws Exception {
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
        return ok ? 0 : 1;
    }

    /**
     * SVG 与常量是否同步：把期望文本与磁盘上的 icon.svg 逐字节比。
     * 漂了说明有人手改了 SVG（或改了常量没重新同步），跑一次 --sync-svg 即可。
     */
    static int checkSvg(File f) throws Exception {
        if (!f.isFile()) {
            System.out.printf("  [%s] %-28s 文件不存在%n", "FAIL", f.getName());
            return 1;
        }
        String actual = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
            .replace("\r\n", "\n").trim();
        boolean ok = svgText().trim().equals(actual);
        System.out.printf("  [%s] %-28s 与几何常量%s%n", ok ? "PASS" : "FAIL", f.getName(),
            ok ? "同步" : "不同步（跑 --sync-svg 重写）");
        return ok ? 0 : 1;
    }

    // ---------- 绘制 ----------

    /** 彩色版：蓝色渐变圆角底板 + 白色隧道拱门与数据流（源点 + 箭头） */
    static BufferedImage drawColor(int size) {
        return downsampled(size, false);
    }

    /**
     * macOS 模板图：alpha 决定形状、颜色被系统忽略（规范是黑色）。
     * 做法是「底板减去字形」的镂空剪影，系统按深浅色自动反色。
     */
    static BufferedImage drawTemplate(int size) {
        return downsampled(size, true);
    }

    /**
     * 先在 8 倍画布上画矢量图形，再面积平均降到目标尺寸。
     *
     * 不直接在小画布上画，是因为 Java2D 在 16/20/24px 上填充圆角矩形会产生「抗锯齿过覆盖」：
     * 圆角外的角落会被涂上 36% / 21% / 6% 的底色（32px 起才是 0），托盘上就变成方块感 + 半透明雾点。
     * 大图绘制没有这个问题（512px 角落 alpha=0），降采样后角落依然干净，边缘还是平滑的。
     */
    static BufferedImage downsampled(int size, boolean template) {
        int superSize = Math.min(SVG_SIZE, size * 8);
        BufferedImage big = render(superSize, smallBoost(size), template);
        if (superSize == size) { return big; }
        return areaAverage(big, size);
    }

    /**
     * 小尺寸时把**已有笔画 / 已有圆点**按比例放大一点点。
     *
     * 为什么需要：目标画布只有 size 个像素，圆点半径 DOT_R x 16 只有 0.48px，
     * 面积平均之后 alpha 掉到 100 以下，肉眼看着就是"没这个点" —— 于是 16px 又变成
     * 另一张图。这里只放大已有元素，**不增删任何元素**（增删元素 = 换一个图形，
     * 那正是要修的问题），所以 16px 与 512px 仍然是同一个图案。
     * >= [BOOST_OFF_SIZE] 一律 1.0。
     */
    static float smallBoost(int size) {
        if (size >= BOOST_OFF_SIZE) return 1f;
        return 1f + (BOOST_OFF_SIZE - size) * (MAX_SMALL_BOOST - 1f) / BOOST_OFF_SIZE;
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

    static BufferedImage render(int size, float boost, boolean template) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = setup(img);
        float s = size;
        if (template) {
            Area a = new Area(plate(s));
            a.subtract(new Area(glyph(s, boost)));
            g.setComposite(AlphaComposite.Src); // 只写形状，不与底色混合
            g.setColor(Color.BLACK);
            g.fill(a);
        } else {
            Paint grad = new GradientPaint(0, 0, new Color(GRAD_START), s, s, new Color(GRAD_END));
            g.setPaint(grad);
            g.fill(plate(s));
            g.setPaint(Color.WHITE);
            g.fill(glyph(s, boost));
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
     * SVG 里 rx = PLATE_R x 512 是半径，换算成 Java 的 arcWidth 要乘 2。
     * 之前直接传 0.23 当半径用，圆角只有原设计的一半，角落被底板填满（托盘上就是方块感）。
     */
    static RoundRectangle2D plate(float s) {
        float d = s * PLATE_R * 2f;
        return new RoundRectangle2D.Float(0, 0, s, s, d, d);
    }

    /**
     * 白色字形：隧道拱门 + 数据流（源点圆点 + 向右的箭头）。
     *
     * **大小尺寸必须画同一个图形**（这条约定是本轮修的线上问题留下的）：
     * 以前 <=24px 会走另一套分支 —— 删掉源点圆点、箭头换成居中的实心三角，
     * 于是 Windows 100% DPI 的托盘（取 tray-16.png）和主窗口（取 icon.png 512）
     * 画的压根不是同一个东西，用户报的就是"托盘图标跟程序图标不一样"。
     *
     * 所以：
     * - 只有一组元素（拱门 + 圆点 + 横线 + 箭尖），任何尺寸都不许增删元素；
     * - 小尺寸唯一的区别是 [smallBoost] 把**已有**笔画和圆点按比例放大一点点，
     *   纯粹是为了在 16px 上还看得见，不是另一套画法；
     * - 所有元素的位置都是「占边长的比例」，缩放天生就是等比的。
     */
    static Area glyph(float s, float boost) {
        Area a = new Area();
        float archL = s * ARCH_L, archR = s * ARCH_R;
        float baseY = s * ARCH_BASE_Y, topY = s * ARCH_TOP_Y;
        float cy = s * FLOW_Y;
        float archW = s * ARCH_W * boost;
        float arrowW = s * ARROW_W * boost;
        float r = (archR - archL) / 2f;

        // 拱门：左柱 → 半圆 → 右柱
        Path2D arch = new Path2D.Float();
        arch.moveTo(archL, baseY);
        arch.lineTo(archL, topY);
        // 必须是 (180, -180)：从左柱顶出发、经 12 点方向到右柱顶，这样后面的 lineTo 才接得上右柱。
        // 用 (0, ±180) 会从左柱顶先拉一条线到右端；用 (180, +180) 画出来是下半圆。
        arch.append(new Arc2D.Float(archL, topY - r, archR - archL, r * 2f, 180f, -180f, Arc2D.OPEN), true);
        arch.lineTo(archR, baseY);
        a.add(new Area(new BasicStroke(archW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            .createStrokedShape(arch)));

        // 数据流横线：从左柱内侧向右穿出拱门
        float ax1 = s * ARROW_X1;
        Path2D line = new Path2D.Float();
        line.moveTo(s * ARROW_X0, cy);
        line.lineTo(ax1, cy);
        a.add(new Area(new BasicStroke(arrowW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            .createStrokedShape(line)));

        // 箭尖：以横线终点为基准，向后收 35%、向前伸 65%
        float tip = s * ARROW_TIP, half = s * ARROW_HALF;
        Path2D head = new Path2D.Float();
        head.moveTo(ax1 - tip * 0.35f, cy - half);
        head.lineTo(ax1 + tip * 0.65f, cy);
        head.lineTo(ax1 - tip * 0.35f, cy + half);
        head.closePath();
        a.add(new Area(head));

        // 源点：隧道左边那个"入口"。以前 <=24px 会被删掉，那样托盘就少了一个元素，
        // 和主窗口不是同一张图 —— 现在任何尺寸都画，只按 boost 微调半径
        float rr = s * DOT_R * boost;
        a.add(new Area(new Ellipse2D.Float(s * DOT_CX - rr, cy - rr, rr * 2f, rr * 2f)));
        return a;
    }

    // ---------- SVG 反向生成 ----------

    /**
     * 用上面的几何常量生成 icon.svg。
     *
     * 存在的意义：SVG 是给人看 / 给设计工具用的「稿」，Java2D 才是唯一生成源。
     * 以前两边各写一份，改一边忘另一边就会漂（第一轮比对时就发现箭尖的 x 差了 10px，
     * SVG 写 282/322，常量算出来是 272/312）。现在 SVG 由这里单向覆盖，
     * `--check` 会比对两者是否一致 —— 从此不可能漂移。
     */
    static String svgText() {
        float s = SVG_SIZE;
        int rx = round(PLATE_R * s);
        int l = round(ARCH_L * s), rr = round(ARCH_R * s);
        int baseY = round(ARCH_BASE_Y * s), topY = round(ARCH_TOP_Y * s);
        int cy = round(FLOW_Y * s);
        int archW = round(ARCH_W * s);
        int arcR = round((rr - l) / 2f), arcDx = rr - l;
        int cx = round(DOT_CX * s), dotR = round(DOT_R * s);
        int ax0 = round(ARROW_X0 * s), ax1 = round(ARROW_X1 * s);
        int arrowW = round(ARROW_W * s);
        int tipX = round(ARROW_TIP * s * 0.65f), tipBack = round(ARROW_TIP * s * 0.35f);
        int halfY = round(ARROW_HALF * s);
        return String.join("\n",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + SVG_SIZE + " " + SVG_SIZE
                + "\" width=\"" + SVG_SIZE + "\" height=\"" + SVG_SIZE + "\">",
            "  <!-- 本文件由 scripts/gen-tray-icons/GenTrayIcons.java --sync-svg 反向生成，请勿手改；",
            "       要改图形请改那份 Java 里的几何常量，再跑一次生成器 -->",
            "  <defs>",
            "    <linearGradient id=\"bg\" x1=\"0%\" y1=\"0%\" x2=\"100%\" y2=\"100%\">",
            "      <stop offset=\"0%\" stop-color=\"" + hex(GRAD_START) + "\"/>",
            "      <stop offset=\"100%\" stop-color=\"" + hex(GRAD_END) + "\"/>",
            "    </linearGradient>",
            "  </defs>",
            "",
            "  <!-- 圆角底板 -->",
            "  <rect width=\"" + SVG_SIZE + "\" height=\"" + SVG_SIZE + "\" rx=\"" + rx
                + "\" fill=\"url(#bg)\"/>",
            "",
            "  <!-- 隧道拱门（内网穿透）：左柱 -> 半圆 -> 右柱 -->",
            "  <path d=\"M" + l + " " + baseY + " V" + topY + " a" + arcR + " " + arcR
                + " 0 0 1 " + arcDx + " 0 V" + baseY + "\"",
            "        fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"" + archW
                + "\" stroke-linecap=\"round\"/>",
            "",
            "  <!-- 数据流：源点圆点 + 箭头 -->",
            "  <circle cx=\"" + cx + "\" cy=\"" + cy + "\" r=\"" + dotR + "\" fill=\"#FFFFFF\"/>",
            "  <path d=\"M" + ax0 + " " + cy + " H" + ax1 + "\" stroke=\"#FFFFFF\" stroke-width=\""
                + arrowW + "\" stroke-linecap=\"round\"/>",
            "  <path d=\"M" + (ax1 - tipBack) + " " + (cy - halfY) + " L" + (ax1 + tipX) + " " + cy
                + " L" + (ax1 - tipBack) + " " + (cy + halfY) + " Z\" fill=\"#FFFFFF\"/>",
            "</svg>",
            "");
    }

    static void writeSvg(File f) throws Exception {
        Files.write(f.toPath(), svgText().getBytes(StandardCharsets.UTF_8));
        System.out.println("  -> " + f.getPath() + "（由几何常量反向生成）");
    }

    static int round(float v) { return Math.round(v); }

    static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
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

    /**
     * 预览：把小尺寸那一档放大 6 倍（最近邻，不放插值），分别合成到浅色与深色托盘背景上。
     * 用最近邻是为了看清真实的像素分布 —— 插值会把 16px 的锯齿抹平，反而看不出问题。
     */
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
}
