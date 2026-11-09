// 品牌资源生成器：以 app/src/main/assets/logo.png 为唯一真源，派生启动图标与 TV 横幅。
// 运行（JDK 11+，无需任何第三方库）：
//   java tools/make_logo.java
//   java tools/make_logo.java D:/VS Code/QlockOnTV
// 说明：旧 tools/make_icons.js 是纯代码画的书本图标，已废弃，请勿再运行，否则会覆盖本脚本产物。

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class make_logo {

    private static Path root;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path srcPath = root.resolve("app/src/main/assets/logo.png");
        BufferedImage raw = ImageIO.read(srcPath.toFile());
        if (raw == null) throw new IOException("读不到源图：" + srcPath);
        System.out.println("源图 " + raw.getWidth() + "x" + raw.getHeight() + "  " + srcPath);

        // 原图四角带紫色闪光颗粒，桌面上会被系统再裁一次形状，先去掉四角、留透明圆角
        BufferedImage logo = roundCorners(raw);

        // 1) 启动图标（沿用原来的 5 档密度与文件名，Manifest 无需改动）
        String[] iconDirs = {
            "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"
        };
        int[] iconSizes = {48, 72, 96, 144, 192};
        for (int i = 0; i < iconDirs.length; i++) {
            int s = iconSizes[i];
            Path out = root.resolve("app/src/main/res/" + iconDirs[i] + "/ic_launcher.png");
            write(resize(logo, s, s), out, iconDirs[i] + "/ic_launcher.png " + s + "x" + s);
        }

        // 2) Android TV 启动器横幅（android:banner），16:9，多密度
        //    官方要求 xhdpi 下 320x180dp，这里给 640x360px 让大屏依然清晰
        String[] bannerDirs = {"drawable-hdpi", "drawable-xhdpi", "drawable-xxhdpi"};
        int[][] bannerSizes = {{480, 270}, {640, 360}, {960, 540}};
        for (int i = 0; i < bannerDirs.length; i++) {
            int w = bannerSizes[i][0], h = bannerSizes[i][1];
            Path out = root.resolve("app/src/main/res/" + bannerDirs[i] + "/banner.png");
            write(drawBanner(logo, w, h), out, bannerDirs[i] + "/banner.png " + w + "x" + h);
        }

        // 3) 旧的纯色 layer-list 横幅不再使用（同名资源会冲突）
        Path legacy = root.resolve("app/src/main/res/drawable/banner.xml");
        if (Files.deleteIfExists(legacy)) {
            System.out.println("removed drawable/banner.xml（已由 banner.png 取代）");
        }
        System.out.println("完成。改 logo 只需替换 assets/logo.png 后重跑本脚本。");
    }

    /** 把圆角矩形之外的四角装饰裁成透明，避免桌面图标出现紫色噪点角 */
    private static BufferedImage roundCorners(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        float r = Math.min(w, h) * 0.225f;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(new RoundRectangle2D.Float(0, 0, w, h, r * 2, r * 2));
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** 高质量缩小；大跨度时逐次减半，避免一次性 BICUBIC 丢细节 */
    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        int cw = src.getWidth(), ch = src.getHeight();
        while (cw > w * 2 && ch > h * 2) {
            cw = Math.max(w, cw / 2);
            ch = Math.max(h, ch / 2);
            cur = scale(cur, cw, ch);
        }
        return cur.getWidth() == w && cur.getHeight() == h ? cur : scale(cur, w, h);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 深蓝渐变横幅：左侧品牌图 + 右侧中文名与副标题 */
    private static BufferedImage drawBanner(BufferedImage logo, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        g.setPaint(new GradientPaint(0, 0, new Color(0x0B, 0x12, 0x20),
            w, h, new Color(0x12, 0x30, 0x5A)));
        g.fillRect(0, 0, w, h);
        int bar = Math.max(2, Math.round(h * 0.012f));
        g.setPaint(new Color(0x4C, 0xC2, 0xFF));
        g.fillRect(0, h - bar, w, bar);

        int s = Math.round(h * 0.64f);
        int lx = Math.round(w * 0.045f);
        int ly = (h - s) / 2;
        g.drawImage(resize(logo, s, s), lx, ly, null);

        Font font = loadFont();
        int tx = lx + s + Math.round(w * 0.028f);
        g.setFont(font.deriveFont(Font.BOLD, h * 0.235f));
        g.setColor(new Color(0xEE, 0xF4, 0xFB));
        // 用 Unicode 转义写中文：JDK 17 在中文 Windows 上按 GBK 读源码，
        // 直接写 UTF-8 汉字会变成乱码字，转义后源码全 ASCII，任何平台都一致。
        g.drawString("\u9898\u9501", tx, Math.round(h * 0.48f));
        g.setFont(font.deriveFont(Font.PLAIN, h * 0.075f));
        g.setColor(new Color(0x9F, 0xB0, 0xC7));
        g.drawString("QlockOnTV \u00b7 \u7b54\u9898\u89e3\u9501",
            tx, Math.round(h * 0.67f));
        g.dispose();
        return out;
    }

    /** 优先加载系统中文字体，缺失时回退逻辑字体（仍能显示中文） */
    private static Font loadFont() {
        String[] candidates = {
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/msyhbd.ttc",
            "C:/Windows/Fonts/msyh.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/System/Library/Fonts/PingFang.ttc"
        };
        for (String p : candidates) {
            File f = new File(p);
            if (!f.isFile()) continue;
            try {
                Font font = Font.createFont(Font.TRUETYPE_FONT, f);
                if (font != null) return font;
            } catch (Throwable ignored) {
                // 某些 JDK 不接受 .ttc，继续试下一个
            }
        }
        return new Font("SansSerif", Font.BOLD, 16);
    }

    private static void write(BufferedImage img, Path out, String label) throws IOException {
        Files.createDirectories(out.getParent());
        if (!ImageIO.write(img, "png", out.toFile())) {
            throw new IOException("写 PNG 失败：" + out);
        }
        System.out.println("wrote " + label + "  " + out.toFile().length() + " bytes");
    }
}
