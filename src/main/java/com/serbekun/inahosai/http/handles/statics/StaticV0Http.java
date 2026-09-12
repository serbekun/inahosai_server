package com.serbekun.inahosai.http.handles.statics;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.domain.http.dto.ErrorRes;
import com.serbekun.inahosai.resources.ResourcesBasePath;
import com.serbekun.inahosai.service.resource.ResourcesService;
import com.serbekun.inahosai.service.resource.ResourcesService.ResourceData;

/**
 * Serves static resources of the {@code /static/v0/*} family.
 * <p>
 * Every supported resource kind is described by {@link StaticResource}: the
 * resource directory it maps to and whether its listing is public. Files are
 * always served as bytes with the content type derived from the extension, so
 * text and binary kinds follow the same path.
 * </p>
 * <p>
 * PDFs can optionally be gated by {@code pdf.require_auth}/{@code pdf.token}.
 * The token is never accepted from the URL: the visitor submits it once through a
 * {@code POST} form to {@link #UNLOCK_PATH}, which sets an HttpOnly cookie, and
 * every later PDF request is authorized by that cookie. A missing or wrong token
 * gets 401. The gate also covers the PDF directory listing, so file names do not
 * leak either.
 * </p>
 * <p>
 * PDF responses are marked {@code private, no-store} and {@code noindex}, because
 * they may be access-controlled and must not sit in a shared cache or a search
 * index.
 * </p>
 */
public class StaticV0Http {

    private static final Logger log = LoggerFactory.getLogger(StaticV0Http.class);

    /** Content type used for every directory listing response. */
    private static final String LIST_CONTENT_TYPE = "application/json";

    /** How long a client may reuse a public static file before revalidating. */
    private static final String CACHE_CONTROL = "public, max-age=300";

    /** Access-controlled files must not be stored by any cache, shared or private. */
    private static final String PDF_CACHE_CONTROL = "private, no-store";

    /** PDFs must not be indexed, and links to them must not be followed. */
    private static final String PDF_ROBOTS = "noindex, nofollow";

    /** Form field carrying the PDF token on the unlock POST. */
    private static final String TOKEN_PARAM = "token";

    /** Form field carrying where to go back to after a successful unlock. */
    private static final String NEXT_PARAM = "next";

    /** Name of the HttpOnly cookie that carries a successful unlock. */
    private static final String PDF_COOKIE = "inahosai_pdf";

    /** Where the browser should send the cookie, and nowhere else. */
    private static final String PDF_COOKIE_PATH = "/static/v0/pdf";

    /** How long an unlocked session lasts. One festival day, then re-enter. */
    private static final int PDF_COOKIE_MAX_AGE_SECONDS = 12 * 60 * 60;

    /** Where the unlock form posts. */
    public static final String UNLOCK_PATH = "/api/v0/pdf/unlock";

    /** Algorithm used to derive the cookie value from the token. */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ResourcesService resourcesService;
    private final boolean pdfAuthRequired;
    private final String pdfToken;

    /**
     * A per-process random key. The cookie never holds the token itself, only an
     * HMAC of it under this key, so a leaked cookie cannot be turned back into the
     * token offline. A restart invalidates every unlock, which is cheap here.
     */
    private final byte[] cookieKey = new byte[32];

    /** The expected cookie value, precomputed once. */
    private final String pdfCookieValue;

    /** Whether the cookie should carry the Secure attribute. */
    private final boolean cookieSecure;

    public StaticV0Http(ResourcesService resourcesService, SiteConfig config) {
        this.resourcesService = resourcesService;
        this.pdfToken = config.pdf().token();
        // An auth demand without a token would lock every PDF out, so treat it as
        // inert and say so rather than silently denying the whole directory.
        this.pdfAuthRequired = config.pdf().requireAuth() && !pdfToken.isEmpty();
        if (config.pdf().requireAuth() && pdfToken.isEmpty()) {
            log.warn("pdf.require_auth is true but pdf.token is empty; PDFs are served without a token");
        }

        new SecureRandom().nextBytes(cookieKey);
        this.pdfCookieValue = pdfAuthRequired ? cookieValue(pdfToken) : "";
        this.cookieSecure = config.site().baseUrl().startsWith("https://");
    }

    /** Whether the PDF gate is armed (a token is required and configured). */
    public boolean pdfAuthRequired() {
        return pdfAuthRequired;
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

    /**
     * Handles the unlock form: validates the posted token and, on success, sets the
     * HttpOnly cookie and sends the visitor back to the page that linked the form.
     *
     * <p>The token travels in the request body, never in the URL, so it cannot end up
     * in server access logs, browser history or a {@code Referer} header. The cookie
     * is HttpOnly so page scripts cannot read it, SameSite=Lax so a cross-site form
     * cannot ride it, and scoped to the PDF directory so it is not sent anywhere else.
     *
     * @param ctx the POST request
     */
    public void unlock(Context ctx) {
        String next = safeNext(ctx.formParam(NEXT_PARAM));
        String presented = ctx.formParam(TOKEN_PARAM);

        if (!pdfAuthRequired) {
            // The gate is off; there is nothing to unlock. Registered only when armed,
            // but a reload cannot change that, so this is just belt and braces.
            ctx.redirect(next, HttpStatus.SEE_OTHER);
            return;
        }

        if (presented == null || !equalConstantTime(presented, pdfToken)) {
            ctx.status(HttpStatus.UNAUTHORIZED)
                    .contentType("text/html; charset=utf-8")
                    .result(unlockFailedPage(next));
            return;
        }

        Cookie cookie = new Cookie(PDF_COOKIE, pdfCookieValue);
        cookie.setPath(PDF_COOKIE_PATH);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure || "https".equalsIgnoreCase(ctx.scheme()));
        cookie.setSameSite(SameSite.LAX);
        cookie.setMaxAge(PDF_COOKIE_MAX_AGE_SECONDS);
        ctx.cookie(cookie);

        ctx.redirect(next, HttpStatus.SEE_OTHER);
    }

    /** Whether the resource is the PDF kind subject to the optional gate. */
    private static boolean isPdf(StaticResource resource) {
        return resource == StaticResource.PDF;
    }

    /**
     * Whether the request may read a PDF.
     *
     * <p>Authorized by the unlock cookie, matched in constant time against the value
     * derived from the configured token. No query parameter is consulted, so the token
     * cannot leak through a URL.
     *
     * @param ctx the request
     * @return true when the gate is off, or the request carries a valid unlock cookie
     */
    private boolean authorized(Context ctx) {
        if (!pdfAuthRequired) {
            return true;
        }
        String presented = ctx.cookie(PDF_COOKIE);
        if (presented == null) {
            return false;
        }
        return equalConstantTime(presented, pdfCookieValue);
    }

    /**
     * Compares two strings in constant time with respect to their contents.
     *
     * @param a first value
     * @param b second value
     * @return true when they are equal
     */
    private static boolean equalConstantTime(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Derives the cookie value from the token under the per-process key.
     *
     * @param token the configured PDF token
     * @return the hex-encoded HMAC
     */
    private String cookieValue(String token) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(cookieKey, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // JDK always ships HmacSHA256; a failure here is a broken runtime.
            throw new IllegalStateException("Cannot compute the PDF unlock cookie", e);
        }
    }

    /**
     * Validates the post-unlock destination so it is a site-relative path and cannot
     * become an open redirect or a header-injection vector.
     *
     * @param next the raw {@code next} form value
     * @return a safe relative path, or {@code "/"} when the value is unusable
     */
    private static String safeNext(String next) {
        if (next == null || next.isEmpty()) {
            return "/";
        }
        if (!next.startsWith("/") || next.startsWith("//") || next.contains("..")
                || next.contains("\\") || next.contains("\n") || next.contains("\r")) {
            return "/";
        }
        return next;
    }

    /**
     * A minimal page shown when the posted token is wrong, linking back to the form.
     *
     * @param next the validated return path
     * @return a small HTML document
     */
    private static String unlockFailedPage(String next) {
        String href = next.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
        return "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"robots\" content=\"noindex, nofollow\">"
                + "<title>401</title></head><body>"
                + "<p>パスワードが違います。</p>"
                + "<p><a href=\"" + href + "\">戻る</a></p>"
                + "</body></html>";
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

        if (isPdf(resource)) {
            ctx.header("Cache-Control", PDF_CACHE_CONTROL);
            ctx.header("X-Robots-Tag", PDF_ROBOTS);
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

        // A gated file must not be stored by caches or indexed, so it is written with
        // no validators at all -- an ETag plus If-None-Match would invite revalidation
        // of something that is explicitly no-store.
        if (isPdf(resource)) {
            ctx.header("Cache-Control", PDF_CACHE_CONTROL);
            ctx.header("X-Robots-Tag", PDF_ROBOTS);
            ctx.contentType(data.contentType());
            ctx.result(data.data());
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
        PDF(ResourcesBasePath.BASE_PDF_PATH, true);

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
