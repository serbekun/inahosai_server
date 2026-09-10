package com.serbekun.inahosai.resources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the packaged template resources to the on-disk working directory on first run.
 *
 * <p>The JAR holds only templates: the default assets a fork starts from. The running
 * server reads from disk (see {@link LookupOrder#DISK_ONLY}), so the templates have to
 * exist there before anything is served. This class does that copy once, and records the
 * version it copied in a marker file so later startups skip the work.
 *
 * <p>It is deliberately conservative:
 *
 * <ul>
 *   <li>a file that already exists on disk is never overwritten, so an edited template or
 *       a dropped-in asset survives a re-run;</li>
 *   <li>the marker carries the build version, so upgrading the JAR re-runs the copy and
 *       fills in templates that are new in that version while still leaving existing files
 *       alone;</li>
 *   <li>{@code pdf/} holds a placeholder {@code sample.pdf} that ships with the templates,
 *       so a fresh deployment has a working download out of the box. Real documents are
 *       dropped on disk by the operator and are never packaged.</li>
 * </ul>
 */
public class ResourceUnpacker {

    private static final Logger log = LoggerFactory.getLogger(ResourceUnpacker.class);

    /** Marker file, relative to the disk root, recording the unpacked version. */
    public static final String MARKER_FILE = ".unpacked";

    /** Template directories copied out of the JAR. */
    private static final List<String> TEMPLATE_DIRS =
            List.of("css/", "js/", "html/", "images/", "pdf/");

    /**
     * Directories created on disk even when the JAR ships nothing for them, so a fork can
     * drop files in immediately.
     */
    private static final List<String> EMPTY_DIRS = List.of("pdf/");

    private final ResourceLoader loader;
    private final String version;

    /**
     * Creates an unpacker.
     *
     * @param loader  the loader that reads the packaged classpath resources
     * @param version the build version recorded in the marker
     */
    public ResourceUnpacker(ResourceLoader loader, String version) {
        this.loader = loader;
        this.version = version;
    }

    /**
     * Unpacks the templates when the disk copy is missing or from another version.
     *
     * @return true when files were copied, false when the disk was already current
     */
    public boolean unpack() {
        Path root = loader.overrideRoot();
        Path marker = root.resolve(MARKER_FILE);

        if (Files.isRegularFile(marker)) {
            String unpacked = readMarker(marker);
            if (version.equals(unpacked)) {
                log.info("Resources already unpacked at {} (version {})", root, version);
                return false;
            }
            log.info("Resources at {} are from version {}, updating to {}",
                    root, unpacked.isBlank() ? "unknown" : unpacked, version);
        }

        int copied = 0;
        try {
            Files.createDirectories(root);
            for (String dir : TEMPLATE_DIRS) {
                copied += copyDirectory(root, dir);
            }
            for (String dir : EMPTY_DIRS) {
                Files.createDirectories(root.resolve(dir).normalize());
            }
            Files.writeString(marker, version + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The server cannot serve what it could not unpack, so this is fatal.
            throw new IllegalStateException(
                    "Failed to unpack template resources into " + root, e);
        }

        log.info("Unpacked {} template file(s) into {} (version {})", copied, root, version);
        return true;
    }

    /**
     * Copies every file of one packaged directory that is not already on disk.
     *
     * @param root    the disk root
     * @param dir     the packaged directory, with a trailing slash
     * @return how many files were written
     */
    private int copyDirectory(Path root, String dir) {
        int copied = 0;
        for (String path : loader.listClasspath(dir)) {
            Path target = root.resolve(path).normalize();
            if (!target.startsWith(root)) {
                log.warn("Refusing to unpack outside the resource root: {}", path);
                continue;
            }
            if (Files.exists(target)) {
                continue;
            }
            byte[] data = loader.loadFromClasspath(path);
            if (data == null) {
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, data);
                copied++;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to unpack " + path, e);
            }
        }
        return copied;
    }

    /**
     * Reads the version recorded in the marker.
     *
     * @param marker the marker file
     * @return the trimmed contents, or an empty string when it cannot be read
     */
    private static String readMarker(Path marker) {
        try {
            return Files.readString(marker, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            log.warn("Could not read resource marker {}; treating it as stale", marker, e);
            return "";
        }
    }
}
