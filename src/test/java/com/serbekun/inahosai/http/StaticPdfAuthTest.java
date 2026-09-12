package com.serbekun.inahosai.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.TestCase;
import io.javalin.testtools.TestConfig;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.junit.jupiter.api.Test;

import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.config.SiteConfigLoader;
import com.serbekun.inahosai.http.handles.StaticRoutes;
import com.serbekun.inahosai.resources.ResourceCache;
import com.serbekun.inahosai.resources.ResourceLoader;
import com.serbekun.inahosai.service.resource.ResourcesService;

class StaticPdfAuthTest {

    private static final MediaType FORM =
            MediaType.get("application/x-www-form-urlencoded");

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

    private SiteConfig secureConfig(String token) {
        return loader.parse("""
                site: {base_url: "https://example.com"}
                pdf:
                  require_auth: true
                  token: "%s"
                """.formatted(token));
    }

    private static RequestBody unlockForm(String token, String next) {
        return RequestBody.create("token=" + token + "&next=" + next, FORM);
    }

    private static String cookiePair(Response response) {
        String setCookie = response.header("Set-Cookie");
        assertThat(setCookie).isNotNull();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    /** Runs a case with redirects disabled, so the 303 and its headers are inspectable. */
    private static void testNoRedirect(Javalin app, TestCase test) {
        TestConfig config = new TestConfig(false, false,
                new OkHttpClient.Builder().followRedirects(false).build());
        JavalinTest.test(app, config, test);
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
    void pdfResponsesArePrivateNoStoreAndNoindex() {
        JavalinTest.test(appFor(config(false, "")), (server, client) -> {
            var response = client.get("/static/v0/pdf/sample.pdf");

            assertThat(response.header("Cache-Control")).isEqualTo("private, no-store");
            assertThat(response.header("X-Robots-Tag")).isEqualTo("noindex, nofollow");
        });
    }

    @Test
    void otherStaticKindsKeepThePublicCacheAndIndexability() {
        JavalinTest.test(appFor(config(false, "")), (server, client) -> {
            var response = client.get("/static/v0/css/styles.css");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Cache-Control")).isEqualTo("public, max-age=300");
            assertThat(response.header("X-Robots-Tag")).isNull();
        });
    }

    @Test
    void aPdfIsRefusedWithoutTheCookie() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) ->
                assertThat(client.get("/static/v0/pdf/sample.pdf").code()).isEqualTo(401));
    }

    @Test
    void theTokenInTheUrlIsNotAcceptedAnyMore() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) ->
                assertThat(client.get("/static/v0/pdf/sample.pdf?token=SECRET").code())
                        .isEqualTo(401));
    }

    @Test
    void theUnlockFormSetsAnHttpOnlyCookieAndRedirectsWithoutTheToken() {
        testNoRedirect(appFor(secureConfig("SECRET")), (server, client) -> {
            Response response = client.request("/api/v0/pdf/unlock",
                    req -> req.post(unlockForm("SECRET", "/manabi")));

            assertThat(response.code()).isEqualTo(303);
            assertThat(response.header("Location")).isEqualTo("/manabi");
            assertThat(response.header("Location")).doesNotContain("SECRET");

            assertThat(response.header("Set-Cookie"))
                    .contains("inahosai_pdf=")
                    .contains("HttpOnly")
                    .contains("SameSite=Lax")
                    .contains("Path=/static/v0/pdf")
                    .contains("Secure")
                    .doesNotContain("SECRET");
        });
    }

    @Test
    void aWrongUnlockTokenIsRefusedAndSetsNoCookie() {
        testNoRedirect(appFor(config(true, "SECRET")), (server, client) -> {
            Response response = client.request("/api/v0/pdf/unlock",
                    req -> req.post(unlockForm("wrong", "/manabi")));

            assertThat(response.code()).isEqualTo(401);
            assertThat(response.header("Set-Cookie")).isNull();
        });
    }

    @Test
    void theUnlockRedirectRefusesToLeaveTheSite() {
        testNoRedirect(appFor(config(true, "SECRET")), (server, client) -> {
            Response response = client.request("/api/v0/pdf/unlock",
                    req -> req.post(unlockForm("SECRET", "//evil.example/steal")));

            assertThat(response.header("Location")).isEqualTo("/");
        });
    }

    @Test
    void aPdfIsServedWithTheUnlockCookie() {
        testNoRedirect(appFor(config(true, "SECRET")), (server, client) -> {
            Response unlock = client.request("/api/v0/pdf/unlock",
                    req -> req.post(unlockForm("SECRET", "/")));
            String cookie = cookiePair(unlock);

            Response response = client.get("/static/v0/pdf/sample.pdf",
                    req -> req.header("Cookie", cookie));

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Type")).isEqualTo("application/pdf");
            assertThat(response.header("Cache-Control")).isEqualTo("private, no-store");
        });
    }

    @Test
    void theUnlockCookieOnlyAuthorizesThePdfDirectory() {
        testNoRedirect(appFor(config(true, "SECRET")), (server, client) -> {
            Response unlock = client.request("/api/v0/pdf/unlock",
                    req -> req.post(unlockForm("SECRET", "/")));
            String cookie = cookiePair(unlock);

            assertThat(client.get("/static/v0/css/styles.css",
                    req -> req.header("Cookie", cookie)).code()).isEqualTo(200);
        });
    }

    @Test
    void thePdfListingIsGatedToo() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) ->
                assertThat(client.get("/static/v0/pdf").code()).isEqualTo(401));
    }

    @Test
    void otherStaticKindsAreNotGated() {
        JavalinTest.test(appFor(config(true, "SECRET")), (server, client) ->
                assertThat(client.get("/static/v0/css/styles.css").code()).isEqualTo(200));
    }

    @Test
    void theUnlockRouteIsNotRegisteredWhileTheGateIsOff() {
        testNoRedirect(appFor(config(false, "")), (server, client) -> {
            Response response = client.request("/api/v0/pdf/unlock",
                    req -> req.post(unlockForm("SECRET", "/")));

            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void anAuthDemandWithoutATokenLeavesPdfsOpen() {
        JavalinTest.test(appFor(config(true, "")), (server, client) ->
                assertThat(client.get("/static/v0/pdf/sample.pdf").code()).isEqualTo(200));
    }
}
