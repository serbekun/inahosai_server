package com.serbekun;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serbekun.inahosai.BuildInfo;
import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.config.SiteConfigLoader;
import com.serbekun.inahosai.http.InitHttp;
import com.serbekun.inahosai.http.handles.AdminReload;
import com.serbekun.inahosai.http.handles.DebugPages;
import com.serbekun.inahosai.http.handles.HttpHandler;
import com.serbekun.inahosai.http.handles.PageRoutes;
import com.serbekun.inahosai.http.handles.SetupPage;
import com.serbekun.inahosai.http.handles.StaticRoutes;
import com.serbekun.inahosai.http.handles.V0Health;
import com.serbekun.inahosai.http.handles.V0LiveType;
import com.serbekun.inahosai.render.SiteRenderer;
import com.serbekun.inahosai.resources.ResourceCache;
import com.serbekun.inahosai.resources.ResourceLoader;
import com.serbekun.inahosai.service.resource.ResourcesService;

import io.javalin.Javalin;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
    
        log.info("Inahosai!");
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
        SiteConfigLoader siteConfigLoader = new SiteConfigLoader();
        SiteConfig siteConfig = siteConfigLoader.load();
        warnAboutMissingConfig(siteConfig);

        SiteRenderer siteRenderer = new SiteRenderer(resourcesService);
        PageRoutes pageRoutes = new PageRoutes(siteRenderer, siteConfig);
        SetupPage setupPage = new SetupPage(siteRenderer, siteConfig);

        /**
         * 5. HTTP layer (handlers with DI)
         */
        List<HttpHandler> handlers = List.of(
            new V0Health(),
            new V0LiveType(siteConfig),
            pageRoutes,
            setupPage,
            new DebugPages(resourcesService, siteConfig),
            new AdminReload(siteConfigLoader::load, pageRoutes, setupPage),
            new StaticRoutes(resourcesService, siteConfig)
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
