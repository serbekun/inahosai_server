package com.serbekun.inahosai.http.handles;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.http.handles.statics.StaticV0Http;
import com.serbekun.inahosai.http.handles.statics.StaticV0Http.StaticResource;
import com.serbekun.inahosai.service.resource.ResourcesService;

import io.javalin.Javalin;

/**
 * <p>Registers every static resource route of the {@code /static/v0/*} family.</p>
 *
 * <p>Two routes are registered per {@link StaticResource} kind:</p>
 * <ul>
 *   <li>{@code GET /static/v0/{kind}} — JSON listing of the directory</li>
 *   <li>{@code GET /static/v0/{kind}/{name}} — the file itself</li>
 * </ul>
 *
 * <p>When the PDF gate is armed, {@code POST /api/v0/pdf/unlock} is registered as
 * well: it takes the token in the request body and sets the HttpOnly cookie the PDF
 * routes then check, so the token never appears in a URL.</p>
 *
 * <p>Trailing slashes are ignored by Javalin by default, so
 * {@code /static/v0/css/} hits the listing route as well.</p>
 */
public class StaticRoutes implements HttpHandler {

    private static final String BASE_URL = "/static/v0/";

    private final StaticV0Http staticV0Http;

    public StaticRoutes(ResourcesService resourcesService, SiteConfig config) {
        this.staticV0Http = new StaticV0Http(resourcesService, config);
    }

    @Override
    public void register(Javalin svr) {
        for (StaticResource resource : StaticResource.values()) {
            String base = BASE_URL + resource.urlSegment();

            svr.get(base, ctx -> staticV0Http.serve(ctx, "", resource));
            svr.get(base + "/{name}", ctx -> staticV0Http.serve(ctx, resource));
        }

        // Registered only while the gate is armed, exactly like the /debug mount: an
        // open deployment has no unlock endpoint to probe. The form posts the token in
        // the body and the response only sets a cookie, so the token stays out of URLs.
        if (staticV0Http.pdfAuthRequired()) {
            svr.post(StaticV0Http.UNLOCK_PATH, staticV0Http::unlock);
        }
    }
}
