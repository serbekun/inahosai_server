package com.serbekun.bunkasai.http.handles;

import com.serbekun.bunkasai.config.AppEnv;
import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.render.RenderedPage;
import com.serbekun.bunkasai.render.SiteRenderer;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves {@code /setup}, a checklist of which config keys are still unset.
 *
 * <p>Development only. The route is not registered at all outside {@code BUNKASAI_ENV=dev}
 * — not registered and then refused, so there is nothing to probe in production.
 *
 * <p>The page reports key names and set/unset status only, never values.
 */
public class SetupPage implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(SetupPage.class);

    /** The setup checklist is a live view of local state, so it must not be cached. */
    private static final String CACHE_CONTROL = "no-store";

    private final SiteRenderer renderer;
    private final boolean enabled;

    private volatile SiteConfig config;

    /**
     * Creates the handler.
     *
     * @param renderer the renderer
     * @param config   the config loaded at startup
     * @param enabled  whether development-only routes should be served
     */
    public SetupPage(SiteRenderer renderer, SiteConfig config, boolean enabled) {
        this.renderer = renderer;
        this.config = config;
        this.enabled = enabled;
    }

    /**
     * Creates the handler, enabling it only in development.
     *
     * @param renderer the renderer
     * @param config   the config loaded at startup
     */
    public SetupPage(SiteRenderer renderer, SiteConfig config) {
        this(renderer, config, AppEnv.isDev());
    }

    /**
     * Points the page at a new config.
     *
     * @param config the new config
     */
    public void reload(SiteConfig config) {
        this.config = config;
    }

    @Override
    public void register(Javalin svr) {
        if (!enabled) {
            log.info("/setup is disabled ({}={} selects production)",
                    AppEnv.ENV_VAR, AppEnv.DEV);
            return;
        }

        // Rendered per request rather than at startup: it is a development aid whose
        // whole job is to show current state, and it is hit once in a while by one person.
        svr.get("/setup", ctx -> {
            RenderedPage page = renderer.renderSetup(config);
            if (page == null) {
                ctx.status(HttpStatus.NOT_FOUND).result("Setup page template is missing");
                return;
            }
            ctx.contentType(page.contentType())
                    .header("Cache-Control", CACHE_CONTROL)
                    .result(page.body());
        });

        log.info("/setup is enabled ({}={})", AppEnv.ENV_VAR, AppEnv.DEV);
    }
}
