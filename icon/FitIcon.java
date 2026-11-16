import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Fits the supplied logo artwork (icon/source.jpg) into a 432x432 adaptive-icon layer so the
 * outer edges of the coloured swooshes line up with the visible icon edge.
 *
 * Only the middle 72 of 108 dp of an adaptive icon is visible, so the swooshes' bounding box
 * (found from strongly coloured / dark pixels) is scaled to FILL of that visible square and
 * centred; the rest of the layer is the artwork's own background colour.
 *
 *   java FitIcon.java source.jpg out.png preview.png [fill]
 */
public class FitIcon {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        double fill = a.length > 3 ? Double.parseDouble(a[3]) : 0.96;
        int w = src.getWidth(), h = src.getHeight();

        // bounding box of the artwork: pixels clearly different from the light grey background
        int x0 = w, y0 = h, x1 = 0, y1 = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = src.getRGB(x, y);
            int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
            int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            double sat = mx == 0 ? 0 : (mx - mn) / (double) mx;
            if (sat > 0.35 || mx < 120) {
                x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                y0 = Math.min(y0, y); y1 = Math.max(y1, y);
            }
        }
        System.out.printf("artwork box: x %d..%d  y %d..%d  (%dx%d)%n", x0, x1, y0, y1, x1 - x0, y1 - y0);

        // background colour: average of the image corners
        long[] acc = new long[3]; int n = 0;
        for (int[] c : new int[][]{{8, 8}, {w - 9, 8}, {8, h - 9}, {w - 9, h - 9}}) {
            for (int dy = 0; dy < 16; dy++) for (int dx = 0; dx < 16; dx++) {
                int p = src.getRGB(Math.min(w - 1, c[0] + dx - 8), Math.min(h - 1, c[1] + dy - 8));
                acc[0] += p >> 16 & 255; acc[1] += p >> 8 & 255; acc[2] += p & 255; n++;
            }
        }
        Color bg = new Color((int) (acc[0] / n), (int) (acc[1] / n), (int) (acc[2] / n));

        int out = 432;
        double visible = out * 2.0 / 3;                       // 288 px
        double box = Math.max(x1 - x0, y1 - y0);
        double scale = visible * fill / box;
        double cx = (x0 + x1) / 2.0, cy = (y0 + y1) / 2.0;
        double ox = out / 2.0 - cx * scale, oy = out / 2.0 - cy * scale;

        BufferedImage img = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(bg);
        g.fillRect(0, 0, out, out);
        // two-step downscale for quality
        BufferedImage half = new BufferedImage(w / 2, h / 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D hg = half.createGraphics();
        hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        hg.drawImage(src, 0, 0, w / 2, h / 2, null);
        hg.dispose();
        g.drawImage(half, (int) Math.round(ox), (int) Math.round(oy),
                (int) Math.round(w * scale), (int) Math.round(h * scale), null);
        g.dispose();
        ImageIO.write(img, "png", new File(a[1]));

        // preview: the visible 72dp, masked to a rounded square, big
        int v0 = out / 6, vn = (int) visible, big = 600;
        BufferedImage pv = new BufferedImage(big + 80, big + 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = pv.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        pg.setColor(new Color(0x1c1c20));
        pg.fillRect(0, 0, big + 80, big + 80);
        pg.setClip(new RoundRectangle2D.Double(40, 40, big, big, big * 0.45, big * 0.45));
        pg.drawImage(img.getSubimage(v0, v0, vn, vn), 40, 40, big, big, null);
        pg.dispose();
        ImageIO.write(pv, "png", new File(a[2]));
    }
}
