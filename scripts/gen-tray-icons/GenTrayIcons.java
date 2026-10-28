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
 *   desktopApp/src/main/resources/icon.png                 512 带 alpha（窗口/任务栏图标）
 *   desktopApp/icon.ico                                    多尺寸 ICO（16..256）
 *   .build/icon-preview-{dark,light}.png                   深浅背景合成预览（--preview 时输出）
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
            System.out.println("  -> .build/icon-preview-{light,dark}.png（深浅背景放大对照图）");
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
}
