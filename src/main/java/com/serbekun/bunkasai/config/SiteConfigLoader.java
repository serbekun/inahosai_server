package com.serbekun.bunkasai.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds and parses the site config.
 *
 * <p>Resolution order is {@code $BUNKASAI_CONFIG}, then {@code ./config.yaml}, then the
 * bundled {@code config.default.yaml} from the classpath. Whichever wins is logged, so a
 * fork can always tell which file the running site is actually reading.
 *
 * <p>Unknown keys fail loudly. A typo in a fork's config should stop the server at
 * startup with a message naming the key, not silently leave a value at its default —
 * that failure mode is much harder to notice than a crash.
 */
public final class SiteConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(SiteConfigLoader.class);

    /** Environment variable naming an explicit config file. */
    public static final String CONFIG_ENV = "BUNKASAI_CONFIG";

    /** Config file looked for in the working directory. */
    public static final String LOCAL_CONFIG = "config.yaml";

    /** Config bundled in the jar, used when nothing else is present. */
    public static final String DEFAULT_CONFIG = "config.default.yaml";

    private final ObjectMapper mapper = newMapper();

    /**
     * Builds the YAML mapper.
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is deliberately left at its default of true —
     * the "fail loudly on a typo" requirement is met by not disabling it.
     *
     * @return a mapper configured for this project's YAML dialect
     */
    private static ObjectMapper newMapper() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        yaml.registerModule(new JavaTimeModule());
        yaml.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        yaml.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        return yaml;
    }

    /**
     * Loads the config from the first source that exists.
     *
     * @return the parsed config
     * @throws IllegalStateException if the chosen source cannot be read or parsed
     */
    public SiteConfig load() {
        String fromEnv = System.getenv(CONFIG_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path path = Path.of(fromEnv);
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(
                        CONFIG_ENV + " points at '" + fromEnv + "', which is not a readable file");
            }
            return loadFile(path);
        }

        Path local = Path.of(LOCAL_CONFIG);
        if (Files.isRegularFile(local)) {
            return loadFile(local);
        }

        return loadBundledDefault();
    }

    /**
     * Parses a config file from disk.
     *
     * @param path the file to read
     * @return the parsed config
     * @throws IllegalStateException if the file cannot be read or parsed
     */
    public SiteConfig loadFile(Path path) {
        log.info("Loading site config from {}", path.toAbsolutePath());
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in, path.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read site config " + path, e);
        }
    }

    /**
     * Parses the config bundled in the jar.
     *
     * @return the parsed config
     * @throws IllegalStateException if the bundled config is missing or unparseable
     */
    public SiteConfig loadBundledDefault() {
        log.info("Loading bundled site config {} (no {} and no {})",
                DEFAULT_CONFIG, CONFIG_ENV, LOCAL_CONFIG);
        try (InputStream in = SiteConfigLoader.class.getClassLoader()
                .getResourceAsStream(DEFAULT_CONFIG)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Bundled " + DEFAULT_CONFIG + " is missing from the classpath");
            }
            return parse(in, DEFAULT_CONFIG);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled " + DEFAULT_CONFIG, e);
        }
    }

    /**
     * Parses YAML into a config.
     *
     * @param in     the YAML stream
     * @param source a human-readable name for the stream, used in error messages
     * @return the parsed config
     * @throws IllegalStateException if the YAML is malformed or contains unknown keys
     */
    public SiteConfig parse(InputStream in, String source) {
        String yaml;
        try {
            // Config files are a few kilobytes at most, so reading the whole document
            // up front costs nothing and lets the blank check below be uniform.
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read site config " + source, e);
        }
        return parse(yaml, source);
    }

    /**
     * Parses YAML held in memory. Convenient for tests.
     *
     * @param yaml the YAML document
     * @return the parsed config
     */
    public SiteConfig parse(String yaml) {
        return parse(yaml, "site config");
    }

    /**
     * Parses a YAML document.
     *
     * @param yaml   the YAML document
     * @param source a human-readable name for the document, used in error messages
     * @return the parsed config, or an empty config when the document is blank
     * @throws IllegalStateException if the YAML is malformed or contains unknown keys
     */
    public SiteConfig parse(String yaml, String source) {
        // An empty document is a legitimate "nothing configured yet" state, not an error.
        if (yaml == null || yaml.isBlank()) {
            return emptyConfig();
        }
        try {
            SiteConfig config = mapper.readValue(yaml, SiteConfig.class);
            return config != null ? config : emptyConfig();
        } catch (JsonProcessingException e) {
            // getOriginalMessage() drops Jackson's location suffix, which for an unknown
            // key would otherwise bury the key name under a stack of type information.
            throw new IllegalStateException(
                    "Invalid site config in " + source + ": " + e.getOriginalMessage(), e);
        }
    }

    /**
     * A config with nothing set. Its compact constructors fill in the empty nested
     * records, so it renders as an unconfigured — but not broken — site.
     *
     * @return an empty config
     */
    public static SiteConfig emptyConfig() {
        return new SiteConfig(null, null, null, null, null, null, null, null);
    }
}
