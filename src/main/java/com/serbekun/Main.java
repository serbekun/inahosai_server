package com.serbekun;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serbekun.bunkasai.BuildInfo;
import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.config.SiteConfigLoader;
import com.serbekun.bunkasai.http.handles.PageRoutes;
import com.serbekun.bunkasai.http.handles.StaticRoutes;
import com.serbekun.bunkasai.http.handles.V0Health;

import io.javalin.Javalin;

import com.serbekun.bunkasai.http.InitHttp;
import com.serbekun.bunkasai.http.handles.HttpHandler;
import com.serbekun.bunkasai.resources.ResourceCache;
import com.serbekun.bunkasai.render.SiteRenderer;
import com.serbekun.bunkasai.resources.ResourceLoader;
import com.serbekun.bunkasai.service.resource.ResourcesService;


public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        
        log.info("Bunkasai!");
        log.info("Ver: " + BuildInfo.version());

        /**
         * 3. Resource layer (loader -> cache -> service)
         */
        ResourceLoader resourceLoader = new ResourceLoader();
        log.info("Resource override root: {}", resourceLoader.overrideRoot());
        ResourceCache resourceCache = new ResourceCache(resourceLoader);
        ResourcesService resourcesService = new ResourcesService(resourceCache);

        /**
         * 4. Site config and page rendering.
         *
         * Every page is rendered here, once, and held as bytes for the life of the
         * process. Rendering five pages takes milliseconds, so there is no reason to
         * defer it to the first request.
         */
        SiteConfig siteConfig = new SiteConfigLoader().load();
        warnAboutMissingConfig(siteConfig);

        SiteRenderer siteRenderer = new SiteRenderer(resourcesService);
        PageRoutes pageRoutes = new PageRoutes(siteRenderer, siteConfig);

        /**
         * 5. HTTP layer (handlers with DI)
         */
        List<HttpHandler> handlers = List.of(
            new V0Health(),
            pageRoutes,
            new StaticRoutes(resourcesService)
        );

        Javalin svr = Javalin.create();
        InitHttp initHttp = new InitHttp(svr, 2323, handlers);
        initHttp.initHttp();

    }

    /**
     * Logs the config keys a fork still has to fill in.
     *
     * <p>The site stays usable without them -- unset values hide their elements rather
     * than breaking the page -- so this is a warning, not a failure.
     *
     * @param config the loaded config
     */
    private static void warnAboutMissingConfig(SiteConfig config) {
        List<String> missing = config.missingKeys();
        if (missing.isEmpty()) {
            return;
        }
        log.warn("Site config is incomplete. Unset required keys: {}", String.join(", ", missing));
        log.warn("See SETUP.md. Every page will show a setup banner until these are set.");
    }
}
