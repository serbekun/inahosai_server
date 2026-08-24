package com.serbekun.bunkasai.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.serbekun.bunkasai.config.AppEnv;
import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.config.SiteConfigLoader;
import com.serbekun.bunkasai.http.handles.SetupPage;
import com.serbekun.bunkasai.render.SiteRenderer;
import com.serbekun.bunkasai.resources.ResourceCache;
import com.serbekun.bunkasai.resources.ResourceLoader;
import com.serbekun.bunkasai.service.resource.ResourcesService;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;

class SetupPageTest {

    private final SiteConfigLoader loader = new SiteConfigLoader();

    private Javalin appFor(SiteConfig config, boolean enabled) {
        ResourcesService resources =
                new ResourcesService(new ResourceCache(new ResourceLoader()));
        Javalin app = Javalin.create();
        new SetupPage(new SiteRenderer(resources), config, enabled).register(app);
        return app;
    }

    @Test
    void onlyAnExplicitDevValueSelectsDevelopmentMode() {
        assertThat(AppEnv.isDev("dev")).isTrue();
        assertThat(AppEnv.isDev(" DEV ")).isTrue();
        // Unset means production, so a fork that forgets gets the safe behaviour.
        assertThat(AppEnv.isDev(null)).isFalse();
        assertThat(AppEnv.isDev("")).isFalse();
        assertThat(AppEnv.isDev("prod")).isFalse();
        assertThat(AppEnv.isDev("development")).isFalse();
    }

    @Test
    void theSetupRouteIsNotRegisteredInProduction() {
        JavalinTest.test(appFor(loader.loadBundledDefault(), false), (server, client) ->
                assertThat(client.get("/setup").code()).isEqualTo(404));
    }

    @Test
    void theSetupPageListsEveryKeyWithItsStatus() {
        JavalinTest.test(appFor(loader.loadBundledDefault(), true), (server, client) -> {
            String body = client.get("/setup").body().string();

            assertThat(body)
                    .contains("school.name_ja")
                    .contains("festival.start_date")
                    .contains("stream.youtube_id")
                    .contains("site.base_url")
                    .contains("set")
                    .contains("unset");
        });
    }

    @Test
    void theSetupPageNeverRendersAConfigValue() {
        SiteConfig config = loader.parse("""
                school: {name_ja: "SECRET-SCHOOL-NAME"}
                festival: {name: "SECRET-FESTIVAL", start_date: "2026-10-03"}
                stream: {youtube_id: "dQw4w9WgXcQ"}
                site: {base_url: "https://secret.example.com"}
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        JavalinTest.test(appFor(config, true), (server, client) -> {
            String body = client.get("/setup").body().string();

            // The festival name legitimately appears in the shared header and the school
            // name in the shared footer, so the key listing — everything above the
            // footer — is what must not echo a value back.
            String listing = body.substring(0, body.indexOf("<footer"));
            assertThat(listing).doesNotContain("SECRET-SCHOOL-NAME");
            assertThat(body).doesNotContain("dQw4w9WgXcQ");
            assertThat(body).doesNotContain("secret.example.com");
        });
    }

    @Test
    void theSetupPageIsNotCached() {
        JavalinTest.test(appFor(loader.loadBundledDefault(), true), (server, client) ->
                assertThat(client.get("/setup").header("Cache-Control")).isEqualTo("no-store"));
    }

    @Test
    void anUnsetRequiredKeyIsReportedAsUnset() {
        SiteConfig config = loader.parse("""
                festival: {name: "稲穂祭"}
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        JavalinTest.test(appFor(config, true), (server, client) -> {
            String body = client.get("/setup").body().string();

            assertThat(body).contains("setup-banner");
            assertThat(body).contains("unset");
        });
    }
}
