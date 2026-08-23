package com.serbekun.bunkasai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SiteConfigTest {

    private final SiteConfigLoader loader = new SiteConfigLoader();

    // region Bundled default

    @Test
    void bundledDefaultConfigParses() {
        SiteConfig config = loader.loadBundledDefault();

        assertThat(config.school().nameJa()).isEqualTo("茎崎");
        assertThat(config.festival().name()).isEqualTo("稲穂祭");
        assertThat(config.festival().slogan()).isEqualTo("つなぐ");
        assertThat(config.festival().startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(config.pages()).hasSize(5);
        assertThat(config.graph().branches()).hasSize(4);
    }

    @Test
    void bundledDefaultConfigIsConfigured() {
        assertThat(loader.loadBundledDefault().missingKeys()).isEmpty();
    }

    @Test
    void bundledDefaultKeepsGraphBranchesPairedWithTheirRoutes() {
        SiteConfig config = loader.loadBundledDefault();

        assertThat(config.graph().branches())
                .extracting(SiteConfig.Branch::label, SiteConfig.Branch::url)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("時間をつなぐ", "/jikan"),
                        org.assertj.core.groups.Tuple.tuple("学びをつなぐ", "/manabi"),
                        org.assertj.core.groups.Tuple.tuple("場所をつなぐ", "/basho"),
                        org.assertj.core.groups.Tuple.tuple("世界をつなぐ", "/sekai"));
    }

    // endregion

    // region Unknown keys

    @Test
    void unknownKeyFailsLoudly() {
        assertThatThrownBy(() -> loader.parse("school:\n  nmae_ja: \"typo\"\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nmae_ja");
    }

    @Test
    void emptyDocumentLoadsAsUnconfigured() {
        SiteConfig config = loader.parse("");

        assertThat(config.school().nameJa()).isEmpty();
        assertThat(config.pages()).isEmpty();
        assertThat(config.isConfigured()).isFalse();
    }

    // endregion

    // region youtube_id validation

    @Test
    void validYoutubeIdIsAccepted() {
        assertThat(new SiteConfig.Stream("dQw4w9WgXcQ").youtubeId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(new SiteConfig.Stream("aB3-_9xYzQ1").youtubeId()).isEqualTo("aB3-_9xYzQ1");
    }

    @Test
    void shortYoutubeIdIsRejected() {
        assertThat(new SiteConfig.Stream("abc").youtubeId()).isEmpty();
    }

    @Test
    void youtubeUrlIsRejected() {
        assertThat(new SiteConfig.Stream("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .youtubeId()).isEmpty();
    }

    @Test
    void youtubeIdWithMarkupOrQuotesIsRejected() {
        assertThat(new SiteConfig.Stream("dQw4\"w9WgX").youtubeId()).isEmpty();
        assertThat(new SiteConfig.Stream("<script>abc").youtubeId()).isEmpty();
        assertThat(new SiteConfig.Stream("\" onload=\"x").youtubeId()).isEmpty();
    }

    // endregion

    // region missingKeys

    @Test
    void missingKeysListsEveryRequiredKeyOnAnEmptyConfig() {
        assertThat(SiteConfigLoader.emptyConfig().missingKeys())
                .containsExactly("school.name_ja", "festival.name", "festival.start_date");
    }

    @Test
    void missingKeysIsEmptyOnAFullyPopulatedConfig() {
        SiteConfig config = loader.parse("""
                school:
                  name_ja: "茎崎"
                festival:
                  name: "稲穂祭"
                  start_date: "2026-05-01"
                """);

        assertThat(config.missingKeys()).isEmpty();
        assertThat(config.isConfigured()).isTrue();
    }

    @Test
    void missingKeysReportsOnlyWhatIsActuallyUnset() {
        SiteConfig config = loader.parse("""
                school:
                  name_ja: "茎崎"
                festival:
                  name: "稲穂祭"
                """);

        assertThat(config.missingKeys()).containsExactly("festival.start_date");
    }

    // endregion

    // region Other field validation

    @Test
    void heroPhotoMustBeAPlainFilename() {
        assertThat(new SiteConfig.Hero("school.png", "").photo()).isEqualTo("school.png");
        assertThat(new SiteConfig.Hero("../../etc/passwd", "").photo()).isEmpty();
        assertThat(new SiteConfig.Hero("sub/dir.png", "").photo()).isEmpty();
        assertThat(new SiteConfig.Hero("%2e%2e/x.png", "").photo()).isEmpty();
    }

    @Test
    void staticPrefixMustBeRootedAndNotClimb() {
        assertThat(prefix("/static/v0")).isEqualTo("/static/v0");
        assertThat(prefix("/static/v0/")).isEqualTo("/static/v0");
        assertThat(prefix("static/v0")).isEmpty();
        assertThat(prefix("/static/../..")).isEmpty();
    }

    @Test
    void linkUrlAcceptsAbsoluteHttpAndSiteRelativePaths() {
        assertThat(new SiteConfig.WorkItem("t", "d", "https://example.com/x").url())
                .isEqualTo("https://example.com/x");
        assertThat(new SiteConfig.WorkItem("t", "d", "/jikan").url()).isEqualTo("/jikan");
        assertThat(new SiteConfig.WorkItem("t", "d", "").url()).isEmpty();
    }

    @Test
    void linkUrlRejectsScriptAndProtocolRelativeDestinations() {
        assertThat(new SiteConfig.WorkItem("t", "d", "javascript:alert(1)").url()).isEmpty();
        assertThat(new SiteConfig.WorkItem("t", "d", "data:text/html,<script>").url()).isEmpty();
        assertThat(new SiteConfig.WorkItem("t", "d", "//evil.example.com").url()).isEmpty();
        assertThat(new SiteConfig.WorkItem("t", "d", "/../../secret").url()).isEmpty();
    }

    private static String prefix(String value) {
        return new SiteConfig.Site(value, "", "", "", "").staticPrefix();
    }

    // endregion
}
