package com.serbekun.inahosai.http.handles;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.domain.http.dto.V0LiveTypeGetRes;

import io.javalin.Javalin;

/**
 * Handles {@code GET /api/v0/live/type/}.
 *
 * <p>Tells a client which live platform the site is configured for, so the page can pick
 * the right embed without guessing from the config file. The response is always one of
 * {@code "youtube"}, {@code "zoom"} or {@code "none"}: a platform whose payload is
 * missing counts as {@code "none"}, because there is nothing to render.</p>
 *
 * <pre><code>{
 *   "type": "youtube"
 * }</code></pre>
 */
public class V0LiveType implements HttpHandler {

    private final SiteConfig config;

    /**
     * Creates the handler.
     *
     * @param config the config loaded at startup
     */
    public V0LiveType(SiteConfig config) {
        this.config = config;
    }

    @Override
    public void register(Javalin svr) {
        svr.get("/api/v0/live/type/", ctx -> {
            ctx.json(new V0LiveTypeGetRes(config.stream().effectiveType()));
        });
    }
}
