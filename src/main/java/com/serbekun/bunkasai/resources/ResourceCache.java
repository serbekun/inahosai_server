package com.serbekun.bunkasai.resources;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.List;

/**
 * Thread-safe cache layer for resources.
 * <p>
 * Only bytes are cached — the server answers with bytes, so keeping a decoded
 * copy would both duplicate memory and re-encode on every response. Text is
 * decoded on demand, always as UTF-8.
 * </p>
 * <p>
 * Misses are cached too: a bot scanning for files that do not exist must not
 * reach disk and classloader on every request.
 * </p>
 */
public class ResourceCache {

    /**
     * A cached resource: its bytes and the ETag computed once at load time.
     * The array is shared with every caller and must not be modified.
     *
     * @param data the resource bytes
     * @param etag the quoted ETag value for these bytes
     */
    public record Entry(byte[] data, String etag) {}

    /** Marker stored for paths that are known not to exist. */
    private static final Entry MISSING = new Entry(null, null);

    private final ResourceLoader loader;
    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    /**
     * Creates a new ResourceCache with the given ResourceLoader.
     *
     * @param loader the resource loader to use
     */
    public ResourceCache(ResourceLoader loader) {
        this.loader = loader;
    }

    /**
     * Gets a resource entry from cache, loading it lazily if not present.
     *
     * @param path the path to the resource
     * @return the cached entry, or null if the resource does not exist
     */
    public Entry get(String path) {
        Entry cached = cache.get(path);
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }

        // Loading happens outside computeIfAbsent so blocking I/O never runs
        // while a ConcurrentHashMap bin is locked.
        byte[] data = loader.loadBinary(path);
        Entry entry = data != null ? new Entry(data, Etags.of(data)) : MISSING;
        cache.put(path, entry);

        return data != null ? entry : null;
    }

    /**
     * Gets binary data from cache, loading it lazily if not present.
     *
     * @param path the path to the resource
     * @return byte array or null if not found
     */
    public byte[] getBinary(String path) {
        Entry entry = get(path);
        return entry != null ? entry.data() : null;
    }

    /**
     * Gets the resource decoded as UTF-8 text.
     *
     * @param path the path to the resource
     * @return string or null if not found
     */
    public String getText(String path) {
        byte[] data = getBinary(path);
        return data != null ? new String(data, StandardCharsets.UTF_8) : null;
    }

    /**
     * Clears all cached resources.
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Checks if a resource lookup is cached (including a cached miss).
     *
     * @param path the path to the resource
     * @return true if cached, false otherwise
     */
    public boolean isCached(String path) {
        return cache.containsKey(path);
    }

    /**
     * Check exist resource on disk or in jar file.
     *
     * @param path path to file
     * @return true exist, false don't exist
     */
    public boolean exists(String path) {
        return get(path) != null;
    }

    /**
     * Returns the resources available under the given directory.
     *
     * @param basePath path to folder
     * @return list of resource paths
     */
    public List<String> listResources(String basePath) {
        return loader.listResources(basePath);
    }

}
