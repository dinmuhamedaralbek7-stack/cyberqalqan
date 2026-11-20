import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** CyberQalqan иконкаларын (PNG) жасау: java tools/Icon.java <папка> */
public class Icon {
    public static void main(String[] a) throws Exception {
        for (int s : new int[]{180, 192, 512}) {
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(s / 512.0, s / 512.0);
            g.setColor(new Color(0x05070d)); g.fillRect(0, 0, 512, 512);
            Path2D p = new Path2D.Double();
            p.moveTo(256, 70); p.lineTo(406, 126); p.lineTo(406, 238);
            p.curveTo(406, 332, 342, 414, 256, 444);
            p.curveTo(170, 414, 106, 332, 106, 238);
            p.lineTo(106, 126); p.closePath();
            g.setPaint(new GradientPaint(106, 70, new Color(0x3cf2c3), 406, 444, new Color(0x5b8cff)));
            g.fill(p);
            g.setColor(new Color(0x05070d));
            g.setStroke(new BasicStroke(34, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D c = new Path2D.Double(); c.moveTo(190, 258); c.lineTo(236, 304); c.lineTo(328, 208);
            g.draw(c);
            g.dispose();
            ImageIO.write(img, "png", new File(a[0], "icon-" + s + ".png"));
        }
    }
}
