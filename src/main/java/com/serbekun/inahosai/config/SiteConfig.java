package com.serbekun.inahosai.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serbekun.inahosai.resources.ResourcesBasePath;

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
        boolean isSetupReadedAndConfigEdited,
        int port,
        School school,
        Festival festival,
        Hero hero,
        Stream stream,
        Works works,
        Graph graph,
        Site site,
        List<Page> pages,
        Debug debug,
        Pdf pdf) {

    private static final Logger log = LoggerFactory.getLogger(SiteConfig.class);

    /** The port used when the config sets none, or sets one outside 1..65535. */
    public static final int DEFAULT_PORT = 2323;

    /** A YouTube video id — exactly 11 characters of an unreserved alphabet. */
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** A work key — a lowercase slug safe to place in a URL path segment. */
    private static final Pattern WORK_KEY = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public SiteConfig {
        // A missing key deserializes to 0; anything outside the valid range falls back to
        // the default rather than binding a port the JVM would reject.
        if (port < 1 || port > 65535) {
            if (port != 0) {
                log.warn("port {} is not in 1..65535; using {}", port, DEFAULT_PORT);
            }
            port = DEFAULT_PORT;
        }
        school = school != null ? school : new School(null, null, null);
        festival = festival != null ? festival : new Festival(null, null, null, null, null, null);
        hero = hero != null ? hero : new Hero(null, null);
        stream = stream != null ? stream : new Stream(null, null, null);
        works = works != null ? works : new Works(null);
        graph = graph != null ? graph : new Graph(null, null);
        site = site != null
                ? site
                : new Site(null, null, null, null, null, null, null, null, null, null,
                        null);
        pages = copyOf(pages);
        debug = debug != null ? debug : new Debug(false, "");
        pdf = pdf != null ? pdf : new Pdf(false, "");
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
     *
     * <p>{@code about} is optional prose for the visible ABOUT section. A generated
     * sentence from the other fields is always rendered; these lines are added after it,
     * which is what lets a fork add detail without editing a template.
     */
    public record Festival(String name, String slogan, LocalDate startDate, LocalDate endDate,
                           List<String> conceptLead, List<String> about) {
        public Festival {
            name = str(name);
            slogan = str(slogan);
            conceptLead = copyOf(conceptLead);
            about = copyOf(about);
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

    /**
     * The optional live broadcast.
     *
     * <p>{@code type} selects the platform: {@code "youtube"} or {@code "zoom"}. A blank
     * or unknown value is inferred from whichever platform is configured, so an existing
     * {@code youtube_id}-only config keeps working. When nothing usable is set the type
     * becomes {@code "none"} and {@link #isLive()} is false.
     *
     * <p>Only the fields of the selected platform matter: {@code youtubeId} is validated
     * as an 11-character video id and {@code zoomUrl} as an http(s) URL. A value that
     * fails validation is treated as absent rather than taking the site down.
     */
    public record Stream(String type, String youtubeId, String zoomUrl) {

        /** Platform type meaning "no broadcast". */
        public static final String TYPE_NONE = "none";

        /** Platform type for a YouTube embed. */
        public static final String TYPE_YOUTUBE = "youtube";

        /** Platform type for a Zoom meeting link. */
        public static final String TYPE_ZOOM = "zoom";

        public Stream {
            youtubeId = safeYoutubeId(youtubeId);
            zoomUrl = safeLinkUrl(zoomUrl, "stream.zoom_url");
            type = safeLiveType(type, youtubeId, zoomUrl);
        }

        /** Whether the configured platform is YouTube. */
        public boolean isYoutube() {
            return TYPE_YOUTUBE.equals(type);
        }

        /** Whether the configured platform is Zoom. */
        public boolean isZoom() {
            return TYPE_ZOOM.equals(type);
        }

        /**
         * Whether a broadcast can actually be rendered: the selected platform and its
         * payload are both present.
         *
         * @return true when there is a usable stream
         */
        public boolean isLive() {
            return (isYoutube() && !youtubeId.isEmpty()) || (isZoom() && !zoomUrl.isEmpty());
        }

        /**
         * The platform to report to a client: the configured type, or {@code "none"} when
         * it has no usable payload.
         *
         * @return {@code "youtube"}, {@code "zoom"} or {@code "none"}
         */
        public String effectiveType() {
            return isLive() ? type : TYPE_NONE;
        }
    }

    /** The student works listing. */
    public record Works(List<WorkItem> items) {
        public Works {
            items = copyOf(items);
        }
    }

    /**
     * One work. {@code key} names its file-chooser page at {@code /works/{key}}.
     *
     * <p>A work with no {@code files} renders no button at all. A work with exactly one
     * file links straight to it; a work with several gets a chooser page listing them.
     * A work that has files but no usable {@code key} cannot have a chooser, so it is
     * warned about and its button is omitted rather than rendered dead.
     */
    public record WorkItem(String key, String title, String description, List<FileRef> files) {
        public WorkItem {
            key = safeWorkKey(key);
            title = str(title);
            description = str(description);
            files = copyOf(files);
            if (key.isEmpty() && !files.isEmpty()) {
                log.warn("works[] entry '{}' has files but no URL-safe key; no button is rendered",
                        title);
            }
        }
    }

    /** One downloadable file of a work: the label a visitor sees and its link. */
    public record FileRef(String name, String url) {
        public FileRef {
            name = str(name);
            url = safeLinkUrl(url, "works[].files[].url");
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

    /**
     * Site-wide URLs and assets.
     *
     * <p>The last four fields are the footer's credits: {@code repoUrl} is the source
     * link, {@code author} and {@code authorUrl} the person who wrote it, and
     * {@code schoolUrl} the school's own website. A fork changes all of them to its own
     * rather than crediting upstream's.
     */
    public record Site(String staticPrefix, String baseUrl, String gateUrl, String gateLabel,
                       String appleTouchIcon, String ogImage, String repoUrl, String repoLabel,
                       String author, String authorUrl, String schoolUrl) {
        public Site {
            staticPrefix = safeStaticPrefix(staticPrefix);
            baseUrl = safeLinkUrl(baseUrl, "site.base_url");
            gateUrl = safeLinkUrl(gateUrl, "site.gate_url");
            gateLabel = str(gateLabel);
            appleTouchIcon = safeImageName(appleTouchIcon);
            ogImage = safeImageName(ogImage);
            repoUrl = safeLinkUrl(repoUrl, "site.repo_url");
            repoLabel = str(repoLabel);
            author = str(author);
            authorUrl = safeLinkUrl(authorUrl, "site.author_url");
            schoolUrl = safeLinkUrl(schoolUrl, "site.school_url");
        }
    }

    /**
     * Opt-in serving of raw, page-less HTML "playground" files from {@code resources/html/}.
     *
     * <p>These are standalone files such as {@code point_text.html} that are not configured
     * pages (and so have no clean route) but are useful to open while developing or on a
     * deployment that wants to share them. They are only reachable when {@code enabled} is
     * true, and when {@code token} is set a request must present it to be served at all.
     *
     * @param enabled whether the raw playground mount is registered
     * @param token   optional gate; blank means the mount needs no token
     */
    public record Debug(boolean enabled, String token) {
        public Debug {
            token = str(token);
        }
    }

    /**
     * Opt-in gate for the PDF resources under {@code /static/v0/pdf/*}.
     *
     * <p>When {@code requireAuth} is true the visitor posts {@code token} once through
     * the unlock form; the server answers with an HttpOnly cookie and every later PDF
     * request is authorized by that cookie. The token is never accepted from the URL,
     * so it cannot leak through access logs, browser history or a {@code Referer}
     * header. The same gate covers the PDF directory listing, so file names do not
     * leak either. When {@code requireAuth} is false, or when no token is set, PDFs
     * are served as before.
     *
     * @param requireAuth whether PDF requests must carry the unlock cookie
     * @param token       the token the unlock form must present; blank means the gate is inert
     */
    public record Pdf(boolean requireAuth, String token) {
        public Pdf {
            token = str(token);
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
     * Validates the live platform type.
     *
     * <p>{@code "youtube"} and {@code "zoom"} are accepted in any case. A blank value is
     * inferred from whichever validated payload is present, so an existing config that
     * sets only {@code youtube_id} is read as a YouTube stream. An unrecognised value is
     * warned about and treated as blank.
     *
     * @param value     the configured type
     * @param youtubeId the already-validated YouTube id (may be empty)
     * @param zoomUrl   the already-validated Zoom URL (may be empty)
     * @return one of {@code "youtube"}, {@code "zoom"} or {@code "none"}
     */
    private static String safeLiveType(String value, String youtubeId, String zoomUrl) {
        String type = str(value).toLowerCase(Locale.ROOT);
        if (type.equals(Stream.TYPE_YOUTUBE) || type.equals(Stream.TYPE_ZOOM)) {
            return type;
        }
        if (!type.isEmpty() && !type.equals(Stream.TYPE_NONE)) {
            log.warn("stream.type '{}' is not 'youtube' or 'zoom'; inferring it from the "
                    + "configured stream", type);
        }
        if (!youtubeId.isEmpty()) {
            return Stream.TYPE_YOUTUBE;
        }
        if (!zoomUrl.isEmpty()) {
            return Stream.TYPE_ZOOM;
        }
        return Stream.TYPE_NONE;
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

    /**
     * Validates a work key: a lowercase slug that is safe as a URL path segment.
     *
     * <p>Anything else is logged and discarded, which leaves the work without a
     * chooser page. Uppercase is folded to lowercase rather than rejected.
     *
     * @param value the configured key
     * @return the normalized slug, or an empty string when it is not usable
     */
    private static String safeWorkKey(String value) {
        String key = str(value).toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return "";
        }
        if (!WORK_KEY.matcher(key).matches()) {
            log.warn("works[].key '{}' is not a lowercase URL slug; the work gets no page",
                    value);
            return "";
        }
        return key;
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
