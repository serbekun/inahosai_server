package com.serbekun.bunkasai.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.config.SiteConfigLoader;
import com.serbekun.bunkasai.http.handles.PageRoutes;
import com.serbekun.bunkasai.render.SiteRenderer;
import com.serbekun.bunkasai.resources.ResourceCache;
import com.serbekun.bunkasai.resources.ResourceLoader;
import com.serbekun.bunkasai.service.resource.ResourcesService;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;

/**
 * These use JavalinTest, which binds a random port. MainTest starts the real server on
 * the hardcoded port 2323 and leaves it running, so a fixed port here would be flaky.
 */
class PageRoutesTest {

    private final SiteConfigLoader loader = new SiteConfigLoader();

    private Javalin appFor(SiteConfig config) {
        ResourcesService resources =
                new ResourcesService(new ResourceCache(new ResourceLoader()));
        PageRoutes routes = new PageRoutes(new SiteRenderer(resources), config);
        Javalin app = Javalin.create();
        routes.register(app);
        return app;
    }

    private Javalin defaultApp() {
        return appFor(loader.loadBundledDefault());
    }

    @Test
    void everyPageIsServedAtItsCleanRoute() {
        JavalinTest.test(defaultApp(), (server, client) -> {
            for (String route : new String[] {"/", "/jikan", "/manabi", "/basho", "/sekai"}) {
                assertThat(client.get(route).code()).as("GET %s", route).isEqualTo(200);
            }
        });
    }

    @Test
    void htmlIsServedAsUtf8() {
        JavalinTest.test(defaultApp(), (server, client) ->
                // Jetty normalises the header, dropping the space after the semicolon.
                assertThat(client.get("/").header("Content-Type"))
                        .startsWith("text/html")
                        .contains("charset=utf-8"));
    }

    @Test
    void aPageCarriesAnEtagAndCacheControl() {
        JavalinTest.test(defaultApp(), (server, client) -> {
            var response = client.get("/");

            assertThat(response.header("ETag")).matches("\"[0-9a-f]{16}\"");
            assertThat(response.header("Cache-Control")).isEqualTo("public, max-age=300");
        });
    }

    @Test
    void aMatchingIfNoneMatchReturns304WithAnEmptyBody() {
        JavalinTest.test(defaultApp(), (server, client) -> {
            String etag = client.get("/").header("ETag");

            var response = client.get("/", request -> request.header("If-None-Match", etag));

            assertThat(response.code()).isEqualTo(304);
            assertThat(response.body().contentLength()).isIn(0L, -1L);
            assertThat(response.body().string()).isEmpty();
        });
    }

    @Test
    void aStaleIfNoneMatchReturnsTheFullBody() {
        JavalinTest.test(defaultApp(), (server, client) -> {
            var response = client.get("/", request ->
                    request.header("If-None-Match", "\"0000000000000000\""));

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("稲穂祭");
        });
    }

    @Test
    void anUnknownRouteReturnsTheStyledNotFoundPageRatherThanAStackTrace() {
        JavalinTest.test(defaultApp(), (server, client) -> {
            var response = client.get("/does-not-exist", request ->
                    request.header("Accept", "text/html"));

            assertThat(response.code()).isEqualTo(404);
            String body = response.body().string();
            assertThat(body).contains("404").contains("topbar").contains("styles.css");
            assertThat(body).doesNotContain("Exception");
        });
    }

    @Test
    void theOldStaticHtmlPathIsNoLongerServed() {
        // The html/ directory holds templates, partials and a debug playground; none of
        // it should be reachable now that pages are rendered.
        JavalinTest.test(defaultApp(), (server, client) -> {
            ResourcesService resources =
                    new ResourcesService(new ResourceCache(new ResourceLoader()));
            new com.serbekun.bunkasai.http.handles.StaticRoutes(resources).register(server);

            assertThat(client.get("/static/v0/html/index.html").code()).isEqualTo(404);
            assertThat(client.get("/static/v0/html/point_text.html").code()).isEqualTo(404);
        });
    }

    @Test
    void reloadSwapsThePageBodiesWithoutReregisteringRoutes() {
        ResourcesService resources =
                new ResourcesService(new ResourceCache(new ResourceLoader()));
        PageRoutes routes = new PageRoutes(new SiteRenderer(resources), loader.loadBundledDefault());
        Javalin app = Javalin.create();
        routes.register(app);

        JavalinTest.test(app, (server, client) -> {
            assertThat(client.get("/").body().string()).contains("茎崎");

            routes.reload(loader.parse("""
                    school: {name_ja: "別の学校", name_short: "別"}
                    festival: {name: "別の祭", start_date: "2026-10-03"}
                    pages:
                      - {key: index, route: "/", template: index.html}
                    """));

            assertThat(client.get("/").body().string())
                    .contains("別の祭")
                    .doesNotContain("茎崎");
        });
    }
}
