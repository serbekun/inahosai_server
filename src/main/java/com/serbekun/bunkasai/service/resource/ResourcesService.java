package com.serbekun.bunkasai.service.resource;

import com.serbekun.bunkasai.resources.ResourceCache;
import com.serbekun.bunkasai.resources.ResourcesBasePath;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public API for loading static resources.
 * <p>
 * Every method that takes a caller-supplied name runs it through
 * {@link ResourcesBasePath#resolve}, so a name coming from a URL can never
 * address anything outside its own resource directory. Raw-path access is
 * deliberately not part of this API.
 * </p>
 */
public class ResourcesService {

    private static final Logger log = LoggerFactory.getLogger(ResourcesService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ResourceCache cache;

    /**
     * A resource ready to be written to a response.
     *
     * @param data        the resource bytes; must not be modified
     * @param etag        the ETag of these bytes
     * @param contentType the content type derived from the file extension
     */
    public record ResourceData(byte[] data, String etag, String contentType) {}

    /**
     * Creates a new ResourcesService.
     *
     * @param cache the resource cache
     */
    public ResourcesService(ResourceCache cache) {
        this.cache = cache;
    }

    /**
     * Loads a single file of a resource directory together with its ETag and
     * content type.
     *
     * @param basePath the resource directory, e.g. {@link ResourcesBasePath#BASE_CSS_PATH}
     * @param name     the file name inside that directory
     * @return the resource, or null if the name is empty or the file is missing
     * @throws IllegalArgumentException if the name contains path traversal characters
     */
    public ResourceData getResource(String basePath, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        String path = ResourcesBasePath.resolve(basePath, name);
        ResourceCache.Entry entry = cache.get(path);
        if (entry == null) {
            return null;
        }

        return new ResourceData(entry.data(), entry.etag(), detectMimeType(name));
    }

    /**
     * Detects MIME type based on file extension.
     *
     * @param filename the filename
     * @return MIME type string or "application/octet-stream" if unknown
     */
    public String detectMimeType(String filename) {
        String extension = getFileExtension(filename);
        return MIME_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    /**
     * Clears all cached resources.
     */
    public void clearCache() {
        cache.clear();
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot + 1).toLowerCase();
    }

    /**
     * Return list of available files in directory.
     *
     * @param basePath path to folder
     * @return list of available files in directory
     */
    public List<String> listResources(String basePath) {
        return cache.listResources(basePath);
    }

    /**
     * Returns a JSON array of the file names under {@code basePath}, with the
     * base path stripped from each entry.
     *
     * @param basePath the directory to list
     * @return the JSON listing, or {@code null} if serialization fails
     */
    public String listAsJson(String basePath) {
        List<String> filesList = listResources(basePath).stream()
            .map(file -> file.startsWith(basePath) ? file.substring(basePath.length()) : file)
            .toList();
        try {
            return OBJECT_MAPPER.writeValueAsString(filesList);
        } catch (Exception e) {
            log.warn("Failed to serialize resource listing for {}", basePath, e);
            return null;
        }
    }

    // region Static resource helpers

    /**
     * Returns the binary data for an image resource.
     *
     * @param name the name of the image resource
     * @return the binary data, or null if the name is empty or the file is missing
     */
    public byte[] getImage(String name) {
        return getBytes(ResourcesBasePath.BASE_IMAGES_PATH, name);
    }

    /**
     * Returns the binary data for a PDF resource.
     *
     * @param name the name of the PDF resource
     * @return the binary data, or null if the name is empty or the file is missing
     */
    public byte[] getPdf(String name) {
        return getBytes(ResourcesBasePath.BASE_PDF_PATH, name);
    }

    /**
     * Returns the JSON data for a resource.
     *
     * @param name the name of the JSON resource
     * @return the file content, or null if the name is empty or the file is missing
     */
    public String getJson(String name) {
        return getText(ResourcesBasePath.BASE_JSON_PATH, name);
    }

    /**
     * Returns the text of a template partial.
     * <p>
     * Partials are build-time template fragments. They are never served over HTTP and
     * never appear in a listing.
     * </p>
     *
     * @param name the partial's filename, e.g. {@code header.html}
     * @return the file content, or null if the name is empty or the file is missing
     */
    public String getPartial(String name) {
        return getText(ResourcesBasePath.BASE_PARTIALS_PATH, name);
    }

    /**
     * Returns the HTML content for a given resource name.
     *
     * @param name the name of the HTML resource
     * @return the file content, or null if the name is empty or the file is missing
     */
    public String getHtml(String name) {
        return getText(ResourcesBasePath.BASE_HTML_PATH, name);
    }

    /**
     * Returns the CSS content for a given resource name.
     *
     * @param name the name of the CSS resource
     * @return the file content, or null if the name is empty or the file is missing
     */
    public String getCss(String name) {
        return getText(ResourcesBasePath.BASE_CSS_PATH, name);
    }

    /**
     * Returns the JavaScript content for a given resource name.
     *
     * @param name the name of the JavaScript resource
     * @return the file content, or null if the name is empty or the file is missing
     */
    public String getJs(String name) {
        return getText(ResourcesBasePath.BASE_JS_PATH, name);
    }

    /**
     * Returns the SVG content for a given resource name.
     *
     * @param name the name of the SVG resource
     * @return the file content, or null if the name is empty or the file is missing
     */
    public String getSvg(String name) {
        return getText(ResourcesBasePath.BASE_SVG_PATH, name);
    }

    /**
     * Returns the content of a domain description file.
     *
     * @param name the name of the domain resource
     * @return the file content, or null if the name is empty or the file is missing
     */
    public String getDomain(String name) {
        return getText(ResourcesBasePath.BASE_DOMAIN_PATH, name);
    }

    // endregion

    /**
     * Reads a named file of a resource directory as UTF-8 text.
     *
     * @param basePath the resource directory
     * @param name     the file name inside that directory
     * @return the text, or null if the name is empty or the file is missing
     */
    private String getText(String basePath, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return cache.getText(ResourcesBasePath.resolve(basePath, name));
    }

    /**
     * Reads a named file of a resource directory as bytes.
     *
     * @param basePath the resource directory
     * @param name     the file name inside that directory
     * @return the bytes, or null if the name is empty or the file is missing
     */
    private byte[] getBytes(String basePath, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return cache.getBinary(ResourcesBasePath.resolve(basePath, name));
    }

    /**
     * A map of file extensions to their corresponding MIME types.
     * Text formats carry an explicit charset: the content is Japanese, and the
     * header must decide the encoding rather than an in-document meta tag.
     */
    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
        Map.entry("html", "text/html; charset=utf-8"),
        Map.entry("htm", "text/html; charset=utf-8"),
        Map.entry("css", "text/css; charset=utf-8"),
        Map.entry("js", "application/javascript; charset=utf-8"),
        Map.entry("json", "application/json; charset=utf-8"),
        Map.entry("xml", "application/xml; charset=utf-8"),
        Map.entry("txt", "text/plain; charset=utf-8"),
        Map.entry("svg", "image/svg+xml; charset=utf-8"),
        Map.entry("webmanifest", "application/manifest+json; charset=utf-8"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("png", "image/png"),
        Map.entry("gif", "image/gif"),
        Map.entry("bmp", "image/bmp"),
        Map.entry("webp", "image/webp"),
        Map.entry("avif", "image/avif"),
        Map.entry("ico", "image/x-icon"),
        Map.entry("woff", "font/woff"),
        Map.entry("woff2", "font/woff2"),
        Map.entry("ttf", "font/ttf"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("zip", "application/zip")
    );
}
