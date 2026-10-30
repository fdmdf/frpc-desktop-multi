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
import java.io.ByteArrayInputStream;
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

    static final int[] COLOR_SIZES = {16, 20, 22, 24, 32, 40, 48, 64, 128, 256};

    static final int[] WINDOW_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256, 512};
    static final int MAC_1X = 22, MAC_2X = 44;
    static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    static final float PLATE_R = 0.2266f;
    static final int GRAD_START = 0x1E88E5, GRAD_END = 0x0B57D0;

    static final float ARCH_L = 0.293f, ARCH_R = 0.707f;
    static final float ARCH_TOP_Y = 0.520f, ARCH_BASE_Y = 0.714f;
    static final float ARCH_W = 0.066f;

    static final float FLOW_Y = 0.605f;
    static final float DOT_CX = 0.383f, DOT_R = 0.030f;
    static final float ARROW_X0 = 0.422f, ARROW_X1 = 0.559f;
    static final float ARROW_W = 0.043f, ARROW_TIP = 0.078f, ARROW_HALF = 0.047f;

    static final float MAX_SMALL_BOOST = 1.20f;
    static final int BOOST_OFF_SIZE = 48;

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

        for (int n : WINDOW_SIZES) {
            write(drawColor(n), new File(iconDir, "icon-" + n + ".png"));
        }
        write(drawColor(SVG_SIZE), new File(resDir, "icon.png"));

        if (syncSvg) writeSvg(svg);

        Map<Integer, byte[]> icoFrames = new LinkedHashMap<>();
        for (int n : ICO_SIZES) {
            icoFrames.put(n, n >= 256 ? pngBytes(drawColor(n)) : dibBytes(drawColor(n)));
        }
        writeIco(icoFrames, new File(root, "desktopApp/icon.ico"));

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
            failures += checkIco(new File(root, "desktopApp/icon.ico"));
            failures += checkSvg(svg);
            System.out.println((failures == 0 ? "check: OK（全部产物 + SVG 与常量同步）"
                : "check: " + failures + " 项 FAIL"));
        }
        System.out.println("done");
        if (failures > 0) System.exit(1);
    }

    static int checkIco(File f) throws Exception {
        byte[] d = Files.readAllBytes(f.toPath());
        ByteBuffer buf = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int n = buf.getShort(4) & 0xffff;
        int fail = 0;
        for (int k = 0; k < n; k++) {
            int base = 6 + 16 * k;
            int size = buf.get(base) & 0xff;
            if (size == 0) size = 256;
            int bpp = buf.getShort(base + 6) & 0xffff;
            int len = buf.getInt(base + 8);
            int off = buf.getInt(base + 12);
            boolean png = (d[off] & 0xff) == 0x89 && d[off + 1] == 'P';
            int cornerAlpha;
            if (png) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(d, off, len));
                cornerAlpha = (img.getRGB(0, 0) >>> 24) & 0xff;
            } else {
                int h = buf.getInt(off + 8) / 2;
                int rowBytes = size * 4;
                cornerAlpha = d[off + 40 + (h - 1) * rowBytes + 3] & 0xff;
            }
            boolean expectPng = size >= 256;
            boolean ok = png == expectPng && bpp == 32 && cornerAlpha == 0;
            if (!ok) fail++;
            System.out.printf("  [%s] %-14s %3dx%-3d %-3s 角落alpha=%d%n",
                ok ? "PASS" : "FAIL", f.getName(), size, size, png ? "PNG" : "DIB", cornerAlpha);
        }
        return fail;
    }

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

    static BufferedImage drawColor(int size) {
        return downsampled(size, false);
    }

    static BufferedImage drawTemplate(int size) {
        return downsampled(size, true);
    }

    static BufferedImage downsampled(int size, boolean template) {
        int superSize = Math.min(SVG_SIZE, size * 8);
        BufferedImage big = render(superSize, smallBoost(size), template);
        if (superSize == size) { return big; }
        return areaAverage(big, size);
    }

    static float smallBoost(int size) {
        if (size >= BOOST_OFF_SIZE) return 1f;
        return 1f + (BOOST_OFF_SIZE - size) * (MAX_SMALL_BOOST - 1f) / BOOST_OFF_SIZE;
    }

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
            g.setComposite(AlphaComposite.Src);
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

    static RoundRectangle2D plate(float s) {
        float d = s * PLATE_R * 2f;
        return new RoundRectangle2D.Float(0, 0, s, s, d, d);
    }

    static Area glyph(float s, float boost) {
        Area a = new Area();
        float archL = s * ARCH_L, archR = s * ARCH_R;
        float baseY = s * ARCH_BASE_Y, topY = s * ARCH_TOP_Y;
        float cy = s * FLOW_Y;
        float archW = s * ARCH_W * boost;
        float arrowW = s * ARROW_W * boost;
        float r = (archR - archL) / 2f;

        Path2D arch = new Path2D.Float();
        arch.moveTo(archL, baseY);
        arch.lineTo(archL, topY);
        arch.append(new Arc2D.Float(archL, topY - r, archR - archL, r * 2f, 180f, -180f, Arc2D.OPEN), true);
        arch.lineTo(archR, baseY);
        a.add(new Area(new BasicStroke(archW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            .createStrokedShape(arch)));

        float ax1 = s * ARROW_X1;
        Path2D line = new Path2D.Float();
        line.moveTo(s * ARROW_X0, cy);
        line.lineTo(ax1, cy);
        a.add(new Area(new BasicStroke(arrowW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            .createStrokedShape(line)));

        float tip = s * ARROW_TIP, half = s * ARROW_HALF;
        Path2D head = new Path2D.Float();
        head.moveTo(ax1 - tip * 0.35f, cy - half);
        head.lineTo(ax1 + tip * 0.65f, cy);
        head.lineTo(ax1 - tip * 0.35f, cy + half);
        head.closePath();
        a.add(new Area(head));

        float rr = s * DOT_R * boost;
        a.add(new Area(new Ellipse2D.Float(s * DOT_CX - rr, cy - rr, rr * 2f, rr * 2f)));
        return a;
    }

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

    static byte[] dibBytes(BufferedImage img) {
        BufferedImage src = img;
        if (src.getType() != BufferedImage.TYPE_INT_ARGB) {
            BufferedImage tmp = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tmp.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
            src = tmp;
        }
        int w = src.getWidth(), h = src.getHeight();
        int rowBytes = w * 4;
        int maskRow = ((w + 31) / 32) * 4;
        int maskSize = maskRow * h;
        ByteBuffer buf = ByteBuffer.allocate(40 + rowBytes * h + maskSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(40);
        buf.putInt(w);
        buf.putInt(h * 2);
        buf.putShort((short) 1);
        buf.putShort((short) 32);
        buf.putInt(0);
        buf.putInt(rowBytes * h);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                buf.put((byte) (argb & 0xff));
                buf.put((byte) ((argb >>> 8) & 0xff));
                buf.put((byte) ((argb >>> 16) & 0xff));
                buf.put((byte) ((argb >>> 24) & 0xff));
            }
        }
        byte[] mask = new byte[maskSize];
        for (int y = 0; y < h; y++) {
            int row = (h - 1 - y) * maskRow;
            for (int x = 0; x < w; x++) {
                if ((src.getRGB(x, y) >>> 24) == 0) {
                    mask[row + (x >> 3)] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        buf.put(mask);
        return buf.array();
    }

    static void writeIco(Map<Integer, byte[]> images, File f) throws Exception {
        int n = images.size();
        ByteBuffer dir = ByteBuffer.allocate(6 + 16 * n).order(ByteOrder.LITTLE_ENDIAN);
        dir.putShort((short) 0);
        dir.putShort((short) 1);
        dir.putShort((short) n);
        int offset = 6 + 16 * n;
        for (Map.Entry<Integer, byte[]> e : images.entrySet()) {
            int size = e.getKey();
            byte[] data = e.getValue();
            dir.put(size >= 256 ? 0 : (byte) size);
            dir.put(size >= 256 ? 0 : (byte) size);
            dir.put((byte) 0);
            dir.put((byte) 0);
            dir.putShort((short) 1);
            dir.putShort((short) 32);
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
