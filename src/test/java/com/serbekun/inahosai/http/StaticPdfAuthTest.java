package com.serbekun.inahosai.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.config.SiteConfigLoader;
import com.serbekun.inahosai.http.handles.StaticRoutes;
import com.serbekun.inahosai.resources.ResourceCache;
import com.serbekun.inahosai.resources.ResourceLoader;
import com.serbekun.inahosai.service.resource.ResourcesService;

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
