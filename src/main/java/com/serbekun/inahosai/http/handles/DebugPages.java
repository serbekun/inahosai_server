package com.serbekun.inahosai.http.handles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.render.SiteRenderer;
import com.serbekun.inahosai.resources.ResourcesBasePath;
import com.serbekun.inahosai.service.resource.ResourcesService;
import com.serbekun.inahosai.service.resource.ResourcesService.ResourceData;

/**
 * Serves raw, page-less HTML "playground" files from {@code resources/html/} under
 * {@code /debug/{file}}.
 *
 * <p>These are standalone files such as {@code point_text.html} that are not configured
 * pages — they have no clean route, so they are deliberately unpublished. Whether a fork
 * can reach them anyway is a config choice ({@code debug.enabled}), not an environment one:
 * unlike {@link SetupPage}, this mount is opt-in through {@code config.yaml} and so can be
 * turned on for a production deployment that wants to share a playground.
 *
 * <p>Two things keep it narrow. The route is not registered at all unless
 * {@code debug.enabled} is true, so an off-by-default deployment has nothing to probe. And
 * when {@code debug.token} is set, a request must present it as the {@code token} query
 * parameter (matched in constant time); a wrong or missing token gets the same 404 as a
 * file that does not exist, so the mount stays hidden. Files that are real pages — a
 * configured page's template, the 404 body, the setup page — are never served raw here;
 * they already have their own routes.
 *
 * <p>The set of routes is fixed at startup, so enabling or disabling the mount requires a
 * restart, like adding or removing a page route.
 */
public class DebugPages implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(DebugPages.class);

    /** Query parameter carrying the optional gate. */
    private static final String TOKEN_PARAM = "token";

    /** A development aid is a live view of local files, so it must not be cached. */
    private static final String CACHE_CONTROL = "no-store";

    private static final String NOT_FOUND_BODY = "Not found";

    private final ResourcesService resources;
    private final boolean enabled;
    private final String token;
    private final Set<String> excluded;

    /**
     * Creates the handler.
     *
     * @param resources the resource API used to read the playground files
     * @param config    the config loaded at startup
     */
    public DebugPages(ResourcesService resources, SiteConfig config) {
        this.resources = resources;
        this.enabled = config.debug().enabled();
        this.token = config.debug().token();
        this.excluded = excludedTemplates(config);
    }

    /**
     * The templates that already have a semantic route, which must never be re-served as
     * raw files here. Built from the config so that a page added later is excluded by
     * construction rather than by a filter that could rot.
     *
     * @param config the site config
     * @return the set of template filenames that are not raw playgrounds
     */
    private static Set<String> excludedTemplates(SiteConfig config) {
        Set<String> names = new LinkedHashSet<>();
        names.add(SiteRenderer.NOT_FOUND_TEMPLATE);
        names.add(SiteRenderer.SETUP_TEMPLATE);
        for (SiteConfig.Page page : config.pages()) {
            if (!page.template().isEmpty()) {
                names.add(page.template());
            }
        }
        return Set.copyOf(names);
    }

    @Override
    public void register(Javalin svr) {
        // Not registered and then refused: an off-by-default deployment has nothing to probe.
        if (!enabled) {
            log.info("/api/v0/debug playground mount is disabled (debug.enabled=false)");
            return;
        }

        svr.get("/api/v0/debug/{file}", this::serve);

        if (token.isEmpty()) {
            log.info("/api/v0/debug playground mount is enabled without a token");
        } else {
            log.info("/api/v0/debug playground mount is enabled behind a token");
        }
    }

    /**
     * Serves one playground file, honouring the optional token.
     *
     * <p>A request for a page's own template, a non-HTML file, a missing file, or a file
     * without the right token all receive the same bare 404, so the mount does not reveal
     * what it holds.
     *
     * @param ctx the request
     */
    private void serve(Context ctx) {
        String file = ctx.pathParam("file");
        if (!allowed(file) || !authorized(ctx)) {
            hide(ctx);
            return;
        }

        ResourceData data;
        try {
            data = resources.getResource(ResourcesBasePath.BASE_HTML_PATH, file);
        } catch (IllegalArgumentException e) {
            hide(ctx);
            return;
        }
        if (data == null) {
            hide(ctx);
            return;
        }

        ctx.header("Cache-Control", CACHE_CONTROL)
                .contentType(data.contentType())
                .result(data.data());
    }

    /**
     * Whether the file is a servable playground: an HTML file that is not a page template,
     * the 404 body or the setup page. The resource resolver already rejects subdirectories
     * and traversal, so partials under {@code html/partials/} are unreachable here.
     *
     * @param file the requested filename
     * @return true if the file may be served raw
     */
    private boolean allowed(String file) {
        return file != null && file.endsWith(".html") && !excluded.contains(file);
    }

    /**
     * Whether the request is permitted past the optional token.
     *
     * <p>Compared with {@link MessageDigest#isEqual}, which does not return early on the
     * first differing byte, so the comparison time does not leak the token's length.
     *
     * @param ctx the request
     * @return true when no token is configured, or the request carries the right one
     */
    private boolean authorized(Context ctx) {
        if (token.isEmpty()) {
            return true;
        }
        String presented = ctx.queryParam(TOKEN_PARAM);
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Responds as if the route did not exist.
     *
     * @param ctx the request
     */
    private void hide(Context ctx) {
        ctx.status(HttpStatus.NOT_FOUND).result(NOT_FOUND_BODY);
    }
}
