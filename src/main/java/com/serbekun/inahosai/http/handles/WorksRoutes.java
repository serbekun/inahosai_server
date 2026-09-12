package com.serbekun.inahosai.http.handles;

import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.render.RenderedPage;
import com.serbekun.inahosai.render.SiteRenderer;

/**
 * Serves the per-work file-chooser pages at {@code /works/{key}}.
 *
 * <p>These pages are not in the configured {@code pages} list, so they never appear in
 * the shared nav and are not part of the sitemap. They are rendered once at startup and
 * swapped in by {@code /admin/reload}, exactly like {@link PageRoutes}; a request is a
 * byte-array write plus an ETag comparison.
 */
public class WorksRoutes implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(WorksRoutes.class);

    /** Matches the page routes: revalidate quickly, the ETag makes it cheap. */
    private static final String CACHE_CONTROL = "public, max-age=300";

    private static final String FALLBACK_NOT_FOUND = "404";

    private final SiteRenderer renderer;

    private volatile Map<String, RenderedPage> pages;

    /**
     * Creates the handler and performs the initial render.
     *
     * @param renderer the renderer
     * @param config   the config loaded at startup
     */
    public WorksRoutes(SiteRenderer renderer, SiteConfig config) {
        this.renderer = renderer;
        reload(config);
    }

    /**
     * Re-renders the chooser pages against a new config and swaps the result in.
     *
     * <p>Routes are not re-registered: a work added after startup needs a restart. The
     * map is never mutated in place, so an in-flight request finishes against the map
     * it started with.
     *
     * @param config the config to render
     * @return the number of chooser pages rendered
     */
    public final int reload(SiteConfig config) {
        Map<String, RenderedPage> rendered = renderer.renderWorkPages(config);
        this.pages = rendered;
        return rendered.size();
    }

    /**
     * The chooser pages currently served.
     *
     * @return the immutable work-key to page map
     */
    public Map<String, RenderedPage> pages() {
        return pages;
    }

    @Override
    public void register(Javalin svr) {
        for (String key : pages.keySet()) {
            svr.get(SiteRenderer.WORK_ROUTE_PREFIX + key, ctx -> serve(ctx, key));
        }
        log.info("Serving {} work page route(s)", pages.size());
    }

    /**
     * Writes one chooser page, honouring conditional requests.
     *
     * @param ctx the request
     * @param key the work key
     */
    private void serve(Context ctx, String key) {
        RenderedPage page = pages.get(key);
        if (page == null) {
            // Only reachable if a reload dropped a work that had a route registered.
            ctx.status(HttpStatus.NOT_FOUND).result(FALLBACK_NOT_FOUND);
            return;
        }
        write(ctx, page);
    }

    /**
     * Writes a page with caching headers, or a 304 when the client's copy is current.
     *
     * @param ctx  the request
     * @param page the page to write
     */
    private static void write(Context ctx, RenderedPage page) {
        ctx.header("ETag", page.etag());
        ctx.header("Cache-Control", CACHE_CONTROL);

        if (page.etag().equals(ctx.header("If-None-Match"))) {
            ctx.status(HttpStatus.NOT_MODIFIED);
            return;
        }

        ctx.contentType(page.contentType()).result(page.body());
    }
}
