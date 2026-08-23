package com.serbekun.bunkasai.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.config.SiteConfigLoader;
import com.serbekun.bunkasai.resources.ResourceCache;
import com.serbekun.bunkasai.resources.ResourceLoader;
import com.serbekun.bunkasai.service.resource.ResourcesService;

import org.junit.jupiter.api.Test;

class SiteRendererTest {

    private final SiteConfigLoader loader = new SiteConfigLoader();
    private final SiteRenderer renderer =
            new SiteRenderer(new ResourcesService(new ResourceCache(new ResourceLoader())));

    private SiteConfig defaultConfig() {
        return loader.loadBundledDefault();
    }

    private String render(SiteConfig config, String route) {
        RenderedPage page = renderer.renderAll(config).get(route);
        assertThat(page).as("page at %s", route).isNotNull();
        return new String(page.body(), StandardCharsets.UTF_8);
    }

    // region Routes

    @Test
    void rendersEveryConfiguredPageAtItsRoute() {
        assertThat(renderer.renderAll(defaultConfig()))
                .containsOnlyKeys("/", "/jikan", "/manabi", "/basho", "/sekai");
    }

    @Test
    void renderAllExcludesPartialsAndUnlistedTemplates() {
        Map<String, RenderedPage> pages = renderer.renderAll(defaultConfig());

        // Partials and the point_text.html debug playground are not configured pages, so
        // they are excluded by construction rather than by a filter.
        assertThat(pages.keySet()).noneMatch(route -> route.contains("partials"));
        assertThat(pages.keySet()).noneMatch(route -> route.contains("point_text"));
        assertThat(pages).doesNotContainKey("/404");
    }

    @Test
    void renderedMapIsImmutable() {
        Map<String, RenderedPage> pages = renderer.renderAll(defaultConfig());

        assertThat(pages.getClass().getName()).contains("Immutable");
    }

    // endregion

    // region Shared chrome

    @Test
    void everyPageGetsTheSameNavigation() {
        for (String route : new String[] {"/", "/jikan", "/manabi", "/basho", "/sekai"}) {
            String html = render(defaultConfig(), route);
            assertThat(html).as("nav on %s", route)
                    .contains("href=\"/jikan\"")
                    .contains("href=\"/manabi\"")
                    .contains("href=\"/basho\"")
                    .contains("href=\"/sekai\"");
        }
    }

    @Test
    void theCurrentPageIsTheOnlyOneMarkedAsCurrent() {
        String html = render(defaultConfig(), "/jikan");

        assertThat(html.split("aria-current=\"page\"", -1)).hasSize(2);
        assertThat(html).contains("href=\"/jikan\" aria-current=\"page\"");
    }

    @Test
    void theEraIsRenderedFromTheConfiguredStartDate() {
        SiteConfig config = loader.parse("""
                school: {name_ja: "茎崎"}
                festival: {name: "稲穂祭", start_date: "2026-10-03"}
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        assertThat(render(config, "/")).contains("令和8年");
    }

    // endregion

    // region Escaping

    @Test
    void aSchoolNameContainingMarkupIsEscaped() {
        SiteConfig config = loader.parse("""
                school: {name_ja: "<script>alert(1)</script>"}
                festival: {name: "稲穂祭", start_date: "2026-10-03"}
                hero: {photo: "school.png"}
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        String html = render(config, "/");

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void aConfigValueContainingAClosingScriptTagCannotBreakOutOfTheIsland() {
        SiteConfig config = loader.parse("""
                school: {name_ja: "茎崎"}
                festival: {name: "稲穂祭", start_date: "2026-10-03"}
                graph:
                  center: {text: "</script><script>alert(1)</script>", at: [0.5, 0.5]}
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        String html = render(config, "/");

        assertThat(html).doesNotContain("</script><script>");
        assertThat(html).contains("\\u003c/script\\u003e");
    }

    @Test
    void theIslandStillParsesAsJsonAndRoundTripsTheOriginalCharacters() throws Exception {
        String hostile = "</script> & <b>";
        SiteConfig config = loader.parse("""
                school: {name_ja: "茎崎"}
                festival: {name: "稲穂祭", start_date: "2026-10-03"}
                graph:
                  center: {text: "%s", at: [0.5, 0.5]}
                pages:
                  - {key: index, route: "/", template: index.html}
                """.formatted(hostile));

        String html = render(config, "/");
        String island = html.substring(
                html.indexOf("id=\"site-config\">") + "id=\"site-config\">".length());
        island = island.substring(0, island.indexOf("</script>"));

        JsonNode parsed = new ObjectMapper().readTree(island);

        assertThat(parsed.at("/graph/center/text").asText()).isEqualTo(hostile);
    }

    // endregion

    // region Absent config hides elements

    @Test
    void anUnsetWorkUrlProducesNoAnchorElement() {
        String html = render(defaultConfig(), "/manabi");

        assertThat(html).contains("書道作品");
        assertThat(html).doesNotContain("branchmap__btn");
        assertThat(html).doesNotContain("href=\"#\"");
    }

    @Test
    void aConfiguredWorkUrlProducesAnAnchorElement() {
        SiteConfig config = loader.parse("""
                school: {name_ja: "茎崎"}
                festival: {name: "稲穂祭", start_date: "2026-10-03"}
                works:
                  items:
                    - {title: "書道作品", description: "d", url: "https://example.com/works"}
                pages:
                  - {key: manabi, route: "/manabi", template: manabi.html}
                """);

        assertThat(render(config, "/manabi"))
                .contains("<a class=\"branchmap__btn\" href=\"https://example.com/works\">");
    }

    @Test
    void anUnsetHeroPhotoProducesNoImageElement() {
        SiteConfig config = loader.parse("""
                school: {name_ja: "茎崎"}
                festival: {name: "稲穂祭", start_date: "2026-10-03"}
                hero: {photo: ""}
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        assertThat(render(config, "/")).doesNotContain("hero__photo");
    }

    @Test
    void anUnsetAppleTouchIconProducesNoLinkElement() {
        // The tag used to point at an image that does not exist, 404ing on every page.
        assertThat(render(defaultConfig(), "/")).doesNotContain("apple-touch-icon");
    }

    @Test
    void anUnsetGateUrlProducesNoGateElement() {
        assertThat(render(defaultConfig(), "/")).doesNotContain("topbar__link--gate");
    }

    @Test
    void aConfiguredGateUrlProducesARealAnchor() {
        SiteConfig config = loader.parse("""
                school: {name_ja: "茎崎"}
                festival: {name: "稲穂祭", start_date: "2026-10-03"}
                site: {gate_url: "/gate", gate_label: "つながり門"}
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        String html = render(config, "/");

        assertThat(html).contains("<a class=\"topbar__link topbar__link--gate\" href=\"/gate\"");
        assertThat(html).doesNotContain("aria-hidden=\"true\"");
    }

    // endregion

    // region Setup banner

    @Test
    void anUnconfiguredSiteShowsTheSetupBanner() {
        SiteConfig config = loader.parse("""
                pages:
                  - {key: index, route: "/", template: index.html}
                """);

        assertThat(render(config, "/")).contains("setup-banner");
    }

    @Test
    void aConfiguredSiteShowsNoSetupBanner() {
        assertThat(render(defaultConfig(), "/")).doesNotContain("setup-banner");
    }

    // endregion

    // region ETag

    @Test
    void theEtagIsStableAcrossTwoRendersOfTheSameConfig() {
        String first = renderer.renderAll(defaultConfig()).get("/").etag();
        String second = renderer.renderAll(defaultConfig()).get("/").etag();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void theEtagChangesWhenTheConfigChanges() throws Exception {
        SiteConfig original = defaultConfig();
        SiteConfig changed = loader.parse(
                new String(getClass().getClassLoader()
                        .getResourceAsStream("config.default.yaml").readAllBytes(),
                        StandardCharsets.UTF_8)
                        .replace("name_ja: \"茎崎\"", "name_ja: \"別の学校\""));

        assertThat(renderer.renderAll(original).get("/").etag())
                .isNotEqualTo(renderer.renderAll(changed).get("/").etag());
    }

    @Test
    void theEtagIsAQuotedHexDigest() {
        assertThat(renderer.renderAll(defaultConfig()).get("/").etag())
                .matches("\"[0-9a-f]{16}\"");
    }

    // endregion

    // region Content type and 404

    @Test
    void htmlIsServedAsUtf8() {
        assertThat(renderer.renderAll(defaultConfig()).get("/").contentType())
                .isEqualTo("text/html; charset=utf-8");
    }

    @Test
    void theNotFoundPageRendersButIsNotARoute() {
        RenderedPage notFound = renderer.renderNotFound(defaultConfig());

        assertThat(notFound).isNotNull();
        assertThat(new String(notFound.body(), StandardCharsets.UTF_8)).contains("404");
        assertThat(renderer.renderAll(defaultConfig())).doesNotContainKey("/404");
    }

    // endregion

    // region Open Graph

    @Test
    void openGraphTagsAreRenderedIntoTheDeliveredHtml() {
        SiteConfig config = loader.parse("""
                school: {name_ja: "茎崎"}
                festival: {name: "稲穂祭", start_date: "2026-10-03"}
                hero: {photo: "school.png"}
                site: {static_prefix: "/static/v0", base_url: "https://example.com"}
                pages:
                  - {key: index, route: "/", template: index.html, title: "T", description: "D"}
                """);

        String html = render(config, "/");

        assertThat(html)
                .contains("<meta property=\"og:title\" content=\"T\">")
                .contains("<meta property=\"og:description\" content=\"D\">")
                .contains("<meta property=\"og:url\" content=\"https://example.com/\">")
                .contains("<meta property=\"og:image\" "
                        + "content=\"https://example.com/static/v0/images/school.png\">")
                .contains("<meta property=\"og:type\" content=\"website\">")
                .contains("<meta name=\"twitter:card\"");
    }

    @Test
    void openGraphUrlsAreOmittedWhenThePublicOriginIsUnknown() {
        // A relative og:image is useless to a crawler, so it is better left out.
        String html = render(defaultConfig(), "/");

        assertThat(html).doesNotContain("og:url");
        assertThat(html).doesNotContain("og:image");
    }

    @Test
    void theFestivalNameIsWrittenOnceAndReusedInTitles() {
        SiteConfig config = loader.parse("""
                school: {name_ja: "茎崎"}
                festival: {name: "稲穂祭", slogan: "つなぐ", start_date: "2026-10-03"}
                pages:
                  - key: index
                    route: "/"
                    template: index.html
                    title: "{{festival.name}} — {{festival.slogan}}"
                """);

        assertThat(render(config, "/")).contains("<title>稲穂祭 — つなぐ</title>");
    }

    // endregion
}
