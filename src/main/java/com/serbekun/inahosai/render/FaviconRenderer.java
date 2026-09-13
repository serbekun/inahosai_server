package com.serbekun.inahosai.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

import com.serbekun.inahosai.config.SiteConfig;

/**
 * Renders the site icon once, at startup, into the raster formats browsers and
 * crawlers actually ask for.
 *
 * <p>Google Search only reads raster favicons (BMP, GIF, ICO, PNG, JPEG, PPM,
 * TIFF), so the inline SVG in the page head is invisible to it. The same reason
 * applies to several older browsers and to iOS home screens. The SVG stays in the
 * head for the crisp modern tab icon; this class covers everyone the SVG does not.
 * </p>
 *
 * <p>Everything is generated from the configured glyph: no image asset is committed,
 * and changing the school name changes the icon on the next restart. Rendering is
 * pure off-screen Java2D, so it works headless.
 * </p>
 */
public final class FaviconRenderer {

    /** Size of the icon embedded in the ICO. Google recommends at least 48x48. */
    private static final int ICO_SIZE = 48;

    /** Size of the standalone PNG, large enough for an Android home screen. */
    private static final int PNG_SIZE = 192;

    /** Apple's preferred home-screen icon size. */
    private static final int APPLE_SIZE = 180;

    private static final Color BACKGROUND = new Color(0x0a0e16);
    private static final Color FOREGROUND = new Color(0xf3efe6);

    private final byte[] ico;
    private final byte[] png;
    private final byte[] applePng;

    /**
     * Renders every icon from a site config.
     *
     * @param config the config whose glyph the icon carries
     */
    public FaviconRenderer(SiteConfig config) {
        String glyph = glyph(config);
        // The tab icon and the ICO keep the rounded, transparent corners. The
        // apple-touch-icon is opaque and square: iOS applies its own mask, and a
        // transparent corner shows up black.
        this.ico = toIco(renderPng(glyph, ICO_SIZE, true), ICO_SIZE);
        this.png = renderPng(glyph, PNG_SIZE, true);
        this.applePng = renderPng(glyph, APPLE_SIZE, false);
    }

    /**
     * The single character carried by the icon.
     *
     * <p>The Latin initial comes first: an SVG or raster icon is drawn with a
     * fallback font, and a Latin letter survives a machine with no Japanese font
     * where a kanji would render as tofu. Falls back to the short name, then the
     * full Japanese name, so the icon is never empty on a configured site.
     * </p>
     *
     * @param config the site config
     * @return one code point, or an empty string when no name is set
     */
    public static String glyph(SiteConfig config) {
        String name = !config.school().nameLatin().isEmpty()
                ? config.school().nameLatin()
                : config.school().nameShort();
        if (name.isEmpty()) {
            name = config.school().nameJa();
        }
        if (name.isEmpty()) {
            return "";
        }
        // A single code point, so a surrogate pair is not split in half.
        return name.substring(0, name.offsetByCodePoints(0, 1));
    }

    /**
     * The icon wrapped in an ICO container.
     *
     * @return the {@code .ico} bytes
     */
    public byte[] ico() {
        return ico.clone();
    }

    /**
     * The standalone PNG icon.
     *
     * @return the PNG bytes
     */
    public byte[] png() {
        return png.clone();
    }

    /**
     * The opaque PNG icon for the iOS home screen.
     *
     * @return the PNG bytes
     */
    public byte[] applePng() {
        return applePng.clone();
    }

    private static byte[] renderPng(String glyph, int size, boolean rounded) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            graphics.setColor(BACKGROUND);
            if (rounded) {
                int radius = Math.round(size * 0.24f);
                graphics.fillRoundRect(0, 0, size, size, radius, radius);
            } else {
                graphics.fillRect(0, 0, size, size);
            }

            if (!glyph.isEmpty()) {
                graphics.setColor(FOREGROUND);
                Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(size * 0.64f);
                graphics.setFont(font);
                FontMetrics metrics = graphics.getFontMetrics();
                int x = (size - metrics.stringWidth(glyph)) / 2;
                int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
                graphics.drawString(glyph, x, y);
            }
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode the favicon PNG", e);
        }
        return out.toByteArray();
    }

    /**
     * Wraps PNG bytes in a single-entry ICO container.
     *
     * <p>Since Windows Vista the ICO format may carry a PNG verbatim instead of a
     * DIB, which is what every current browser expects. A one-pixel dimension is
     * stored as 0 in the directory entry, hence the {@code >= 256} guard even
     * though the icon is smaller.
     * </p>
     *
     * @param png  the square PNG to embed
     * @param size the side length of that PNG in pixels
     * @return the {@code .ico} bytes
     */
    private static byte[] toIco(byte[] png, int size) {
        int width = size >= 256 ? 0 : size;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLittleEndian(out, 0, 2); // reserved
        writeLittleEndian(out, 1, 2); // type: 1 = icon
        writeLittleEndian(out, 1, 2); // image count

        out.write(width); // width
        out.write(width); // height
        out.write(0); // palette size
        out.write(0); // reserved
        writeLittleEndian(out, 1, 2); // colour planes
        writeLittleEndian(out, 32, 2); // bits per pixel
        writeLittleEndian(out, png.length, 4);
        writeLittleEndian(out, 6 + 16, 4); // data offset

        out.writeBytes(png);
        return out.toByteArray();
    }

    private static void writeLittleEndian(ByteArrayOutputStream out, int value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((value >> (8 * i)) & 0xFF);
        }
    }
}
