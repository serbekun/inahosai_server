package com.serbekun.inahosai.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.config.SiteConfigLoader;
import com.serbekun.inahosai.http.handles.WorksRoutes;
import com.serbekun.inahosai.render.SiteRenderer;
import com.serbekun.inahosai.resources.ResourceCache;
import com.serbekun.inahosai.resources.ResourceLoader;
import com.serbekun.inahosai.service.resource.ResourcesService;

class WorksRoutesTest {

    private final SiteConfigLoader loader = new SiteConfigLoader();

    private WorksRoutes routesFor(SiteConfig config) {
        ResourcesService resources =
                new ResourcesService(new ResourceCache(new ResourceLoader()));
        return new WorksRoutes(new SiteRenderer(resources), config);
    }

    private Javalin appWith(WorksRoutes routes) {
        Javalin app = Javalin.create();
        routes.register(app);
        return app;
    }

    @Test
    void aMultiFileWorkIsServedAtItsChooserRoute() {
        JavalinTest.test(appWith(routesFor(loader.loadBundledDefault())), (server, client) -> {
            var response = client.get("/works/bijutsu");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Type")).startsWith("text/html");
            assertThat(response.body().string())
                    .contains("美術作品")
                    .contains("1年生")
                    .contains("2年生");
        });
    }

    @Test
    void aSingleFileWorkHasNoChooserRoute() {
        JavalinTest.test(appWith(routesFor(loader.loadBundledDefault())), (server, client) ->
                assertThat(client.get("/works/shodo").code()).isEqualTo(404));
    }

    @Test
    void anUnknownWorkIsNotFound() {
        JavalinTest.test(appWith(routesFor(loader.loadBundledDefault())), (server, client) ->
                assertThat(client.get("/works/nope").code()).isEqualTo(404));
    }

    @Test
    void aChooserPageCarriesAnEtagAndCacheControl() {
        JavalinTest.test(appWith(routesFor(loader.loadBundledDefault())), (server, client) -> {
            var response = client.get("/works/bijutsu");

            assertThat(response.header("ETag")).isNotBlank();
            assertThat(response.header("Cache-Control")).isEqualTo("public, max-age=300");
        });
    }

    @Test
    void reloadSwapsTheChooserBodies() {
        WorksRoutes routes = routesFor(loader.loadBundledDefault());

        JavalinTest.test(appWith(routes), (server, client) -> {
            assertThat(client.get("/works/bijutsu").body().string()).contains("美術作品");

            routes.reload(loader.parse("""
                    school: {name_ja: "別の学校"}
                    festival: {name: "別の祭", start_date: "2026-10-03"}
                    works:
                      items:
                        - key: bijutsu
                          title: "別の作品"
                          description: "d"
                          files:
                            - {name: "A", url: "/static/v0/pdf/a.pdf"}
                            - {name: "B", url: "/static/v0/pdf/b.pdf"}
                    pages:
                      - {key: manabi, route: "/manabi", template: manabi.html}
                    """));

            assertThat(client.get("/works/bijutsu").body().string())
                    .contains("別の作品")
                    .doesNotContain("美術作品");
        });
    }
}
