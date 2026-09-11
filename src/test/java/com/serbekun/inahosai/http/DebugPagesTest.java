package com.serbekun.inahosai.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.config.SiteConfigLoader;
import com.serbekun.inahosai.http.handles.DebugPages;
import com.serbekun.inahosai.resources.ResourceCache;
import com.serbekun.inahosai.resources.ResourceLoader;
import com.serbekun.inahosai.service.resource.ResourcesService;

class DebugPagesTest {

    private final SiteConfigLoader loader = new SiteConfigLoader();

    private Javalin appFor(SiteConfig config) {
        ResourcesService resources =
                new ResourcesService(new ResourceCache(new ResourceLoader()));
        Javalin app = Javalin.create();
        new DebugPages(resources, config).register(app);
        return app;
    }

    private SiteConfig config(boolean enabled, String token) {
        return loader.parse("""
                debug: {enabled: %s, token: "%s"}
                pages:
                  - {key: index, route: "/", template: index.html}
                """.formatted(enabled, token));
    }

    @Test
    void theMountIsNotRegisteredWhileDisabled() {
        JavalinTest.test(appFor(config(false, "")), (server, client) ->
                assertThat(client.get("/api/v0/debug/point_text.html").code()).isEqualTo(404));
    }

    @Test
    void aPlaygroundFileIsServedWhenEnabled() {
        JavalinTest.test(appFor(config(true, "")), (server, client) -> {
            var response = client.get("/api/v0/debug/point_text.html");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("Point Text");
        });
    }

    @Test
    void playgroundFilesAreServedAsUtf8HtmlAndNotCached() {
        JavalinTest.test(appFor(config(true, "")), (server, client) -> {
            var response = client.get("/api/v0/debug/point_text.html");

            assertThat(response.header("Content-Type"))
                    .startsWith("text/html").contains("charset=utf-8");
            assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        });
    }

    @Test
    void aConfiguredPageTemplateIsNotServedRaw() {
        JavalinTest.test(appFor(config(true, "")), (server, client) ->
                assertThat(client.get("/api/v0/debug/index.html").code()).isEqualTo(404));
    }

    @Test
    void theNotFoundAndSetupTemplatesAreNotServedRaw() {
        JavalinTest.test(appFor(config(true, "")), (server, client) -> {
            assertThat(client.get("/api/v0/debug/404.html").code()).isEqualTo(404);
            assertThat(client.get("/api/v0/debug/setup.html").code()).isEqualTo(404);
        });
    }

    @Test
    void aMissingOrNonHtmlFileIsNotFound() {
        JavalinTest.test(appFor(config(true, "")), (server, client) -> {
            assertThat(client.get("/api/v0/debug/nope.html").code()).isEqualTo(404);
            assertThat(client.get("/api/v0/debug/styles.css").code()).isEqualTo(404);
        });
    }

    @Test
    void aTokenGateRefusesRequestsWithoutTheToken() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) ->
                assertThat(client.get("/api/v0/debug/point_text.html").code()).isEqualTo(404));
    }

    @Test
    void aTokenGateServesTheFileWithTheRightToken() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) -> {
            var response = client.get("/api/v0/debug/point_text.html?token=SECRET");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("Point Text");
        });
    }

    @Test
    void aTokenGateRefusesAWrongToken() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) ->
                assertThat(client.get("/api/v0/debug/point_text.html?token=wrong").code())
                        .isEqualTo(404));
    }
}
