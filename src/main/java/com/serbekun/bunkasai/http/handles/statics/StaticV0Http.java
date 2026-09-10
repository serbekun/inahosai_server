package com.serbekun.bunkasai.http.handles.statics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.domain.http.dto.ErrorRes;
import com.serbekun.bunkasai.resources.ResourcesBasePath;
import com.serbekun.bunkasai.service.resource.ResourcesService;
import com.serbekun.bunkasai.service.resource.ResourcesService.ResourceData;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves static resources of the {@code /static/v0/*} family.
 * <p>
 * Every supported resource kind is described by {@link StaticResource}: the
 * resource directory it maps to and whether its listing is public. Files are
 * always served as bytes with the content type derived from the extension, so
 * text and binary kinds follow the same path.
 * </p>
 * <p>
 * PDFs can optionally be gated by {@code pdf.require_auth}/{@code pdf.token}:
 * when enabled, both the file and its directory listing require the token as the
 * {@code token} query parameter. Other kinds are never gated.
 * </p>
 */
public class StaticV0Http {

    private static final Logger log = LoggerFactory.getLogger(StaticV0Http.class);

    /** Content type used for every directory listing response. */
    private static final String LIST_CONTENT_TYPE = "application/json";

    /** How long a client may reuse a static file before revalidating. */
    private static final String CACHE_CONTROL = "public, max-age=300";

    /** Query parameter carrying the PDF gate. */
    private static final String TOKEN_PARAM = "token";

    private final ResourcesService resourcesService;
    private final boolean pdfAuthRequired;
    private final String pdfToken;

    public StaticV0Http(ResourcesService resourcesService, SiteConfig config) {
        this.resourcesService = resourcesService;
        this.pdfToken = config.pdf().token();
        // An auth demand without a token would lock every PDF out, so treat it as
        // inert and say so rather than silently denying the whole directory.
        this.pdfAuthRequired = config.pdf().requireAuth() && !pdfToken.isEmpty();
        if (config.pdf().requireAuth() && pdfToken.isEmpty()) {
            log.warn("pdf.require_auth is true but pdf.token is empty; PDFs are served without a token");
        }
    }

    /** Serves the resource named by the {@code name} path parameter. */
    public void serve(Context ctx, StaticResource resource) {
        serve(ctx, ctx.pathParam("name"), resource);
    }

    /**
     * Serves a single resource, or the directory listing when {@code name} is
     * null/empty.
     *
     * @param ctx      the request context
     * @param name     the resource file name; null or empty means "list the directory"
     * @param resource the resource kind being served
     */
    public void serve(Context ctx, String name, StaticResource resource) {
        if (isPdf(resource) && !authorized(ctx)) {
            ctx.status(HttpStatus.UNAUTHORIZED).json(new ErrorRes("Unauthorized"));
            return;
        }

        if (name == null || name.isEmpty()) {
            serveListing(ctx, resource);
            return;
        }

        try {
            serveFile(ctx, name, resource);
        } catch (IllegalArgumentException e) {
            // Thrown by ResourcesBasePath.resolve on path traversal attempts.
            ctx.status(HttpStatus.BAD_REQUEST).json(new ErrorRes("Invalid resource name"));
        }
    }

    /** Whether the resource is the PDF kind subject to the optional gate. */
    private static boolean isPdf(StaticResource resource) {
        return resource == StaticResource.PDF;
    }

    /**
     * Whether the request may read a PDF.
     *
     * <p>Compared with {@link MessageDigest#isEqual}, which does not return early on the
     * first differing byte, so the comparison time does not leak the token's length.
     *
     * @param ctx the request
     * @return true when the gate is off, or the request carries the right token
     */
    private boolean authorized(Context ctx) {
        if (!pdfAuthRequired) {
            return true;
        }
        String presented = ctx.queryParam(TOKEN_PARAM);
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                pdfToken.getBytes(StandardCharsets.UTF_8));
    }

    private void serveListing(Context ctx, StaticResource resource) {
        if (!resource.listable) {
            ctx.status(HttpStatus.NOT_FOUND).json(new ErrorRes("Resource listing not available"));
            return;
        }

        String files = resourcesService.listAsJson(resource.basePath);
        if (files == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(new ErrorRes("Resource listing not available"));
            return;
        }

        ctx.contentType(LIST_CONTENT_TYPE);
        ctx.result(files);
    }

    private void serveFile(Context ctx, String name, StaticResource resource) {
        ResourceData data = resourcesService.getResource(resource.basePath, name);
        if (data == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(new ErrorRes("Resource not found"));
            return;
        }

        ctx.header("ETag", data.etag());
        ctx.header("Cache-Control", CACHE_CONTROL);

        if (data.etag().equals(ctx.header("If-None-Match"))) {
            ctx.status(HttpStatus.NOT_MODIFIED);
            return;
        }

        ctx.contentType(data.contentType());
        ctx.result(data.data());
    }

    /**
     * Describes one kind of static resource served under {@code /static/v0/}.
     * The enum constant name (lower-cased) is also the URL segment.
     */
    public enum StaticResource {

        // HTML is deliberately absent. Pages are pre-rendered from templates and
        // served from their own routes, so serving the raw html/ directory here would
        // publish the templates themselves, the partials under it, and the
        // point_text.html debug playground.
        CSS(ResourcesBasePath.BASE_CSS_PATH, true),
        IMAGES(ResourcesBasePath.BASE_IMAGES_PATH, true),
        JS(ResourcesBasePath.BASE_JS_PATH, true),
        JSON(ResourcesBasePath.BASE_JSON_PATH, true),
        PDF(ResourcesBasePath.BASE_PDF_PATH, true),
        SVG(ResourcesBasePath.BASE_SVG_PATH, true);

        private final String basePath;
        private final boolean listable;

        StaticResource(String basePath, boolean listable) {
            this.basePath = basePath;
            this.listable = listable;
        }

        /** The URL segment this resource is served under, e.g. {@code images}. */
        public String urlSegment() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
