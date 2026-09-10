package com.serbekun.inahosai.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.config.SiteConfigLoader;
import com.serbekun.inahosai.http.handles.V0LiveType;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

import org.junit.jupiter.api.Test;

class V0LiveTypeTest {

    private final SiteConfigLoader loader = new SiteConfigLoader();

    private Javalin appFor(SiteConfig config) {
        Javalin app = Javalin.create();
        new V0LiveType(config).register(app);
        return app;
    }

    private SiteConfig config(String yaml) {
        return loader.parse(yaml);
    }

    @Test
    void reportsYoutubeWhenConfigured() {
        JavalinTest.test(appFor(config("""
                stream:
                  type: "youtube"
                  youtube_id: "dQw4w9WgXcQ"
                """)), (server, client) ->
                assertThat(client.get("/api/v0/live/type/").body().string())
                        .contains("\"type\":\"youtube\""));
    }

    @Test
    void reportsZoomWhenConfigured() {
        JavalinTest.test(appFor(config("""
                stream:
                  type: "zoom"
                  zoom_url: "https://zoom.us/j/1234567890"
                """)), (server, client) ->
                assertThat(client.get("/api/v0/live/type/").body().string())
                        .contains("\"type\":\"zoom\""));
    }

    @Test
    void reportsNoneWhenNothingIsConfigured() {
        JavalinTest.test(appFor(config("")), (server, client) ->
                assertThat(client.get("/api/v0/live/type/").body().string())
                        .contains("\"type\":\"none\""));
    }

    @Test
    void reportsNoneWhenTheSelectedPlatformHasNoPayload() {
        JavalinTest.test(appFor(config("""
                stream:
                  type: "zoom"
                """)), (server, client) ->
                assertThat(client.get("/api/v0/live/type/").body().string())
                        .contains("\"type\":\"none\""));
    }

    @Test
    void theTrailingSlashIsOptional() {
        JavalinTest.test(appFor(config("""
                stream:
                  type: "youtube"
                  youtube_id: "dQw4w9WgXcQ"
                """)), (server, client) ->
                assertThat(client.get("/api/v0/live/type").body().string())
                        .contains("\"type\":\"youtube\""));
    }
}
