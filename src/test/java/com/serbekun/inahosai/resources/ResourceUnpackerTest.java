package com.serbekun.inahosai.resources;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceUnpackerTest {

    @TempDir
    Path root;

    private ResourceLoader loader() {
        return new ResourceLoader(root, LookupOrder.DISK_ONLY);
    }

    @Test
    void firstRunCopiesTemplatesIncludingThePlaceholderPdf() throws IOException {
        boolean unpacked = new ResourceUnpacker(loader(), "v1").unpack();

        assertThat(unpacked).isTrue();
        assertThat(Files.isRegularFile(root.resolve("html/index.html"))).isTrue();
        assertThat(Files.isRegularFile(root.resolve("html/partials/header.html"))).isTrue();
        assertThat(Files.isRegularFile(root.resolve("css/styles.css"))).isTrue();
        assertThat(Files.isRegularFile(root.resolve("pdf/sample.pdf"))).isTrue();
        assertThat(Files.isRegularFile(root.resolve(ResourceUnpacker.MARKER_FILE))).isTrue();
        assertThat(Files.isDirectory(root.resolve("pdf"))).isTrue();
    }

    @Test
    void asecondRunWithTheSameVersionSkipsTheCopy() {
        new ResourceUnpacker(loader(), "v1").unpack();

        assertThat(new ResourceUnpacker(loader(), "v1").unpack()).isFalse();
    }

    @Test
    void anExistingFileIsNeverOverwritten() throws IOException {
        Files.createDirectories(root.resolve("html"));
        Files.writeString(root.resolve("html/index.html"), "edited", StandardCharsets.UTF_8);

        new ResourceUnpacker(loader(), "v1").unpack();

        assertThat(Files.readString(root.resolve("html/index.html"))).isEqualTo("edited");
    }

    @Test
    void aNewVersionFillsInMissingTemplatesAndUpdatesTheMarker() throws IOException {
        new ResourceUnpacker(loader(), "v1").unpack();
        Files.delete(root.resolve("html/setup.html"));

        boolean unpacked = new ResourceUnpacker(loader(), "v2").unpack();

        assertThat(unpacked).isTrue();
        assertThat(Files.isRegularFile(root.resolve("html/setup.html"))).isTrue();
        assertThat(Files.readString(root.resolve(ResourceUnpacker.MARKER_FILE)).strip())
                .isEqualTo("v2");
    }

    @Test
    void diskOnlyLookupIgnoresTheClasspath() {
        ResourceLoader loader = loader();

        assertThat(loader.loadBinary("html/index.html")).isNull();
    }
}
