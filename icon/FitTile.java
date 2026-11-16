import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Fits the supplied tile artwork (icon/source.jpg: a rounded white tile on grey) into a
 * 432x432 adaptive-icon layer so the tile's edges ARE the icon's edges.
 *
 * The tile square (measured: x/y 170..1830 of 2000, corner radius ~23 %) is scaled onto the
 * visible 72dp (middle 288 px). The launcher's mask may have tighter corners than the tile
 * (the system mask is a rounded square with 8 % corners), so pixels outside the tile's
 * rounded outline are filled with the colour just inside the outline, instead of the grey
 * backdrop and drop shadow.
 *
 *   java FitTile.java source.jpg out.png [x0 x1 radiusFraction]
 */
public class FitTile {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        double t0 = a.length > 2 ? Double.parseDouble(a[2]) : 170;
        double t1 = a.length > 3 ? Double.parseDouble(a[3]) : 1830;
        double rf = a.length > 4 ? Double.parseDouble(a[4]) : 0.23;
        double size = t1 - t0, rad = size * rf;
        double inset = size * 0.012;   // sample from just inside the outline (skips the shadowed lip)

        // 1) clean square of the tile: outside the rounded outline -> nearest colour inside it
        int n = (int) Math.round(size);
        BufferedImage tile = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double px = x + 0.5, py = y + 0.5;
            double[] q = clampInside(px, py, size, rad, inset);
            int sx = (int) Math.min(src.getWidth() - 1, Math.max(0, Math.round(t0 + q[0])));
            int sy = (int) Math.min(src.getHeight() - 1, Math.max(0, Math.round(t0 + q[1])));
            tile.setRGB(x, y, src.getRGB(sx, sy));
        }

        // 2) layer: the tile fills the visible 288 px; the hidden margin repeats the edge colour
        int out = 432, v0 = out / 6, vn = out * 2 / 3;
        BufferedImage small = scale(tile, vn);
        BufferedImage img = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < out; y++) for (int x = 0; x < out; x++) {
            int sx = Math.min(vn - 1, Math.max(0, x - v0)), sy = Math.min(vn - 1, Math.max(0, y - v0));
            img.setRGB(x, y, small.getRGB(sx, sy));
        }
        ImageIO.write(img, "png", new File(a[1]));
    }

    /** Point (px,py) in tile coords; if outside the rounded outline (shrunk by inset), move it onto it. */
    static double[] clampInside(double px, double py, double size, double rad, double inset) {
        double lo = inset, hi = size - inset, r = Math.max(1, rad - inset);
        double x = Math.min(hi, Math.max(lo, px)), y = Math.min(hi, Math.max(lo, py));
        double cx = x < lo + r ? lo + r : x > hi - r ? hi - r : x;
        double cy = y < lo + r ? lo + r : y > hi - r ? hi - r : y;
        double dx = x - cx, dy = y - cy, d = Math.hypot(dx, dy);
        if (d > r) { x = cx + dx / d * r; y = cy + dy / d * r; }
        return new double[]{x, y};
    }

    /** High-quality downscale by repeated halving, then a final bicubic step. */
    static BufferedImage scale(BufferedImage im, int target) {
        BufferedImage cur = im;
        while (cur.getWidth() / 2 >= target) cur = resize(cur, cur.getWidth() / 2);
        return resize(cur, target);
    }

    static BufferedImage resize(BufferedImage im, int s) {
        BufferedImage o = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(im, 0, 0, s, s, null);
        g.dispose();
        return o;
    }
}
