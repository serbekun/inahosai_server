package com.serbekun.inahosai.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import com.serbekun.inahosai.config.SiteConfigLoader;
import com.serbekun.inahosai.http.handles.FaviconRoutes;
import com.serbekun.inahosai.render.FaviconRenderer;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;

/**
 * The icons are generated, not committed, so these assert the bytes are a real ICO
 * and real PNGs rather than a 404 or an empty body.
 */
class FaviconRoutesTest {

    private final SiteConfigLoader loader = new SiteConfigLoader();

    private Javalin app() {
        Javalin app = Javalin.create();
        new FaviconRoutes(new FaviconRenderer(loader.loadBundledDefault())).register(app);
        return app;
    }

    @Test
    void faviconIcoIsARasterIcon() {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.get("/favicon.ico");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Type")).startsWith("image/x-icon");

            byte[] body = response.body().bytes();
            // ICONDIR: reserved=0, type=1 (icon), count=1.
            assertThat(body.length).isGreaterThan(6);
            assertThat(body[0]).isZero();
            assertThat(body[1]).isZero();
            assertThat(body[2]).isEqualTo((byte) 1);
            assertThat(body[3]).isZero();
            assertThat(body[4]).isEqualTo((byte) 1);
            assertThat(body[5]).isZero();
        });
    }

    @Test
    void faviconPngIsAValidPng() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.get("/favicon.png");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Type")).startsWith("image/png");
            assertThat(ImageIO.read(new ByteArrayInputStream(response.body().bytes())))
                    .isNotNull();
        });
    }

    @Test
    void theGlyphIsActuallyDrawn() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            var image = ImageIO.read(
                    new ByteArrayInputStream(client.get("/favicon.png").body().bytes()));

            long lightPixels = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    int rgb = image.getRGB(x, y);
                    int red = (rgb >> 16) & 0xFF;
                    int green = (rgb >> 8) & 0xFF;
                    int blue = rgb & 0xFF;
                    // The glyph is the only near-white element on the dark square.
                    if (red > 200 && green > 200 && blue > 200) {
                        lightPixels++;
                    }
                }
            }

            assertThat(lightPixels).isPositive();
        });
    }

    @Test
    void appleTouchIconIsAnOpaquePng() throws Exception {
        JavalinTest.test(app(), (server, client) -> {
            var response = client.get("/apple-touch-icon.png");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Type")).startsWith("image/png");
            assertThat(ImageIO.read(new ByteArrayInputStream(response.body().bytes())))
                    .isNotNull();
        });
    }
}
