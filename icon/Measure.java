import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Prints luminance along the middle row/column near the edges, to locate the tile's edges. */
public class Measure {
    public static void main(String[] a) throws Exception {
        BufferedImage s = ImageIO.read(new File(a[0]));
        int w = s.getWidth(), mid = w / 2;
        StringBuilder row = new StringBuilder("row  x:lum  "), col = new StringBuilder("col  y:lum  ");
        for (int i = 120; i < 260; i += 4) row.append(i).append(':').append(lum(s, i, mid)).append(' ');
        row.append(" ...  ");
        for (int i = w - 260; i < w - 120; i += 4) row.append(i).append(':').append(lum(s, i, mid)).append(' ');
        for (int i = 120; i < 260; i += 4) col.append(i).append(':').append(lum(s, mid + 300, i)).append(' ');
        col.append(" ...  ");
        for (int i = w - 260; i < w - 120; i += 4) col.append(i).append(':').append(lum(s, mid - 300, i)).append(' ');
        System.out.println(row);
        System.out.println(col);
        // diagonal toward the top-left corner (no swoosh there)
        StringBuilder d = new StringBuilder("diag TL  ");
        for (int i = 150; i < 420; i += 6) d.append(i).append(':').append(lum(s, i, i)).append(' ');
        System.out.println(d);
    }

    static int lum(BufferedImage s, int x, int y) {
        int p = s.getRGB(x, y);
        return (int) (0.3 * (p >> 16 & 255) + 0.59 * (p >> 8 & 255) + 0.11 * (p & 255));
    }
}
