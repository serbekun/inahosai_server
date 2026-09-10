package com.serbekun.inahosai.resources;

import java.io.InputStream;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads resources from the on-disk override directory first, then from the
 * classpath/JAR.
 * <p>
 * The disk side has an explicit root ({@link #overrideRoot()}): every path is
 * resolved against it and normalized, and anything that escapes the root is
 * rejected. This holds even when a caller passes a raw, unvalidated path, so
 * the loader never depends on {@link ResourcesBasePath#resolve} having been
 * called upstream.
 * </p>
 */
public class ResourceLoader {
    private static final Logger log = LoggerFactory.getLogger(ResourceLoader.class);

    /** Directory used for on-disk overrides when none is configured explicitly. */
    public static final Path DEFAULT_OVERRIDE_ROOT = Path.of("data", "static");

    private final Path overrideRoot;
    private final LookupOrder order;

    /** Creates a disk-first loader using {@link #DEFAULT_OVERRIDE_ROOT} as the disk root. */
    public ResourceLoader() {
        this(DEFAULT_OVERRIDE_ROOT);
    }

    /**
     * Creates a disk-first loader with an explicit on-disk override root.
     *
     * @param overrideRoot directory holding files that shadow the packaged ones
     */
    public ResourceLoader(Path overrideRoot) {
        this(overrideRoot, LookupOrder.DISK_FIRST);
    }

    /**
     * Creates a loader with an explicit on-disk override root and lookup order.
     *
     * @param overrideRoot directory holding the on-disk resources
     * @param order        where and in what order a lookup checks disk and classpath
     */
    public ResourceLoader(Path overrideRoot, LookupOrder order) {
        this.overrideRoot = overrideRoot.toAbsolutePath().normalize();
        this.order = order != null ? order : LookupOrder.DISK_FIRST;
    }

    /** The absolute, normalized directory disk lookups are confined to. */
    public Path overrideRoot() {
        return overrideRoot;
    }

    /** The order this loader resolves disk and classpath resources in. */
    public LookupOrder order() {
        return order;
    }

    /**
     * Loads a resource as binary data from disk first, then from the classpath.
     *
     * @param path the resource path, relative and using '/' as separator
     * @return byte array or null if not found or error
     */
    public byte[] loadBinary(String path) {
        if (!isSafeResourcePath(path)) {
            log.warn("Rejected unsafe resource path: {}", path);
            return null;
        }

        switch (order) {
            case DISK_FIRST -> {
                byte[] disk = loadFromDisk(path);
                if (disk != null) {
                    return disk;
                }
                return loadFromClasspath(path);
            }
            case CLASSPATH_FIRST -> {
                byte[] cp = loadFromClasspath(path);
                if (cp != null) {
                    return cp;
                }
                return loadFromDisk(path);
            }
            case DISK_ONLY -> {
                return loadFromDisk(path);
            }
            default -> throw new IllegalStateException("Unhandled lookup order " + order);
        }
    }

    /**
     * Checks whether a resource exists under the configured {@link LookupOrder}.
     *
     * @param path the path to the resource
     * @return true if the resource exists, false otherwise
     */
    public boolean exists(String path) {
        if (!isSafeResourcePath(path)) {
            return false;
        }
        return switch (order) {
            case DISK_ONLY -> resolveOnDisk(path) != null;
            case DISK_FIRST -> resolveOnDisk(path) != null || existsOnClasspath(path);
            case CLASSPATH_FIRST -> existsOnClasspath(path) || resolveOnDisk(path) != null;
        };
    }

    /**
     * Reads a resource from disk.
     *
     * @param path the resource path
     * @return the bytes, or null when the file is absent or unreadable
     */
    private byte[] loadFromDisk(String path) {
        Path file = resolveOnDisk(path);
        if (file == null) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.error("Failed to read resource from disk: {}", file, e);
            return null;
        }
    }

    /**
     * Reads a resource from the classpath (directory or JAR).
     *
     * <p>Public so the first-run unpacker can copy the packaged templates out without
     * going through the cache or the disk root.
     *
     * @param path the resource path
     * @return the bytes, or null when the resource is absent
     */
    public byte[] loadFromClasspath(String path) {
        if (!isSafeResourcePath(path)) {
            log.warn("Rejected unsafe resource path: {}", path);
            return null;
        }
        try (InputStream is = getResourceAsStream(path)) {
            if (is == null) {
                log.debug("Resource not found: {}", path);
                return null;
            }
            return is.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to load binary resource: {}", path, e);
            return null;
        }
    }

    private boolean existsOnClasspath(String path) {
        try (InputStream is = getResourceAsStream(path)) {
            return is != null;
        } catch (IOException e) {
            log.error("Error checking if resource exists: {}", path, e);
            return false;
        }
    }

    /**
     * Resolves a resource path to a readable file inside {@link #overrideRoot()}.
     * <p>
     * The path is normalized before the containment check, so {@code ..}
     * segments cannot escape, and the resolved file is compared through
     * {@link Path#toRealPath} so a symlink pointing outside the root is
     * rejected as well.
     * </p>
     *
     * @param path the resource path
     * @return the real path of an existing regular file, or null
     */
    private Path resolveOnDisk(String path) {
        Path candidate = overrideRoot.resolve(path).normalize();
        if (!candidate.startsWith(overrideRoot)) {
            log.warn("Path escape attempt: {}", path);
            return null;
        }
        if (!Files.isRegularFile(candidate)) {
            return null;
        }

        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(overrideRoot.toRealPath())) {
                log.warn("Symlink escapes resource root: {} -> {}", path, real);
                return null;
            }
            return real;
        } catch (IOException e) {
            log.debug("Cannot resolve real path for {}", candidate, e);
            return null;
        }
    }

    /**
     * Rejects paths that are absolute, empty or contain traversal segments.
     * Applied to classpath lookups as well — the classloader does not confine
     * lookups on its own.
     *
     * @param path the resource path to validate
     * @return true if the path is safe to resolve
     */
    private static boolean isSafeResourcePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (path.startsWith("/") || path.contains("\\")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets an InputStream for a resource using the class loader.
     *
     * @param name resource name
     * @return InputStream or null if not found
     */
    private InputStream getResourceAsStream(String name) {
        // Use class loader of this class (important in JAR / module path)
        return ResourceLoader.class.getClassLoader().getResourceAsStream(name);
    }

    /**
     * Returns a list of all resources at the specified basePath (e.g. "html/", "images/", etc.)
     * <p>
     * Disk and classpath listings are merged, so a single overriding file on
     * disk does not hide the rest of the packaged directory.
     * </p>
     *
     * @param basePath path inside classpath ( must end with '/')
     * @return list of path to files (example: "html/index.html", "images/logo.png")
     */
    public List<String> listResources(String basePath) {
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        if (!isSafeResourcePath(basePath)) {
            log.warn("Rejected unsafe resource path: {}", basePath);
            return List.of();
        }

        // A disk-only deployment lists disk-only: the classpath is a template source the
        // unpacker consumed at startup, not a live second tree.
        if (order == LookupOrder.DISK_ONLY) {
            return listFromDisk(basePath);
        }
        if (order == LookupOrder.CLASSPATH_FIRST) {
            Set<String> merged = new LinkedHashSet<>(listFromClasspath(basePath));
            merged.addAll(listFromDisk(basePath));
            return merged.stream().sorted().toList();
        }

        Set<String> merged = new LinkedHashSet<>(listFromDisk(basePath));
        merged.addAll(listFromClasspath(basePath));

        return merged.stream().sorted().toList();
    }

    /**
     * Lists the resources packaged in the classpath (directory or JAR) only.
     *
     * <p>Public so the first-run unpacker can enumerate the templates to copy without
     * merging in the destination directory it is about to write to.
     *
     * @param basePath directory to list, with or without a trailing slash
     * @return distinct, sorted resource paths
     */
    public List<String> listClasspath(String basePath) {
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        if (!isSafeResourcePath(basePath)) {
            log.warn("Rejected unsafe resource path: {}", basePath);
            return List.of();
        }
        return listFromClasspath(basePath);
    }

    /**
     * Lists all files of a directory inside the on-disk override root.
     *
     * @param basePath path to the directory, relative to the override root
     * @return list of resource paths found on disk
     */
    private List<String> listFromDisk(String basePath) {
        Path dir = overrideRoot.resolve(basePath).normalize();
        if (!dir.startsWith(overrideRoot)) {
            log.warn("Path escape attempt: {}", basePath);
            return List.of();
        }
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        return listFromDirectory(dir, basePath);
    }

    /**
     * Lists all resources from classpath (JAR or directory).
     *
     * @param basePath base path to search under
     * @return distinct sorted list of resource paths
     */
    private List<String> listFromClasspath(String basePath) {
        List<String> result = new ArrayList<>();

        try {
            Enumeration<URL> urls = ResourceLoader.class.getClassLoader().getResources(basePath);

            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                String protocol = url.getProtocol().toLowerCase();

                if ("jar".equals(protocol)) {
                    result.addAll(listFromJar(url, basePath));
                } else if ("file".equals(protocol)) {
                    result.addAll(listFromDirectory(Paths.get(url.toURI()), basePath));
                }
            }
        } catch (Exception e) {
            log.error("Failed to list resources from classpath: {}", basePath, e);
        }

        return result.stream().distinct().sorted().toList();
    }

    /**
     * Lists resources inside a JAR file under the given base path.
     * <p>
     * The JAR is opened through {@link JarURLConnection} rather than by parsing
     * the URL, so paths containing spaces or non-ASCII characters (which arrive
     * percent-encoded from {@link URL#getPath()}) work. JAR caching is disabled
     * so closing this handle cannot break a shared one.
     * </p>
     *
     * @param jarUrl  URL to the JAR
     * @param basePath base directory path inside the JAR
     * @return list of matching resource names
     */
    private List<String> listFromJar(URL jarUrl, String basePath) {
        List<String> result = new ArrayList<>();

        try {
            URLConnection connection = jarUrl.openConnection();
            if (!(connection instanceof JarURLConnection jarConnection)) {
                return result;
            }
            jarConnection.setUseCaches(false);

            try (JarFile jarFile = jarConnection.getJarFile()) {
                Enumeration<JarEntry> entries = jarFile.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (name.startsWith(basePath) && !entry.isDirectory()) {
                        result.add(name);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to read JAR file: {}", jarUrl, e);
        }

        return result;
    }

    /**
     * Lists files under a directory on the filesystem, recursively.
     *
     * <p>Subdirectories are kept in the result (e.g. {@code html/partials/head.html}) so
     * that a directory-backed classpath lists the same files a JAR does, which is what
     * lets the unpacker copy partials from a development build.
     *
     * @param dir      directory path
     * @param basePath base path prefix to use for results
     * @return list of resource paths
     */
    private List<String> listFromDirectory(Path dir, String basePath) {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> basePath + dir.relativize(p).toString().replace('\\', '/'))
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list directory {}", dir, e);
            return List.of();
        }
    }
}
