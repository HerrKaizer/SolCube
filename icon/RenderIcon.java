import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Suite's launcher icon (light). The mark is an "S" built from three bars joined by rounded
 * turns, one bar per section of the app. It fills the tile so its two open ends sit right at
 * the top-right and bottom-left corners, and from there the ink bleeds into those corners like
 * light through a prism: near-black -> navy -> blue -> cyan -> a thin pink edge, dispersed per
 * colour channel, running a little way along the rim. White glass tile with a soft inner bevel.
 *
 *   java RenderIcon.java <out.png> [preview.png]
 *
 * Output: a 432x432 adaptive-icon layer (108dp at xxxhdpi). Only the middle 72dp is visible
 * after the launcher's mask, so everything is laid out in visible coordinates v in [0,1],
 * mapped onto [1/6, 5/6] of the layer.
 */
public class RenderIcon {
    static final int W = 864;          // working size (2x the output)
    static final double V0 = 1.0 / 6, VS = 2.0 / 3;

    // glyph geometry (visible coords)
    static final double BAR = 0.14;                 // stroke width
    static final double TOP = 0.215, MID = 0.50, BOT = 0.785;
    static final double LEFT = 0.355, RIGHT = 0.645; // turn centres x
    static final double END_R = 1.02, END_L = -0.02;  // bars run out to the edge and dissolve there
    static final double FADE_A = 0.66, FADE_B = 0.92; // where the ink gives way to the bleed
    static final double R = (MID - TOP) / 2;         // turn radius

    static final double[] BASE = {0.957, 0.957, 0.965};
    static final double[] INK = {0.035, 0.035, 0.060};

    public static void main(String[] a) throws Exception {
        float[] glyph = glyphMask();

        // bleed source: the two bar ends, where the ink dissolves (see fade())
        float[] src = new float[W * W];
        float[] solid = new float[W * W];
        for (int y = 0; y < W; y++) for (int x = 0; x < W; x++) {
            int i = y * W + x;
            double f = fade(vx(x), vx(y));
            solid[i] = (float) (glyph[i] * f);
            src[i] = (float) (glyph[i] * (1 - f * 0.6));
        }
        // only the end zones feed the bleed
        for (int y = 0; y < W; y++) for (int x = 0; x < W; x++) {
            double vx = vx(x), vy = vx(y);
            double e = Math.max(endZone(vx, vy, true), endZone(vx, vy, false));
            src[y * W + x] *= (float) e;
        }

        // 1) per-channel smear outward from the ends toward the corners (slightly different
        //    lengths per channel = dispersion), plus a thin glow riding the rim near the corners
        double[] disp = {1.30, 1.25, 1.20};
        int steps = 70;
        float[][] field = new float[3][W * W];
        for (int y = 0; y < W; y++) {
            for (int x = 0; x < W; x++) {
                double vx = vx(x), vy = vx(y);
                double r = Math.hypot(vx - 0.5, vy - 0.5);
                double d = r < 1e-6 ? 0 : Math.abs((vx - 0.5) - (vy - 0.5)) / (r * Math.sqrt(2));
                double wq = smooth(0.5, 0.95, d);
                double rim = rimBand(vx, vy) * Math.pow(smooth(0.80, 1.0, d), 2) * smooth(0.42, 0.62, r);
                for (int c = 0; c < 3; c++) {
                    double best = 0;
                    if (wq > 0.001) {
                        for (int k = 0; k <= steps; k++) {
                            double t = (double) k / steps;
                            double s = 1 + (disp[c] - 1) * t;
                            double m = sample(src, 0.5 + (vx - 0.5) / s, 0.5 + (vy - 0.5) / s);
                            if (m > 0) best = Math.max(best, m * Math.pow(1 - t, 0.9));
                        }
                    }
                    field[c][y * W + x] = (float) (best * wq + rim * 0.38);
                }
            }
        }
        for (int c = 0; c < 3; c++) for (int pass = 0; pass < 3; pass++) field[c] = boxBlur(field[c], 4);

        float[] out = new float[W * W * 3];
        for (int y = 0; y < W; y++) for (int x = 0; x < W; x++) {
            int i = y * W + x;
            // glass tile: very slightly brighter at the top
            double shade = 0.012 * (0.5 - vx(y));
            for (int c = 0; c < 3; c++) {
                double b = BASE[c] + shade;
                out[i * 3 + c] = (float) spectrum(field[c][i] * 1.45, b)[c];
            }
        }

        // 2) the glyph itself, crisp, with a hair of colour fringe on its edges
        double off = 1.2;
        for (int y = 0; y < W; y++) for (int x = 0; x < W; x++) {
            int p = (y * W + x) * 3;
            double[] g = {samplePx(solid, x + off, y - off * 0.4), samplePx(solid, x, y), samplePx(solid, x - off, y + off * 0.4)};
            for (int c = 0; c < 3; c++) out[p + c] = (float) (out[p + c] * (1 - g[c]) + INK[c] * g[c]);
        }

        // 3) glass bevel: a soft grey rim and a fainter inner edge
        bevel(out, 0.012, 0.0045, 0.20);
        bevel(out, 0.060, 0.0030, 0.07);

        write(out, W / 2, new File(a[0]));
        if (a.length > 1) writePreview(out, new File(a[1]));
    }

    // ------------------------------------------------------------------ glyph

    static float[] glyphMask() {
        BufferedImage img = new BufferedImage(W, W, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        aa(g);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (BAR * W * VS), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));

        Path2D s = new Path2D.Double();
        s.moveTo(px(END_R), px(TOP));
        s.lineTo(px(LEFT), px(TOP));
        // turn down on the left: top -> left -> bottom
        s.append(new Arc2D.Double(px(LEFT - R), px(TOP), R * W * VS * 2, R * W * VS * 2, 90, 180, Arc2D.OPEN), true);
        s.lineTo(px(RIGHT), px(MID));
        // turn down on the right: top -> right -> bottom
        s.append(new Arc2D.Double(px(RIGHT - R), px(MID), R * W * VS * 2, R * W * VS * 2, 90, -180, Arc2D.OPEN), true);
        s.lineTo(px(END_L), px(BOT));
        g.draw(s);
        g.dispose();

        byte[] b = new byte[W * W];
        img.getRaster().getDataElements(0, 0, W, W, b);
        float[] f = new float[W * W];
        for (int i = 0; i < f.length; i++) f[i] = (b[i] & 0xff) / 255f;
        return f;
    }

    // ------------------------------------------------------------------ helpers

    static double vx(int p) { return ((p + 0.5) / W - V0) / VS; }

    /** 1 inside the solid glyph, falling to 0 along the outer part of the top-right / bottom-left bar. */
    static double fade(double vx, double vy) {
        double f = 1;
        if (vy < MID - R * 0.5) f = Math.min(f, 1 - smooth(FADE_A, FADE_B, vx));            // top bar, toward the right
        if (vy > MID + R * 0.5) f = Math.min(f, 1 - smooth(FADE_A, FADE_B, 1 - vx));        // bottom bar, toward the left
        return f;
    }

    /** Soft region around each dissolving bar end. */
    static double endZone(double vx, double vy, boolean topRight) {
        double cx = topRight ? 0.86 : 0.14, cy = topRight ? TOP : BOT;
        double dd = sq((vx - cx) * 0.8) + sq(vy - cy);
        return Math.exp(-dd / (2 * 0.085 * 0.085));
    }

    static double px(double v) { return (V0 + v * VS) * W; }

    static double sq(double v) { return v * v; }

    /** Signed distance to the visible tile's rounded edge (inset), as a thin glow band. */
    static double rimBand(double vx, double vy) {
        double d = tileDist(vx, vy, 0.03, 0.21);
        return Math.exp(-(d * d) / (2 * 0.011 * 0.011));
    }

    static double tileDist(double vx, double vy, double in, double rad) {
        double qx = Math.abs(vx - 0.5) - (0.5 - in - rad), qy = Math.abs(vy - 0.5) - (0.5 - in - rad);
        return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - rad;
    }

    static void bevel(float[] out, double in, double width, double strength) {
        for (int y = 0; y < W; y++) for (int x = 0; x < W; x++) {
            double d = tileDist(vx(x), vx(y), in, 0.23 - in);
            double a = Math.exp(-(d * d) / (2 * width * width)) * strength;
            int p = (y * W + x) * 3;
            for (int c = 0; c < 3; c++) out[p + c] = (float) (out[p + c] * (1 - a) + 0.62 * a);
        }
    }

    /** Intensity -> colour on the white tile: thin pink edge -> cyan -> blue -> navy -> ink. */
    static double[] spectrum(double i, double base) {
        double[][] st = {
            {0.00, base, base, base},
            {0.04, 0.98, 0.70, 0.78},
            {0.08, 0.93, 0.28, 0.45},
            {0.13, 0.45, 0.78, 1.00},
            {0.21, 0.08, 0.42, 0.98},
            {0.33, 0.06, 0.14, 0.70},
            {0.52, INK[0], INK[1], INK[2]},
        };
        if (i <= 0) return new double[]{base, base, base};
        for (int k = 1; k < st.length; k++) {
            if (i <= st[k][0]) {
                double t = (i - st[k - 1][0]) / (st[k][0] - st[k - 1][0]);
                t = t * t * (3 - 2 * t);
                return new double[]{
                    lerp(st[k - 1][1], st[k][1], t), lerp(st[k - 1][2], st[k][2], t), lerp(st[k - 1][3], st[k][3], t)};
            }
        }
        return INK.clone();
    }

    static float[] boxBlur(float[] src, int rad) {
        float[] tmp = new float[W * W], dst = new float[W * W];
        for (int y = 0; y < W; y++) {
            double s = 0;
            for (int x = -rad; x <= rad; x++) s += at(src, x, y);
            for (int x = 0; x < W; x++) {
                tmp[y * W + x] = (float) (s / (2 * rad + 1));
                s += at(src, x + rad + 1, y) - at(src, x - rad, y);
            }
        }
        for (int x = 0; x < W; x++) {
            double s = 0;
            for (int y = -rad; y <= rad; y++) s += at(tmp, x, y);
            for (int y = 0; y < W; y++) {
                dst[y * W + x] = (float) (s / (2 * rad + 1));
                s += at(tmp, x, y + rad + 1) - at(tmp, x, y - rad);
            }
        }
        return dst;
    }

    static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    static double sample(float[] m, double vx, double vy) { return samplePx(m, px(vx) - 0.5, px(vy) - 0.5); }

    static double samplePx(float[] m, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        return lerp(lerp(at(m, x0, y0), at(m, x0 + 1, y0), fx), lerp(at(m, x0, y0 + 1), at(m, x0 + 1, y0 + 1), fx), fy);
    }

    static double at(float[] m, int x, int y) {
        if (x < 0 || y < 0 || x >= W || y >= W) return 0;
        return m[y * W + x];
    }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    static int to8(double v) { return (int) Math.round(Math.max(0, Math.min(1, v)) * 255); }

    static void write(float[] o, int n, File f) throws Exception {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double[] c = new double[3];
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
                for (int k = 0; k < 3; k++) c[k] += o[((2 * y + dy) * W + 2 * x + dx) * 3 + k] / 4;
            img.setRGB(x, y, to8(c[0]) << 16 | to8(c[1]) << 8 | to8(c[2]));
        }
        ImageIO.write(img, "png", f);
    }

    /** What a launcher shows: the visible 72dp, masked to a rounded square, on a dark wallpaper. */
    static void writePreview(float[] o, File f) throws Exception {
        int a = (int) (W * V0), n = (int) (W * VS);
        BufferedImage img = new BufferedImage(n + 80, n + 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        aa(g);
        g.setColor(new Color(0x1c1c20));
        g.fillRect(0, 0, n + 80, n + 80);
        BufferedImage tile = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int p = ((y + a) * W + x + a) * 3;
            tile.setRGB(x, y, to8(o[p]) << 16 | to8(o[p + 1]) << 8 | to8(o[p + 2]));
        }
        g.setClip(new RoundRectangle2D.Double(40, 40, n, n, n * 0.45, n * 0.45));
        g.drawImage(tile, 40, 40, null);
        g.dispose();
        ImageIO.write(img, "png", f);
    }
}
