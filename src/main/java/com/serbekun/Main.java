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
import com.serbekun.inahosai.resources.LookupOrder;
import com.serbekun.inahosai.resources.ResourceCache;
import com.serbekun.inahosai.resources.ResourceLoader;
import com.serbekun.inahosai.resources.ResourceUnpacker;
import com.serbekun.inahosai.service.resource.ResourcesService;

import io.javalin.Javalin;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
    
        log.info("Inahosai!");
        log.info("Ver: " + BuildInfo.version());

        /**
         * 3. Resource layer.
         *
         * The JAR holds templates only. On first run they are copied to the on-disk
         * working directory, and from then on the server reads the disk and nothing else,
         * so private files (PDFs and the like) never have a packaged counterpart.
         */
        ResourceLoader resourceLoader = new ResourceLoader(
                ResourceLoader.DEFAULT_OVERRIDE_ROOT, LookupOrder.DISK_ONLY);
        log.info("Resource root: {} (disk only)", resourceLoader.overrideRoot());
        new ResourceUnpacker(resourceLoader, BuildInfo.version()).unpack();

        ResourceCache resourceCache = new ResourceCache(resourceLoader);
        ResourcesService resourcesService = new ResourcesService(resourceCache);

        /**
         * 4. Site config and page rendering.
         *
         * The config has to be confirmed by a human before the site is served: a fork
         * that still carries the default school must not go live by accident. This check
         * runs after unpacking so the on-disk templates exist even when we refuse to
         * start.
         */
        SiteConfigLoader siteConfigLoader = new SiteConfigLoader();
        SiteConfig siteConfig = siteConfigLoader.load();
        requireConfirmedConfig(siteConfig);
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
     * Refuses to start until a human has confirmed the config fits this school.
     *
     * <p>The bundled default is a working site for another school, so serving it on
     * purpose would be a silent mistake. The gate is one explicit boolean a forker sets
     * after reading the file, and it is checked before any route is registered.
     *
     * @param config the loaded config
     */
    private static void requireConfirmedConfig(SiteConfig config) {
        if (config.isSetupReadedAndConfigEdited()) {
            return;
        }
        log.error("The site config has not been confirmed for this school.");
        log.error("Open config.yaml, check every value against SETUP.md, then set "
                + "'is_setup_readed_and_config_edited: true' and start the server again.");
        log.error("Refusing to start while the bundled default could be served as-is.");
        System.exit(1);
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
