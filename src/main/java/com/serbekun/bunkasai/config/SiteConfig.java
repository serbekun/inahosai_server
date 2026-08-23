package com.serbekun.bunkasai.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.serbekun.bunkasai.resources.ResourcesBasePath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole site described as data: everything a fork needs to change lives here
 * rather than in markup.
 *
 * <p>This config is written by a forker, so it is treated as untrusted input. Every
 * nested record validates its own fields in its compact constructor, which means a
 * {@code SiteConfig} is sanitized no matter how it was built — parsed from YAML, or
 * assembled by hand in a test. A value that fails validation is logged and degrades to
 * "absent" (an empty string); nothing here throws, because a typo in one optional key
 * must not take the whole site down.
 *
 * <p>Unknown YAML keys are a different matter and do fail loudly — see
 * {@link SiteConfigLoader}.
 */
public record SiteConfig(
        School school,
        Festival festival,
        Hero hero,
        Stream stream,
        Works works,
        Graph graph,
        Site site,
        List<Page> pages) {

    private static final Logger log = LoggerFactory.getLogger(SiteConfig.class);

    /** A YouTube video id — exactly 11 characters of an unreserved alphabet. */
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    public SiteConfig {
        school = school != null ? school : new School(null, null, null);
        festival = festival != null ? festival : new Festival(null, null, null, null);
        hero = hero != null ? hero : new Hero(null, null);
        stream = stream != null ? stream : new Stream(null);
        works = works != null ? works : new Works(null);
        graph = graph != null ? graph : new Graph(null, null);
        site = site != null ? site : new Site(null, null, null, null, null);
        pages = copyOf(pages);
    }

    /** The school the festival belongs to. */
    public record School(String nameJa, String nameShort, String nameLatin) {
        public School {
            nameJa = str(nameJa);
            nameShort = str(nameShort);
            nameLatin = str(nameLatin);
        }
    }

    /**
     * The festival itself. {@code startDate} is what the Japanese era is derived from
     * (see {@link JapaneseEra}), so it must be the real first day of the festival.
     */
    public record Festival(String name, String slogan, LocalDate startDate, LocalDate endDate) {
        public Festival {
            name = str(name);
            slogan = str(slogan);
        }
    }

    /**
     * The hero block. An empty {@code photo} renders no {@code <img>} at all — the
     * composition already works without one.
     */
    public record Hero(String photo, String dotsText) {
        public Hero {
            photo = safeImageName(photo);
            dotsText = str(dotsText);
        }
    }

    /** An optional stream embed. An empty or malformed id is treated as no stream. */
    public record Stream(String youtubeId) {
        public Stream {
            youtubeId = safeYoutubeId(youtubeId);
        }
    }

    /** The student works listing. */
    public record Works(List<WorkItem> items) {
        public Works {
            items = copyOf(items);
        }
    }

    /** One work. An empty {@code url} means the link button is not rendered. */
    public record WorkItem(String title, String description, String url) {
        public WorkItem {
            title = str(title);
            description = str(description);
            url = safeLinkUrl(url, "works[].url");
        }
    }

    /** The theme graph drawn by {@code graph.js}, shipped to the page as a JSON island. */
    public record Graph(GraphNode center, List<Branch> branches) {
        public Graph {
            center = center != null ? center : new GraphNode(null, null);
            branches = copyOf(branches);
        }
    }

    /**
     * A graph node. {@code at} is a normalised {@code [x, y]} pair in the range 0..1,
     * hand-tuned rather than auto-laid-out.
     */
    public record GraphNode(String text, List<Double> at) {
        public GraphNode {
            text = str(text);
            at = copyOf(at);
        }
    }

    /**
     * One graph branch. {@code url} travels with the branch rather than living in a
     * separate array, so branch order and link order cannot drift apart.
     */
    public record Branch(String label, List<Double> at, List<String> leaves,
                         List<List<Double>> leafAt, String url) {
        public Branch {
            label = str(label);
            at = copyOf(at);
            leaves = copyOf(leaves);
            leafAt = copyOf(leafAt);
            url = safeLinkUrl(url, "graph.branches[].url");
        }
    }

    /** Site-wide URLs and assets. */
    public record Site(String staticPrefix, String baseUrl, String gateUrl,
                       String appleTouchIcon, String ogImage) {
        public Site {
            staticPrefix = safeStaticPrefix(staticPrefix);
            baseUrl = safeLinkUrl(baseUrl, "site.base_url");
            gateUrl = safeLinkUrl(gateUrl, "site.gate_url");
            appleTouchIcon = safeImageName(appleTouchIcon);
            ogImage = safeImageName(ogImage);
        }
    }

    /**
     * One rendered page. Listing pages here rather than hardcoding them is what keeps
     * the shared nav consistent: the header partial iterates this list, so
     * {@code aria-current} is computed and cannot drift between pages.
     */
    public record Page(String key, String route, String template, String navLabel,
                       String navTitle, String title, String description,
                       String heroDotsText, String footerWord) {
        public Page {
            key = str(key);
            route = str(route);
            template = str(template);
            navLabel = str(navLabel);
            navTitle = str(navTitle);
            title = str(title);
            description = str(description);
            heroDotsText = str(heroDotsText);
            footerWord = str(footerWord);
        }
    }

    /**
     * Returns the config keys that are required but still unset. Empty means configured.
     *
     * <p>Keys are reported in their YAML (snake_case) spelling so the message points at
     * something the reader can actually find in the file.
     *
     * @return the missing keys, in a stable order
     */
    public List<String> missingKeys() {
        List<String> missing = new ArrayList<>();
        if (school.nameJa().isEmpty()) {
            missing.add("school.name_ja");
        }
        if (festival.name().isEmpty()) {
            missing.add("festival.name");
        }
        if (festival.startDate() == null) {
            missing.add("festival.start_date");
        }
        return List.copyOf(missing);
    }

    /**
     * Whether enough is configured for the site to be meaningful.
     *
     * @return true when {@link #missingKeys()} is empty
     */
    public boolean isConfigured() {
        return missingKeys().isEmpty();
    }

    // region Field validation
    //
    // Nested records reach these because private members are shared across the whole
    // top-level class. Each returns a safe value rather than throwing.

    /** Null-coalesces and trims a plain text value. */
    private static String str(String value) {
        return value == null ? "" : value.strip();
    }

    /** Null-safe immutable copy that also drops null elements. */
    private static <T> List<T> copyOf(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            if (value != null) {
                copy.add(value);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * Validates a YouTube video id. Anything that is not exactly 11 characters of
     * {@code [A-Za-z0-9_-]} is discarded, so a full URL, a quote or an angle bracket can
     * never reach a {@code src} attribute.
     */
    private static String safeYoutubeId(String value) {
        String id = str(value);
        if (id.isEmpty()) {
            return "";
        }
        if (!YOUTUBE_ID.matcher(id).matches()) {
            log.warn("stream.youtube_id is not a valid YouTube id; treating the stream as absent");
            return "";
        }
        return id;
    }

    /**
     * Validates an image filename. It is a bare filename, not a path, so it is run
     * through the same resolver the HTTP layer uses — which throws rather than returning
     * null when it sees traversal characters.
     */
    private static String safeImageName(String value) {
        String name = str(value);
        if (name.isEmpty()) {
            return "";
        }
        try {
            ResourcesBasePath.resolveImagePath(name);
            return name;
        } catch (IllegalArgumentException e) {
            log.warn("Image name '{}' is not a plain filename; treating the image as absent", name);
            return "";
        }
    }

    /** Validates the static mount point: must be rooted, must not climb. */
    private static String safeStaticPrefix(String value) {
        String prefix = str(value);
        if (prefix.isEmpty()) {
            return "";
        }
        if (!prefix.startsWith("/") || prefix.contains("..")) {
            log.warn("site.static_prefix '{}' must start with '/' and contain no '..'; ignoring it",
                    prefix);
            return "";
        }
        // Trailing slashes would double up when concatenated with a resource path.
        while (prefix.length() > 1 && prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /**
     * Validates a link destination: either an absolute http(s) URL or a site-relative
     * path such as {@code /jikan}.
     *
     * <p>Relative paths have to be allowed because the graph branches link to this site's
     * own routes. Everything else is rejected — notably {@code javascript:} and
     * {@code data:} URLs, and protocol-relative {@code //host} forms, none of which
     * should ever reach an {@code href}.
     */
    private static String safeLinkUrl(String value, String key) {
        String url = str(value);
        if (url.isEmpty()) {
            return "";
        }
        if (url.startsWith("/")) {
            if (url.startsWith("//") || url.contains("..")) {
                log.warn("{} '{}' is not a safe site-relative path; ignoring it", key, url);
                return "";
            }
            return url;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            boolean http = scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
            if (uri.isAbsolute() && http && uri.getHost() != null) {
                return url;
            }
        } catch (URISyntaxException e) {
            // Fall through to the warning below.
        }
        log.warn("{} '{}' is neither an absolute http(s) URL nor a site-relative path; ignoring it",
                key, url);
        return "";
    }

    // endregion
}
