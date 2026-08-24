package com.serbekun.bunkasai.http.handles;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.domain.http.dto.ErrorRes;
import com.serbekun.bunkasai.domain.http.dto.ReloadRes;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reloads the config and re-renders every page, without a restart.
 *
 * <p>This is the only write endpoint in the server, and the project has no other
 * authentication anywhere, so it is deliberately narrow:
 *
 * <ul>
 *   <li>the route is not registered at all unless {@code BUNKASAI_ADMIN_TOKEN} is set —
 *       an unconfigured deployment has nothing to attack;</li>
 *   <li>only loopback callers are considered, checked against the socket's own peer
 *       address rather than a forwarding header, which a client can set freely;</li>
 *   <li>the token is compared in constant time.</li>
 * </ul>
 *
 * <p>A reload never takes the site down. If the new config is invalid the old pages keep
 * serving and the error comes back in the response.
 */
public class AdminReload implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminReload.class);

    /** Environment variable holding the shared secret. */
    public static final String TOKEN_ENV = "BUNKASAI_ADMIN_TOKEN";

    private static final String ROUTE = "/api/v0/admin/reload";
    private static final String BEARER = "Bearer ";

    private final Supplier<SiteConfig> configSource;
    private final PageRoutes pageRoutes;
    private final SetupPage setupPage;
    private final String token;

    /**
     * Creates the handler.
     *
     * @param configSource loads the config afresh on each call
     * @param pageRoutes   the page routes whose bodies are swapped
     * @param setupPage    the setup page, kept pointing at the same config
     * @param token        the shared secret, or null/blank to disable the route entirely
     */
    public AdminReload(Supplier<SiteConfig> configSource, PageRoutes pageRoutes,
                       SetupPage setupPage, String token) {
        this.configSource = configSource;
        this.pageRoutes = pageRoutes;
        this.setupPage = setupPage;
        this.token = token;
    }

    /**
     * Creates the handler, taking the token from the environment.
     *
     * @param configSource loads the config afresh on each call
     * @param pageRoutes   the page routes whose bodies are swapped
     * @param setupPage    the setup page, kept pointing at the same config
     */
    public AdminReload(Supplier<SiteConfig> configSource, PageRoutes pageRoutes,
                       SetupPage setupPage) {
        this(configSource, pageRoutes, setupPage, System.getenv(TOKEN_ENV));
    }

    @Override
    public void register(Javalin svr) {
        if (token == null || token.isBlank()) {
            log.info("{} is disabled ({} is not set)", ROUTE, TOKEN_ENV);
            return;
        }

        svr.post(ROUTE, this::reload);
        log.info("{} is enabled for loopback callers", ROUTE);
    }

    /**
     * Handles a reload request.
     *
     * @param ctx the request
     */
    private void reload(Context ctx) {
        if (!isLoopback(ctx)) {
            log.warn("Rejected {} from non-loopback address", ROUTE);
            ctx.status(HttpStatus.FORBIDDEN).json(new ErrorRes("Forbidden"));
            return;
        }
        if (!isAuthorized(ctx)) {
            log.warn("Rejected {} with an invalid token", ROUTE);
            ctx.status(HttpStatus.UNAUTHORIZED).json(new ErrorRes("Unauthorized"));
            return;
        }

        SiteConfig config;
        try {
            config = configSource.get();
        } catch (RuntimeException e) {
            // The running site is untouched, so a bad edit costs nothing but this call.
            log.warn("Reload rejected: the config did not load", e);
            ctx.status(HttpStatus.BAD_REQUEST).json(new ErrorRes(e.getMessage()));
            return;
        }

        int rendered = pageRoutes.reload(config);
        setupPage.reload(config);

        log.info("Reloaded config and re-rendered {} page(s)", rendered);
        ctx.json(new ReloadRes(rendered, config.missingKeys()));
    }

    /**
     * Whether the caller is on this machine.
     *
     * <p>Checked against the socket's peer address, not {@code X-Forwarded-For}: that
     * header is set by the client and would make the check meaningless. A deployment
     * behind a reverse proxy therefore cannot reach this route from outside, which is the
     * intended behaviour.
     *
     * @param ctx the request
     * @return true if the peer address is a loopback address
     */
    private static boolean isLoopback(Context ctx) {
        try {
            return InetAddress.getByName(ctx.req().getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Whether the request carries the right token.
     *
     * <p>Compared with {@link MessageDigest#isEqual}, which does not return early on the
     * first differing byte, so the comparison time does not leak the token's prefix.
     *
     * @param ctx the request
     * @return true if the token matches
     */
    private boolean isAuthorized(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return false;
        }
        byte[] presented = header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, expected);
    }
}
