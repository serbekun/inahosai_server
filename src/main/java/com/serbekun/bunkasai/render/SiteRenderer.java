package com.serbekun.bunkasai.render;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import com.serbekun.bunkasai.config.JapaneseEra;
import com.serbekun.bunkasai.config.SiteConfig;
import com.serbekun.bunkasai.resources.Etags;
import com.serbekun.bunkasai.service.resource.ResourcesService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders every page of the site once, at startup.
 *
 * <p>Rendering is server-side and eager. Server-side because the pages are shared into
 * LINE, whose crawler does not execute JavaScript, so the og: tags have to be in the
 * delivered HTML. Eager because five pages take milliseconds, and doing it per-request
 * would buy nothing but a chance to fail under load.
 *
 * <p>The templates are the site's real HTML files, not a separate copy of them.
 */
public class SiteRenderer {

    private static final Logger log = LoggerFactory.getLogger(SiteRenderer.class);

    /** Name of the template rendered for unmatched routes. */
    public static final String NOT_FOUND_TEMPLATE = "404.html";

    private final ResourcesService resources;
    private final Mustache.Compiler compiler;

    /**
     * A mapper for the JSON island only.
     *
     * <p>Deliberately not the loader's mapper: that one uses SNAKE_CASE for YAML, and the
     * browser-side code reads camelCase names such as {@code leafAt}.
     */
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * Creates a renderer.
     *
     * @param resources the resource API used to read templates and partials
     */
    public SiteRenderer(ResourcesService resources) {
        this.resources = resources;
        this.compiler = Mustache.compiler()
                // Escaping stays on for every value. The one place it is off is the JSON
                // island, which is escaped by hand instead -- see configJson below.
                .escapeHTML(true)
                // An unset config value is an empty string, and an empty string must not
                // make a {{#section}} render.
                .emptyStringIsFalse(true)
                // A key the model does not carry renders empty rather than throwing, so
                // one missing value cannot take a whole page down.
                .defaultValue("")
                .withLoader(this::loadPartial);
    }

    /**
     * Reads a partial by its Mustache name.
     *
     * @param name the partial name as written in {@code {{>name}}}
     * @return a reader over the partial's text
     * @throws IllegalStateException if the partial does not exist
     */
    private StringReader loadPartial(String name) {
        String text = resources.getPartial(name + ".html");
        if (text == null) {
            throw new IllegalStateException("Missing template partial: " + name + ".html");
        }
        return new StringReader(text);
    }

    /**
     * Renders every configured page.
     *
     * <p>Only the pages named in the config are rendered, so template fragments under
     * {@code html/partials/} and unlisted files such as the {@code point_text.html} debug
     * playground are excluded by construction rather than by a filter that could rot.
     *
     * @param config the site config
     * @return an immutable route to page map
     */
    public Map<String, RenderedPage> renderAll(SiteConfig config) {
        Map<String, RenderedPage> pages = new LinkedHashMap<>();

        for (SiteConfig.Page page : config.pages()) {
            if (page.route().isEmpty() || page.template().isEmpty()) {
                log.warn("Skipping page '{}': both route and template are required", page.key());
                continue;
            }
            String template = resources.getHtml(page.template());
            if (template == null) {
                log.warn("Skipping page '{}': template {} not found", page.key(), page.template());
                continue;
            }
            if (pages.containsKey(page.route())) {
                log.warn("Skipping page '{}': route {} is already taken", page.key(), page.route());
                continue;
            }
            pages.put(page.route(), render(template, model(config, page), page.template()));
        }

        log.info("Rendered {} page(s): {}", pages.size(), pages.keySet());
        return Map.copyOf(pages);
    }

    /**
     * Renders the 404 page.
     *
     * <p>Kept out of {@link #renderAll} because it is an error body, not a route — giving
     * it a URL would make it findable and indexable.
     *
     * @param config the site config
     * @return the rendered 404 page, or null if its template is missing
     */
    public RenderedPage renderNotFound(SiteConfig config) {
        String template = resources.getHtml(NOT_FOUND_TEMPLATE);
        if (template == null) {
            log.warn("No {} template found; unmatched routes will fall back to plain text",
                    NOT_FOUND_TEMPLATE);
            return null;
        }
        SiteConfig.Page page = new SiteConfig.Page(
                "404", "", NOT_FOUND_TEMPLATE, "", "",
                "404 — {{festival.name}}", "", "", "");
        return render(template, model(config, page), NOT_FOUND_TEMPLATE);
    }

    /**
     * Compiles and executes one template.
     *
     * @param template the template text
     * @param model    the values to render it against
     * @param name     the template's filename, used for the content type and error text
     * @return the rendered page
     */
    private RenderedPage render(String template, Map<String, Object> model, String name) {
        byte[] body = compiler.compile(template).execute(model).getBytes(StandardCharsets.UTF_8);
        return new RenderedPage(body, Etags.of(body), resources.detectMimeType(name));
    }

    // region Model

    /**
     * Builds the model for one page.
     *
     * <p>Every conditional is an explicit boolean rather than a value the engine has to
     * judge the truthiness of. That keeps the templates readable and makes "no photo
     * configured" mean exactly one thing.
     *
     * @param config the site config
     * @param page   the page being rendered
     * @return the model
     */
    private Map<String, Object> model(SiteConfig config, SiteConfig.Page page) {
        SiteConfig.Site site = config.site();
        String staticPrefix = site.staticPrefix();
        LocalDate start = config.festival().startDate();

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("school", config.school());
        model.put("festival", config.festival());
        model.put("page", page);

        model.put("era", JapaneseEra.formatOrEmpty(start));
        model.put("year", start != null ? String.valueOf(start.getYear()) : "");
        model.put("staticPrefix", staticPrefix);

        // The favicon is an inline SVG data URI, so this value lands in a percent-encoded
        // context rather than an HTML one -- it must be URL-encoded, not HTML-escaped.
        model.put("iconGlyphEncoded", iconGlyph(config));

        String heroPhoto = config.hero().photo();
        model.put("hasHeroPhoto", !heroPhoto.isEmpty());
        model.put("heroPhotoUrl", imageUrl(staticPrefix, heroPhoto));
        model.put("heroDotsText", !page.heroDotsText().isEmpty()
                ? page.heroDotsText()
                : config.hero().dotsText());

        model.put("hasStream", !config.stream().youtubeId().isEmpty());
        model.put("youtubeId", config.stream().youtubeId());

        List<Map<String, Object>> works = works(config);
        model.put("works", works);
        model.put("hasWorks", !works.isEmpty());

        model.put("hasGate", !site.gateUrl().isEmpty() && !site.gateLabel().isEmpty());
        model.put("gateUrl", site.gateUrl());
        model.put("gateLabel", site.gateLabel());

        model.put("hasAppleTouchIcon", !site.appleTouchIcon().isEmpty());
        model.put("appleTouchIconUrl", imageUrl(staticPrefix, site.appleTouchIcon()));

        model.put("nav", nav(config, page));
        model.put("conceptLines", lines(config.festival().conceptLead()));
        model.put("graphSummary", graphSummary(config.graph()));

        List<String> missing = config.missingKeys();
        model.put("notConfigured", !missing.isEmpty());

        model.put("configJson", configJson(config));

        // Titles and descriptions are themselves tiny templates, so the festival name is
        // written once in the config rather than repeated in every page's title.
        model.put("title", inline(page.title(), model));
        model.put("description", inline(page.description(), model));
        openGraph(model, config, page);

        return model;
    }

    /**
     * Adds the link-preview tags. These only work because rendering is server-side: the
     * LINE crawler that will see them does not execute JavaScript.
     *
     * @param model  the model being built, already carrying title and description
     * @param config the site config
     * @param page   the page being rendered
     */
    private void openGraph(Map<String, Object> model, SiteConfig config, SiteConfig.Page page) {
        String baseUrl = config.site().baseUrl();
        model.put("ogTitle", model.get("title"));
        model.put("ogDescription", model.get("description"));

        boolean hasBase = !baseUrl.isEmpty();
        model.put("hasOgUrl", hasBase);
        model.put("ogUrl", hasBase ? baseUrl + page.route() : "");

        // A relative og:image is useless to a crawler, so it is only emitted when the
        // public origin is known.
        String image = !config.site().ogImage().isEmpty()
                ? config.site().ogImage()
                : config.hero().photo();
        boolean hasImage = hasBase && !image.isEmpty();
        model.put("hasOgImage", hasImage);
        model.put("ogImage", hasImage
                ? baseUrl + imageUrl(config.site().staticPrefix(), image)
                : "");
    }

    /**
     * Builds the shared nav.
     *
     * <p>Because every page renders from this one list, the nav cannot differ between
     * pages and {@code aria-current} is computed rather than copy-pasted.
     *
     * @param config  the site config
     * @param current the page being rendered
     * @return one entry per configured page
     */
    private List<Map<String, Object>> nav(SiteConfig config, SiteConfig.Page current) {
        List<Map<String, Object>> nav = new ArrayList<>();
        for (SiteConfig.Page page : config.pages()) {
            if (page.route().isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", page.navLabel());
            item.put("navTitle", page.navTitle());
            item.put("hasNavTitle", !page.navTitle().isEmpty());
            item.put("href", page.route());
            item.put("current", page.key().equals(current.key()));
            nav.add(item);
        }
        return nav;
    }

    /**
     * Builds the works list.
     *
     * <p>{@code hasUrl} is what stops an unconfigured fork from rendering a dead
     * {@code href="#"}: with no URL the anchor is not emitted at all.
     *
     * @param config the site config
     * @return one entry per work item
     */
    private List<Map<String, Object>> works(SiteConfig config) {
        List<Map<String, Object>> works = new ArrayList<>();
        List<SiteConfig.WorkItem> items = config.works().items();
        for (int i = 0; i < items.size(); i++) {
            SiteConfig.WorkItem item = items.get(i);
            Map<String, Object> work = new LinkedHashMap<>();
            work.put("ordinal", String.format("%02d", i + 1));
            work.put("title", item.title());
            work.put("description", item.description());
            work.put("url", item.url());
            work.put("hasUrl", !item.url().isEmpty());
            works.add(work);
        }
        return works;
    }

    /**
     * Turns a list of text lines into a renderable list that knows where the breaks go.
     *
     * <p>The lines are separate config entries rather than one string containing markup,
     * so they stay plain text and are escaped like every other value.
     *
     * @param values the configured lines
     * @return one entry per line, each flagged with whether a break follows it
     */
    private static List<Map<String, Object>> lines(List<String> values) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("text", values.get(i));
            line.put("hasNext", i < values.size() - 1);
            lines.add(line);
        }
        return lines;
    }

    /**
     * Describes the theme graph in prose, for the screen-reader-only paragraph beside the
     * canvas.
     *
     * <p>Derived from the graph config so the spoken description cannot fall out of step
     * with the drawn one, which is what a hand-written copy would eventually do.
     *
     * @param graph the graph config
     * @return the description, e.g. {@code つなぐ。時間をつなぐ：過去、現在、未来。}
     */
    private static String graphSummary(SiteConfig.Graph graph) {
        StringBuilder summary = new StringBuilder();
        if (!graph.center().text().isEmpty()) {
            summary.append(graph.center().text()).append("。");
        }
        for (SiteConfig.Branch branch : graph.branches()) {
            if (branch.label().isEmpty()) {
                continue;
            }
            summary.append(branch.label());
            if (!branch.leaves().isEmpty()) {
                summary.append("：").append(String.join("、", branch.leaves()));
            }
            summary.append("。");
        }
        return summary.toString();
    }

    /**
     * Renders a config-supplied string as a template.
     *
     * <p>A broken template here is a config typo, not a server fault, so it falls back to
     * the literal text with a warning instead of failing the whole render.
     *
     * @param template the config value, which may contain Mustache tags
     * @param model    the model to render against
     * @return the rendered text, or the original text if it could not be rendered
     */
    private String inline(String template, Map<String, Object> model) {
        if (template.isEmpty() || !template.contains("{{")) {
            return template;
        }
        try {
            Template compiled = compiler.compile(template);
            return compiled.execute(model);
        } catch (RuntimeException e) {
            log.warn("Could not render config value '{}'; using it literally", template, e);
            return template;
        }
    }

    /**
     * Builds the URL of an image in the static tree.
     *
     * @param staticPrefix the configured static mount point
     * @param filename     the image filename, already validated as a plain name
     * @return the URL, or an empty string if there is no image
     */
    private static String imageUrl(String staticPrefix, String filename) {
        return filename.isEmpty() ? "" : staticPrefix + "/images/" + filename;
    }

    /**
     * The single character used as the favicon glyph, percent-encoded for the data URI.
     *
     * @param config the site config
     * @return the encoded glyph, or an empty string when no school name is set
     */
    private static String iconGlyph(SiteConfig config) {
        String name = !config.school().nameShort().isEmpty()
                ? config.school().nameShort()
                : config.school().nameJa();
        if (name.isEmpty()) {
            return "";
        }
        // A single code point, so a surrogate pair is not split in half.
        String glyph = name.substring(0, name.offsetByCodePoints(0, 1));
        return URLEncoder.encode(glyph, StandardCharsets.UTF_8);
    }

    // endregion

    // region JSON island

    /**
     * Serialises the values the browser scripts need, as JSON safe to embed in a
     * {@code <script type="application/json">} element.
     *
     * <p>The island is emitted with triple braces because HTML-escaping would corrupt the
     * JSON, so the escaping is done here instead. Without it a {@code <} in any config
     * value could close the script element early, which is an XSS hole rather than a
     * cosmetic problem. {@code \\uXXXX} is legal inside a JSON string, so this stays valid
     * JSON and {@code JSON.parse} hands the original characters back.
     *
     * @param config the site config
     * @return the escaped JSON, or {@code {}} if serialisation fails
     */
    private String configJson(SiteConfig config) {
        try {
            String raw = json.writeValueAsString(Map.of("graph", config.graph()));
            return raw.replace("<", "\\u003c")
                    .replace(">", "\\u003e")
                    .replace("&", "\\u0026");
        } catch (Exception e) {
            log.warn("Could not serialise the config island; scripts will use their defaults", e);
            return "{}";
        }
    }

    // endregion
}
