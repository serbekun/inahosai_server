package com.serbekun.inahosai.http.handles;

import com.serbekun.inahosai.render.FaviconRenderer;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Serves the raster site icons at the conventional root paths.
 *
 * <p>These paths are deliberately outside {@code /static/v0/}: browsers request
 * {@code /favicon.ico} on their own when a page does not name an icon, and Google
 * Search looks for a favicon linked from the home page. The bytes are rendered once
 * in the constructor, so a request is just a write.
 * </p>
 *
 * <p>The icons are derived from the config, so they only change on restart or reload
 * of the whole process, not per request.
 * </p>
 */
public class FaviconRoutes implements HttpHandler {

    /**
     * Icons change only when the school name does, so a long cache is safe; a restart
     * that changes them serves new bytes under the same path, which is the stable URL
     * Google asks for.
     */
    private static final String CACHE_CONTROL = "public, max-age=604800";

    private final byte[] ico;
    private final byte[] png;
    private final byte[] applePng;

    /**
     * Renders the icons from the config.
     *
     * @param renderer the pre-rendered favicon bytes
     */
    public FaviconRoutes(FaviconRenderer renderer) {
        this.ico = renderer.ico();
        this.png = renderer.png();
        this.applePng = renderer.applePng();
    }

    @Override
    public void register(Javalin svr) {
        svr.get("/favicon.ico", ctx -> serve(ctx, "image/x-icon", ico));
        svr.get("/favicon.png", ctx -> serve(ctx, "image/png", png));
        svr.get("/apple-touch-icon.png", ctx -> serve(ctx, "image/png", applePng));
        // Older iOS asks for this variant explicitly; answer it instead of a 404.
        svr.get("/apple-touch-icon-precomposed.png", ctx -> serve(ctx, "image/png", applePng));
    }

    private static void serve(Context ctx, String contentType, byte[] body) {
        ctx.header("Cache-Control", CACHE_CONTROL);
        ctx.contentType(contentType).result(body);
    }
}
