package com.serbekun.bunkasai.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.config.SiteConfigLoader;
import com.serbekun.bunkasai.http.handles.StaticRoutes;
import com.serbekun.bunkasai.resources.ResourceCache;
import com.serbekun.bunkasai.resources.ResourceLoader;
import com.serbekun.bunkasai.service.resource.ResourcesService;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;

class StaticPdfAuthTest {

    private final SiteConfigLoader loader = new SiteConfigLoader();

    private Javalin appFor(SiteConfig config) {
        ResourcesService resources =
                new ResourcesService(new ResourceCache(new ResourceLoader()));
        Javalin app = Javalin.create();
        new StaticRoutes(resources, config).register(app);
        return app;
    }

    private SiteConfig config(boolean requireAuth, String token) {
        return loader.parse("""
                pdf:
                  require_auth: %s
                  token: "%s"
                """.formatted(requireAuth, token));
    }

    @Test
    void aPdfIsServedWhenTheGateIsOff() {
        JavalinTest.test(appFor(config(false, "")), (server, client) -> {
            var response = client.get("/static/v0/pdf/sample.pdf");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Type")).isEqualTo("application/pdf");
        });
    }

    @Test
    void aPdfIsRefusedWithoutTheToken() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) ->
                assertThat(client.get("/static/v0/pdf/sample.pdf").code()).isEqualTo(401));
    }

    @Test
    void aPdfIsRefusedWithAWrongToken() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) ->
                assertThat(client.get("/static/v0/pdf/sample.pdf?token=wrong").code())
                        .isEqualTo(401));
    }

    @Test
    void aPdfIsServedWithTheRightToken() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) -> {
            var response = client.get("/static/v0/pdf/sample.pdf?token=SECRET");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Type")).isEqualTo("application/pdf");
        });
    }

    @Test
    void thePdfListingIsGatedToo() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) -> {
            assertThat(client.get("/static/v0/pdf").code()).isEqualTo(401);
            assertThat(client.get("/static/v0/pdf?token=SECRET").code()).isEqualTo(200);
        });
    }

    @Test
    void otherStaticKindsAreNotGated() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) ->
                assertThat(client.get("/static/v0/css/styles.css").code()).isEqualTo(200));
    }

    @Test
    void anAuthDemandWithoutATokenLeavesPdfsOpen() {
        JavalinTest.test(appFor(config(true, "")), (server, client) ->
                assertThat(client.get("/static/v0/pdf/sample.pdf").code()).isEqualTo(200));
    }
}
