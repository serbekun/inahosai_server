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
 * Serves the pre-rendered pages.
 *
 * <p>The routes are registered once, from the config that was loaded at startup, and
 * every request after that is a byte-array write plus an ETag comparison. No templating
 * happens per request.
 *
 * <p>The page map is held in a {@code volatile} field so it can be swapped by
 * {@code /admin/reload} without a restart. Reference assignment is atomic and the map
 * itself is immutable, so a request that is already in flight finishes against the map it
 * started with. The map is never mutated in place.
 */
public class PageRoutes implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(PageRoutes.class);

    /**
     * Pages revalidate rather than being held. Five minutes is short enough that a
     * corrected typo reaches visitors quickly, and the ETag makes the revalidation
     * itself cheap.
     */
    private static final String CACHE_CONTROL = "public, max-age=300";

    private static final String FALLBACK_NOT_FOUND = "404";

    private final SiteRenderer renderer;

    private volatile Map<String, RenderedPage> pages;
    private volatile RenderedPage notFound;
    private volatile RenderedPage sitemap;
    private volatile RenderedPage robots;

    /**
     * Creates the handler and performs the initial render.
     *
     * @param renderer the renderer
     * @param config   the config loaded at startup
     */
    public PageRoutes(SiteRenderer renderer, SiteConfig config) {
        this.renderer = renderer;
        reload(config);
    }

    /**
     * Re-renders every page against a new config and swaps the result in.
     *
     * <p>Routes are not re-registered: a page added to the config after startup needs a
     * restart. Changing the text of an existing page does not.
     *
     * @param config the config to render
     * @return the number of pages rendered
     */
    public final int reload(SiteConfig config) {
        Map<String, RenderedPage> rendered = renderer.renderAll(config);
        RenderedPage error = renderer.renderNotFound(config);
        this.pages = rendered;
        this.notFound = error;
        this.sitemap = renderer.renderSitemap(config);
        this.robots = renderer.renderRobots(config);
        return rendered.size();
    }

    /**
     * The routes currently served.
     *
     * @return the immutable route to page map
     */
    public Map<String, RenderedPage> pages() {
        return pages;
    }

    @Override
    public void register(Javalin svr) {
        // Captured once so the set of routes is fixed, while the bodies behind them stay
        // swappable.
        for (String route : pages.keySet()) {
            svr.get(route, ctx -> serve(ctx, route));
        }

        // Registered when they can be built. A sitemap needs an absolute public origin,
        // so without site.base_url there is nothing to register; robots.txt is always
        // useful and simply omits its Sitemap line when the origin is unknown.
        if (sitemap != null) {
            svr.get("/sitemap.xml", this::serveSitemap);
        }
        svr.get("/robots.txt", this::serveRobots);

        // Not qualified by content type: Javalin matches that against the RESPONSE type,
        // and an unmatched route is text/plain, so an "html" mapper would never fire.
        // Negotiating on the request's Accept header instead leaves the JSON error
        // bodies of the /static and /api routes alone.
        svr.error(HttpStatus.NOT_FOUND.getCode(), this::serveNotFound);

        log.info("Serving {} page route(s)", pages.size());
    }

    /**
     * Writes one page, honouring conditional requests.
     *
     * @param ctx   the request
     * @param route the route being served
     */
    private void serve(Context ctx, String route) {
        RenderedPage page = pages.get(route);
        if (page == null) {
            // Only reachable if a reload dropped a page that had a route registered.
            serveNotFound(ctx);
            return;
        }
        write(ctx, page);
    }

    /**
     * Writes the 404 body for a client that asked for a page.
     *
     * <p>A client that did not ask for HTML -- an API caller, a probe -- keeps whatever
     * response it was already given, so the JSON error bodies elsewhere are preserved.
     *
     * @param ctx the request
     */
    private void serveNotFound(Context ctx) {
        if (!wantsHtml(ctx)) {
            return;
        }
        RenderedPage page = notFound;
        ctx.status(HttpStatus.NOT_FOUND);
        if (page == null) {
            ctx.result(FALLBACK_NOT_FOUND);
            return;
        }
        ctx.contentType(page.contentType()).result(page.body());
    }

    /**
     * Writes the sitemap, or a 404 if a reload removed the public origin it needs.
     *
     * @param ctx the request
     */
    private void serveSitemap(Context ctx) {
        RenderedPage page = sitemap;
        if (page == null) {
            ctx.status(HttpStatus.NOT_FOUND).result(FALLBACK_NOT_FOUND);
            return;
        }
        write(ctx, page);
    }

    /**
     * Writes {@code robots.txt}.
     *
     * @param ctx the request
     */
    private void serveRobots(Context ctx) {
        RenderedPage page = robots;
        if (page == null) {
            ctx.status(HttpStatus.NOT_FOUND).result(FALLBACK_NOT_FOUND);
            return;
        }
        write(ctx, page);
    }

    /**
     * Whether the client asked for HTML.
     *
     * @param ctx the request
     * @return true if the Accept header mentions HTML
     */
    private static boolean wantsHtml(Context ctx) {
        String accept = ctx.header("Accept");
        return accept != null && accept.contains("text/html");
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
