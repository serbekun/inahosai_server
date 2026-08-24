package com.serbekun.bunkasai.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The catalogue of config keys, used to tell a fork what it still has to fill in.
 *
 * <p>Only a key's name, description and whether it is set are ever exposed. The values
 * themselves are never reported: nothing in this config is secret today, but a listing
 * endpoint that prints config values is the kind of thing that becomes a leak the moment
 * somebody adds a key that is.
 *
 * <p>The catalogue is written out by hand rather than derived by reflection so each key
 * carries an explanation a reader can act on.
 */
public final class ConfigKeys {

    private ConfigKeys() {}

    /**
     * One config key.
     *
     * @param name        the key in its YAML spelling, e.g. {@code school.name_ja}
     * @param description what the key controls
     * @param required    whether the site is meaningfully configured without it
     * @param isSet       tests whether the key has a value
     */
    public record Key(String name, String description, boolean required,
                      Predicate<SiteConfig> isSet) {}

    private static final List<Key> KEYS = List.of(
            new Key("school.name_ja", "School name in Japanese. Shown in the hero and footer.",
                    true, c -> !c.school().nameJa().isEmpty()),
            new Key("school.name_short", "Short school name. Also the favicon glyph.",
                    false, c -> !c.school().nameShort().isEmpty()),
            new Key("school.name_latin", "Latin transliteration shown under the hero title.",
                    false, c -> !c.school().nameLatin().isEmpty()),

            new Key("festival.name", "Festival name. Shown in the header, hero and footer.",
                    true, c -> !c.festival().name().isEmpty()),
            new Key("festival.slogan", "One-word theme. Drives the hero slogan.",
                    false, c -> !c.festival().slogan().isEmpty()),
            new Key("festival.start_date",
                    "First day of the festival. The Japanese era is derived from this.",
                    true, c -> c.festival().startDate() != null),
            new Key("festival.end_date", "Last day of the festival. Optional.",
                    false, c -> c.festival().endDate() != null),
            new Key("festival.concept_lead", "Lines of the CONCEPT paragraph.",
                    false, c -> !c.festival().conceptLead().isEmpty()),

            new Key("hero.photo",
                    "Image filename for the hero backdrop. Unset renders no image.",
                    false, c -> !c.hero().photo().isEmpty()),
            new Key("hero.dots_text", "Fallback text drawn as particles on the hero canvas.",
                    false, c -> !c.hero().dotsText().isEmpty()),

            new Key("stream.youtube_id",
                    "11-character YouTube video id. Unset or malformed means no stream.",
                    false, c -> !c.stream().youtubeId().isEmpty()),

            new Key("works.items", "Student works listed on the 学び page.",
                    false, c -> !c.works().items().isEmpty()),

            new Key("graph.center", "Centre word of the theme graph.",
                    false, c -> !c.graph().center().text().isEmpty()),
            new Key("graph.branches", "Branches of the theme graph, with their positions.",
                    false, c -> !c.graph().branches().isEmpty()),

            new Key("site.static_prefix", "Where static assets are mounted.",
                    false, c -> !c.site().staticPrefix().isEmpty()),
            new Key("site.base_url",
                    "Public origin. Without it og:url and og:image are omitted.",
                    false, c -> !c.site().baseUrl().isEmpty()),
            new Key("site.gate_url", "Destination of the extra header link. Unset hides it.",
                    false, c -> !c.site().gateUrl().isEmpty()),
            new Key("site.gate_label", "Text of that link.",
                    false, c -> !c.site().gateLabel().isEmpty()),
            new Key("site.apple_touch_icon",
                    "Image filename for the iOS icon. Unset omits the tag.",
                    false, c -> !c.site().appleTouchIcon().isEmpty()),
            new Key("site.og_image",
                    "Image filename for link previews. Falls back to hero.photo.",
                    false, c -> !c.site().ogImage().isEmpty()),

            new Key("pages", "The pages of the site, their routes and their nav labels.",
                    false, c -> !c.pages().isEmpty()));

    /**
     * The catalogue.
     *
     * @return every known config key
     */
    public static List<Key> keys() {
        return KEYS;
    }

    /**
     * Reports which keys are set, without reporting any value.
     *
     * @param config the config to inspect
     * @return one entry per key, carrying its name, description and set/unset status
     */
    public static List<Map<String, Object>> status(SiteConfig config) {
        List<Map<String, Object>> status = new ArrayList<>(KEYS.size());
        for (Key key : KEYS) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", key.name());
            entry.put("description", key.description());
            entry.put("required", key.required());
            entry.put("set", key.isSet().test(config));
            status.add(entry);
        }
        return status;
    }
}
