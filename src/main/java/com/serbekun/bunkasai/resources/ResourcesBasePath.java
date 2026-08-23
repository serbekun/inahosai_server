package com.serbekun.bunkasai.resources;

public final class ResourcesBasePath {

    private ResourcesBasePath() {}

    public static final String BASE_HTML_PATH = "html/";

    /**
     * Shared template fragments.
     * <p>
     * Partials are build-time inputs, not content: they are never served over HTTP and
     * never appear in a listing. Nesting them under {@code html/} keeps them out of the
     * page directory that {@code renderAll} iterates.
     * </p>
     */
    public static final String BASE_PARTIALS_PATH = "html/partials/";
    public static final String BASE_CSS_PATH = "css/";
    public static final String BASE_JS_PATH = "js/";

    public static final String BASE_SVG_PATH = "svg/";
    public static final String BASE_IMAGES_PATH = "images/";
    public static final String BASE_JSON_PATH = "json/";
    public static final String BASE_PDF_PATH = "pdf/";
    public static final String BASE_DOMAIN_PATH = "domain/";

    /**
     * Resolves the full path for an HTML resource.
     *
     * @param name the name of the HTML file
     * @return the full path
     */
    public static String resolveHtmlPath(String name) {
        return resolve(BASE_HTML_PATH, name);
    }

    /**
     * Resolves the full path for a template partial.
     * <p>
     * {@link #resolve(String, String)} only validates the name, never the base, so a
     * nested base path needs no change to the resolver — the filename is still rejected
     * if it tries to climb out of the directory.
     * </p>
     *
     * @param filename the partial's filename, e.g. {@code header.html}
     * @return the full path
     */
    public static String resolvePartialPath(String filename) {
        return resolve(BASE_PARTIALS_PATH, filename);
    }

    public static String resolveCssPath(String filename) {
        return resolve(BASE_CSS_PATH, filename);
    }

    public static String resolveJsPath(String filename) {
        return resolve(BASE_JS_PATH, filename);
    }

    public static String resolveSvgPath(String filename) {
        return resolve(BASE_SVG_PATH, filename);
    }
    
    /**
     * Resolves the full path for an image resource.
     *
     * @param filename the image filename
     * @return the full path
     */
    public static String resolveImagePath(String filename) {
        return resolve(BASE_IMAGES_PATH, filename);
    }

    public static String resolvePdfPath(String filename) {
        return resolve(BASE_PDF_PATH, filename);
    }

    public static String resolveDomainPath(String filename) {
        return resolve(BASE_DOMAIN_PATH, filename);
    }

    public static String resolveJsonPath(String filename) {
        return resolve(BASE_JSON_PATH, filename);
    }


    /**
     *
     * Resolve path just base + name.
     * Rejects names containing path traversal sequences.
     *
     * @param base base folder of resource path
     * @param name filename that will be resolved
     * @return resolved path
     * @throws IllegalArgumentException if name contains path traversal characters
     */
    public static String resolve(String base, String name) {
        if (name == null) {
            name = "";
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains("%")) {
            throw new IllegalArgumentException("Invalid resource name: path traversal not allowed");
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + name;
    }
}