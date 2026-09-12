package com.serbekun.inahosai.render;

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
import com.serbekun.inahosai.config.ConfigKeys;
import com.serbekun.inahosai.config.JapaneseEra;
import com.serbekun.inahosai.config.SiteConfig;
import com.serbekun.inahosai.resources.Etags;
import com.serbekun.inahosai.service.resource.ResourcesService;

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

    /** Name of the development-only setup template. */
    public static final String SETUP_TEMPLATE = "setup.html";

    /** Name of the per-work file-chooser template. */
    public static final String WORK_TEMPLATE = "work.html";

    /** Template of the page that lists the works, used to build the chooser's back link. */
    private static final String WORK_LISTING_TEMPLATE = "manabi.html";

    /** URL prefix under which a work's file-chooser page is served. */
    public static final String WORK_ROUTE_PREFIX = "/works/";

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
        Map<String, Object> model = model(config, page);
        // An error body has no canonical URL and should never be indexed.
        suppressSharing(model);
        return render(template, model, NOT_FOUND_TEMPLATE);
    }

    /**
     * Renders the setup page.
     *
     * <p>Reports key names and whether each is set. It never renders a value: nothing in
     * this config is secret today, but a page that prints config values becomes a leak
     * the moment somebody adds a key that is.
     *
     * @param config the site config
     * @return the rendered setup page, or null if its template is missing
     */
    public RenderedPage renderSetup(SiteConfig config) {
        String template = resources.getHtml(SETUP_TEMPLATE);
        if (template == null) {
            log.warn("No {} template found; the setup page is unavailable", SETUP_TEMPLATE);
            return null;
        }
        SiteConfig.Page page = new SiteConfig.Page(
                "setup", "", SETUP_TEMPLATE, "", "",
                "SETUP — {{festival.name}}", "", "", "SETUP");
        Map<String, Object> model = model(config, page);
        model.put("configKeys", ConfigKeys.status(config));
        // A development aid is neither shareable nor indexable, and the link-preview tags
        // would otherwise echo site.base_url back onto a page whose whole promise is that
        // it prints no config values.
        suppressSharing(model);
        return render(template, model, SETUP_TEMPLATE);
    }

    /**
     * Renders one file-chooser page per work that has more than one file.
     *
     * <p>These are utility pages, not configured pages: they are not part of the shared
     * nav and not listed in the sitemap. They are still real server-rendered pages, so
     * the chooser works with JavaScript off and each one has a stable URL that can be
     * shared.
     *
     * @param config the site config
     * @return a route key (the work key) to page map
     */
    public Map<String, RenderedPage> renderWorkPages(SiteConfig config) {
        String template = resources.getHtml(WORK_TEMPLATE);
        if (template == null) {
            log.warn("No {} template found; work chooser pages are unavailable", WORK_TEMPLATE);
            return Map.of();
        }

        String worksRoute = worksListingRoute(config);
        Map<String, RenderedPage> pages = new LinkedHashMap<>();
        for (SiteConfig.WorkItem item : config.works().items()) {
            if (item.key().isEmpty() || item.files().size() < 2) {
                continue;
            }
            String title = item.title();
            String festival = config.festival().name();
            if (!festival.isEmpty()) {
                title = title.isEmpty() ? festival : title + " — " + festival;
            }
            SiteConfig.Page page = new SiteConfig.Page(
                    "work-" + item.key(), WORK_ROUTE_PREFIX + item.key(), WORK_TEMPLATE, "", "",
                    title, item.description(), "", "");
            Map<String, Object> model = model(config, page);

            Map<String, Object> work = new LinkedHashMap<>();
            work.put("title", item.title());
            work.put("description", item.description());
            work.put("files", workFiles(item));
            model.put("work", work);
            model.put("worksRoute", worksRoute);

            pages.put(item.key(), render(template, model, WORK_TEMPLATE));
        }
        return Map.copyOf(pages);
    }

    /**
     * Finds the route of the page that lists the works, so the chooser can link back to
     * it without hardcoding a school's route. Falls back to the home page when no page
     * uses the works template.
     *
     * @param config the site config
     * @return a site-relative route
     */
    private static String worksListingRoute(SiteConfig config) {
        for (SiteConfig.Page page : config.pages()) {
            if (WORK_LISTING_TEMPLATE.equals(page.template()) && !page.route().isEmpty()) {
                return page.route();
            }
        }
        return "/";
    }

    /**
     * Builds the sitemap listing every configured page.
     *
     * <p>Requires {@code site.base_url}: a sitemap's {@code <loc>} values must be absolute,
     * so without a public origin there is no valid sitemap and none is served rather than
     * one full of relative paths.
     *
     * @param config the site config
     * @return the rendered sitemap, or null when no public origin is configured
     */
    public RenderedPage renderSitemap(SiteConfig config) {
        String baseUrl = config.site().baseUrl();
        if (baseUrl.isEmpty()) {
            log.warn("site.base_url is not set; /sitemap.xml will not be served");
            return null;
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (SiteConfig.Page page : config.pages()) {
            if (page.route().isEmpty()) {
                continue;
            }
            xml.append("  <url>\n");
            xml.append("    <loc>").append(xmlEscape(baseUrl + page.route())).append("</loc>\n");
            xml.append("    <changefreq>weekly</changefreq>\n");
            xml.append("    <priority>")
                    .append("/".equals(page.route()) ? "1.0" : "0.8")
                    .append("</priority>\n");
            xml.append("  </url>\n");
        }
        xml.append("</urlset>\n");

        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        return new RenderedPage(body, Etags.of(body), "application/xml; charset=utf-8");
    }

    /**
     * Builds {@code robots.txt}, pointing crawlers at the sitemap when one exists.
     *
     * <p>The API and the development setup route are kept out of the index: they are not
     * content and should not compete with the pages. The content-signals block a proxy may
     * prepend is outside this file's control.
     *
     * @param config the site config
     * @return the rendered robots.txt
     */
    public RenderedPage renderRobots(SiteConfig config) {
        StringBuilder text = new StringBuilder();
        text.append("User-agent: *\n");
        text.append("Allow: /\n");
        text.append("Disallow: /api/v0/\n");
        text.append("Disallow: /setup\n");
        // Gated documents must not be crawled; the PDF responses also carry a
        // X-Robots-Tag for crawlers that reach one through a link.
        text.append("Disallow: /static/v0/pdf/\n");

        String baseUrl = config.site().baseUrl();
        if (!baseUrl.isEmpty()) {
            text.append("\nSitemap: ").append(baseUrl).append("/sitemap.xml\n");
        }

        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        return new RenderedPage(body, Etags.of(body), "text/plain; charset=utf-8");
    }

    /**
     * Escapes the five characters that are special in XML text.
     *
     * @param value the raw value
     * @return the escaped value
     */
    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Marks a page as neither indexable nor shareable, and drops its link-preview URLs.
     *
     * @param model the model to adjust
     */
    private static void suppressSharing(Map<String, Object> model) {
        model.put("noIndex", true);
        model.put("hasOgUrl", false);
        model.put("hasOgImage", false);
        model.put("ogUrl", "");
        model.put("ogImage", "");
        model.put("hasCanonical", false);
        model.put("canonicalUrl", "");
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
        model.put("startDateLabel", JapaneseEra.formatMonthDay(start));
        model.put("hasEndDate", config.festival().endDate() != null);
        model.put("endDateLabel", JapaneseEra.formatMonthDay(config.festival().endDate()));
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

        model.put("hasStream", config.stream().isLive());
        model.put("liveType", config.stream().effectiveType());
        model.put("youtubeId", config.stream().youtubeId());
        model.put("zoomUrl", config.stream().zoomUrl());

        List<Map<String, Object>> works = works(config);
        model.put("works", works);
        model.put("hasWorks", !works.isEmpty());

        // Shown on a page that links PDFs when the PDF gate is armed: the visitor posts
        // the password once and the server answers with the HttpOnly cookie that
        // authorizes the later PDF links. The URL never carries the token.
        model.put("hasPdfGate", config.pdf().requireAuth() && !config.pdf().token().isEmpty());

        model.put("hasGate", !site.gateUrl().isEmpty() && !site.gateLabel().isEmpty());
        model.put("gateUrl", site.gateUrl());
        model.put("gateLabel", site.gateLabel());

        model.put("hasRepo", !site.repoUrl().isEmpty() && !site.repoLabel().isEmpty());
        model.put("repoUrl", site.repoUrl());
        model.put("repoLabel", site.repoLabel());
        // The footer credits the school itself rather than the page word, so the right
        // side reads the same on every page.
        String schoolLabel = !config.school().nameShort().isEmpty()
                ? config.school().nameShort()
                : config.school().nameJa();
        model.put("schoolLabel", schoolLabel);
        model.put("hasSchoolLink", !schoolLabel.isEmpty() && !site.schoolUrl().isEmpty());
        model.put("schoolUrl", site.schoolUrl());

        model.put("hasAuthor", !site.author().isEmpty());
        model.put("author", site.author());
        model.put("hasAuthorLink", !site.author().isEmpty() && !site.authorUrl().isEmpty());
        model.put("authorUrl", site.authorUrl());

        model.put("hasAppleTouchIcon", !site.appleTouchIcon().isEmpty());
        model.put("appleTouchIconUrl", imageUrl(staticPrefix, site.appleTouchIcon()));

        model.put("nav", nav(config, page));
        model.put("conceptLines", lines(config.festival().conceptLead()));
        List<Map<String, Object>> aboutLines = aboutLines(config);
        model.put("aboutLines", aboutLines);
        model.put("hasAbout", !aboutLines.isEmpty());
        model.put("graphSummary", graphSummary(config.graph()));

        List<String> missing = config.missingKeys();
        model.put("notConfigured", !missing.isEmpty());

        model.put("configJson", configJson(config));

        // Titles and descriptions are themselves tiny templates, so the festival name is
        // written once in the config rather than repeated in every page's title.
        model.put("title", inline(page.title(), model));
        model.put("description", inline(page.description(), model));
        openGraph(model, config, page);

        // Only the home page carries the event markup, so the same Event is not repeated
        // on every page of one small site.
        String structuredData = structuredData(config, page, model);
        model.put("hasStructuredData", !structuredData.isEmpty());
        model.put("structuredDataJson", structuredData);

        return model;
    }

    /**
     * Adds the link-preview tags and the canonical URL. These only work because rendering
     * is server-side: the LINE crawler that will see them does not execute JavaScript.
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

        // A canonical URL has to be absolute, so without a public origin it is omitted
        // rather than written as a relative path a crawler would have to guess at.
        model.put("hasCanonical", hasBase);
        model.put("canonicalUrl", hasBase ? baseUrl + page.route() : "");

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
     * <p>{@code hasDirect} / {@code hasChooser} are what stop an unconfigured fork from
     * rendering a dead {@code href="#"}: a work with no usable file gets no anchor at
     * all. One file downloads straight, several go through a chooser page.
     *
     * @param config the site config
     * @return one entry per work item
     */
    private List<Map<String, Object>> works(SiteConfig config) {
        List<Map<String, Object>> works = new ArrayList<>();
        List<SiteConfig.WorkItem> items = config.works().items();
        for (int i = 0; i < items.size(); i++) {
            SiteConfig.WorkItem item = items.get(i);
            List<Map<String, Object>> files = workFiles(item);

            Map<String, Object> work = new LinkedHashMap<>();
            work.put("ordinal", String.format("%02d", i + 1));
            work.put("title", item.title());
            work.put("description", item.description());
            work.put("files", files);
            work.put("hasFiles", !files.isEmpty());

            boolean direct = files.size() == 1;
            work.put("hasDirect", direct);
            work.put("directUrl", direct ? files.get(0).get("url") : "");

            boolean chooser = files.size() > 1 && !item.key().isEmpty();
            work.put("hasChooser", chooser);
            work.put("chooserUrl", chooser ? WORK_ROUTE_PREFIX + item.key() : "");
            works.add(work);
        }
        return works;
    }

    /**
     * Builds the downloadable files of one work, dropping any whose URL was rejected.
     *
     * @param item the work
     * @return the renderable files
     */
    private static List<Map<String, Object>> workFiles(SiteConfig.WorkItem item) {
        List<Map<String, Object>> files = new ArrayList<>();
        for (SiteConfig.FileRef file : item.files()) {
            if (file.url().isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", file.name());
            entry.put("url", file.url());
            files.add(entry);
        }
        return files;
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
     * Builds the visible ABOUT prose.
     *
     * <p>The first lines are derived from the config rather than typed into a template, so
     * a fork gets real, indexable text — the school, the festival, the era, the dates and
     * the theme — without writing it. {@code festival.about} lines are appended, which is
     * the escape hatch for anything the config does not already say.
     *
     * @param config the site config
     * @return the lines, or an empty list when there is nothing worth saying
     */
    private static List<Map<String, Object>> aboutLines(SiteConfig config) {
        SiteConfig.Festival festival = config.festival();
        String school = config.school().nameJa();
        LocalDate start = festival.startDate();
        LocalDate end = festival.endDate();

        List<String> text = new ArrayList<>();
        if (!school.isEmpty() && !festival.name().isEmpty()) {
            String era = JapaneseEra.formatOrEmpty(start);
            text.add(era.isEmpty()
                    ? school + "の文化祭「" + festival.name() + "」です。"
                    : era + "に、" + school + "の文化祭「" + festival.name() + "」を開催します。");
        } else if (!festival.name().isEmpty()) {
            text.add("文化祭「" + festival.name() + "」です。");
        }
        if (start != null && end != null) {
            text.add("開催期間は" + JapaneseEra.formatMonthDay(start) + "から"
                    + JapaneseEra.formatMonthDay(end) + "までです。");
        } else if (start != null) {
            text.add("開催日は" + JapaneseEra.formatMonthDay(start) + "です。");
        }
        if (!festival.slogan().isEmpty()) {
            text.add("テーマは「" + festival.slogan() + "」。");
        }
        text.addAll(festival.about());

        return lines(text);
    }

    /**
     * Builds the schema.org Event markup for the home page.
     *
     * <p>Search engines read structured data to understand that this is a dated event at a
     * place, which is the difference between a blue link and an event result. Only the home
     * page carries it: repeating the same Event on five pages of one small site adds
     * nothing.
     *
     * @param config the site config
     * @param page   the page being rendered
     * @param model  the model, already carrying the rendered description and og:image
     * @return the JSON-LD string, or an empty string when it cannot or should not be built
     */
    private String structuredData(SiteConfig config, SiteConfig.Page page,
                                  Map<String, Object> model) {
        if (!"index".equals(page.key()) || config.festival().startDate() == null) {
            return "";
        }
        String school = config.school().nameJa();
        String festivalName = config.festival().name();
        String name = (school.isEmpty() ? festivalName : school + " " + festivalName).strip();
        if (name.isEmpty()) {
            return "";
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("@context", "https://schema.org");
        event.put("@type", "Event");
        event.put("name", name);
        event.put("startDate", config.festival().startDate().toString());
        if (config.festival().endDate() != null) {
            event.put("endDate", config.festival().endDate().toString());
        }
        event.put("eventStatus", "https://schema.org/EventScheduled");
        event.put("eventAttendanceMode", "https://schema.org/OfflineEventAttendanceMode");
        event.put("description", model.get("description"));

        Map<String, Object> location = new LinkedHashMap<>();
        location.put("@type", "Place");
        if (!school.isEmpty()) {
            location.put("name", school);
        }
        String baseUrl = config.site().baseUrl();
        if (!baseUrl.isEmpty()) {
            location.put("url", baseUrl + "/");
            event.put("url", baseUrl + "/");
        }
        event.put("location", location);

        Object image = model.get("ogImage");
        if (image instanceof String url && !url.isEmpty()) {
            event.put("image", url);
        }
        if (!school.isEmpty() && !config.site().schoolUrl().isEmpty()) {
            Map<String, Object> organizer = new LinkedHashMap<>();
            organizer.put("@type", "Organization");
            organizer.put("name", school);
            organizer.put("url", config.site().schoolUrl());
            event.put("organizer", organizer);
        }

        return jsonLd(event);
    }

    /**
     * Serialises a value as JSON safe to embed in a {@code <script type="application/ld+json">}
     * element.
     *
     * <p>Like the config island, the JSON cannot be HTML-escaped without corrupting it, so
     * the characters that could close the script element early are escaped as JSON unicode
     * escapes instead.
     *
     * @param value the value to serialise
     * @return the escaped JSON, or an empty string when serialisation fails
     */
    private String jsonLd(Object value) {
        try {
            return json.writeValueAsString(value)
                    .replace("<", "\\u003c")
                    .replace(">", "\\u003e")
                    .replace("&", "\\u0026");
        } catch (Exception e) {
            log.warn("Could not serialise structured data; omitting it", e);
            return "";
        }
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
