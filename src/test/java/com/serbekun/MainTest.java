package com.serbekun;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.serbekun.inahosai.config.SiteConfigLoader;

class MainTest {
    @Test
    void applicationStarts() throws Exception {
        // The server refuses to start until the config is confirmed, so the smoke test
        // has to supply a confirmed one through the explicit-config property.
        Path config = Files.createTempFile("inahosai-test", ".yaml");
        Files.writeString(config, "is_setup_readed_and_config_edited: true\n",
                StandardCharsets.UTF_8);
        System.setProperty(SiteConfigLoader.CONFIG_PROPERTY, config.toString());
        try {
            assertThatCode(() -> Main.main(new String[0]))
                    .doesNotThrowAnyException();
        } finally {
            System.clearProperty(SiteConfigLoader.CONFIG_PROPERTY);
            Files.deleteIfExists(config);
        }
    }
}
