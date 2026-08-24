package com.serbekun.bunkasai.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.config.SiteConfigLoader;
import com.serbekun.bunkasai.http.handles.AdminReload;
import com.serbekun.bunkasai.http.handles.PageRoutes;
import com.serbekun.bunkasai.http.handles.SetupPage;
import com.serbekun.bunkasai.render.SiteRenderer;
import com.serbekun.bunkasai.resources.ResourceCache;
import com.serbekun.bunkasai.resources.ResourceLoader;
import com.serbekun.bunkasai.service.resource.ResourcesService;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminReloadTest {

    private static final String TOKEN = "s3cret-token";
    private static final String ROUTE = "/api/v0/admin/reload";

    @TempDir
    Path tempDir;

    private final SiteConfigLoader loader = new SiteConfigLoader();

    /** A config source pinned to one file, standing in for the environment resolution. */
    private Supplier<SiteConfig> loaderFor(Path configFile) {
        return () -> loader.loadFile(configFile);
    }

    private Javalin appWith(Supplier<SiteConfig> configSource, String token) {
        ResourcesService resources =
                new ResourcesService(new ResourceCache(new ResourceLoader()));
        SiteRenderer renderer = new SiteRenderer(resources);
        SiteConfig initial = loader.loadBundledDefault();
        PageRoutes pageRoutes = new PageRoutes(renderer, initial);
        SetupPage setupPage = new SetupPage(renderer, initial, false);

        Javalin app = Javalin.create();
        pageRoutes.register(app);
        new AdminReload(configSource, pageRoutes, setupPage, token).register(app);
        return app;
    }

    private Path writeConfig(String yaml) throws Exception {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void theRouteIsNotRegisteredWhenNoTokenIsSet() {
        JavalinTest.test(appWith(loader::loadBundledDefault, null), (server, client) ->
                assertThat(client.post(ROUTE).code()).isEqualTo(404));
    }

    @Test
    void theRouteIsNotRegisteredWhenTheTokenIsBlank() {
        JavalinTest.test(appWith(loader::loadBundledDefault, "   "), (server, client) ->
                assertThat(client.post(ROUTE).code()).isEqualTo(404));
    }

    @Test
    void aRequestWithoutATokenIsRejected() {
        JavalinTest.test(appWith(loader::loadBundledDefault, TOKEN), (server, client) ->
                assertThat(client.post(ROUTE).code()).isEqualTo(401));
    }

    @Test
    void aRequestWithTheWrongTokenIsRejected() {
        JavalinTest.test(appWith(loader::loadBundledDefault, TOKEN), (server, client) -> {
            var response = client.post(ROUTE, null, request ->
                    request.header("Authorization", "Bearer wrong-token"));

            assertThat(response.code()).isEqualTo(401);
        });
    }

    @Test
    void aTokenWithoutTheBearerPrefixIsRejected() {
        JavalinTest.test(appWith(loader::loadBundledDefault, TOKEN), (server, client) -> {
            var response = client.post(ROUTE, null, request ->
                    request.header("Authorization", TOKEN));

            assertThat(response.code()).isEqualTo(401);
        });
    }

    @Test
    void aValidRequestReloadsTheConfigAndSwapsThePages() throws Exception {
        Path config = writeConfig("""
                school: {name_ja: "別の学校"}
                festival: {name: "別の祭", slogan: "むすぶ", start_date: "2026-10-03"}
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        JavalinTest.test(appWith(loaderFor(config), TOKEN), (server, client) -> {
            assertThat(client.get("/").body().string()).contains("稲穂祭");

            var response = client.post(ROUTE, null, request ->
                    request.header("Authorization", "Bearer " + TOKEN));

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"reloaded\":1");
            assertThat(client.get("/").body().string())
                    .contains("別の祭")
                    .doesNotContain("稲穂祭");
        });
    }

    @Test
    void reloadingChangesTheEtag() throws Exception {
        Path config = writeConfig("""
                school: {name_ja: "別の学校"}
                festival: {name: "別の祭", start_date: "2026-10-03"}
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        JavalinTest.test(appWith(loaderFor(config), TOKEN), (server, client) -> {
            String before = client.get("/").header("ETag");

            client.post(ROUTE, null, request ->
                    request.header("Authorization", "Bearer " + TOKEN));

            assertThat(client.get("/").header("ETag")).isNotEqualTo(before);
        });
    }

    @Test
    void anInvalidConfigLeavesTheRunningSiteUntouched() throws Exception {
        Path config = writeConfig("school:\n  nmae_ja: \"typo\"\n");

        JavalinTest.test(appWith(loaderFor(config), TOKEN), (server, client) -> {
            String before = client.get("/").body().string();

            var response = client.post(ROUTE, null, request ->
                    request.header("Authorization", "Bearer " + TOKEN));

            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("nmae_ja");
            // A bad edit costs the call, not the site.
            assertThat(client.get("/").body().string()).isEqualTo(before);
        });
    }

    @Test
    void theReloadResponseReportsWhatIsStillUnset() throws Exception {
        Path config = writeConfig("""
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        JavalinTest.test(appWith(loaderFor(config), TOKEN), (server, client) -> {
            var response = client.post(ROUTE, null, request ->
                    request.header("Authorization", "Bearer " + TOKEN));

            assertThat(response.body().string())
                    .contains("school.name_ja")
                    .contains("festival.start_date");
        });
    }
}
