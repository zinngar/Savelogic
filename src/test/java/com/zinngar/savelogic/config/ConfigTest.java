package com.zinngar.savelogic.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    private static final String TEST_CONFIG_PATH = "config/savelogic.json";

    @BeforeEach
    @AfterEach
    void deleteTestConfig() {
        new File(TEST_CONFIG_PATH).delete();
    }

    @Test
    void load_whenFileDoesNotExist_createsDefaultConfig() {
        Config config = Config.load();

        assertNotNull(config);
        assertEquals("github", config.provider);
        assertNotNull(config.github);
        assertNotNull(config.google);
        assertTrue(new File(TEST_CONFIG_PATH).exists());
    }

    @Test
    void load_whenFileExists_loadsConfigFromFile() throws IOException {
        String testJson = "{\"provider\": \"google\", \"automaticBackup\": true}";
        Files.write(new File(TEST_CONFIG_PATH).toPath(), testJson.getBytes());

        Config config = Config.load();

        assertNotNull(config);
        assertEquals("google", config.provider);
        assertTrue(config.isAutomaticBackup());
    }

    @Test
    void load_whenKeepLocalBackupsIsSet_loadsCorrectly() throws IOException {
        String testJson = "{\"keepLocalBackups\": true}";
        Files.write(new File(TEST_CONFIG_PATH).toPath(), testJson.getBytes());

        Config config = Config.load();

        assertTrue(config.keepLocalBackups);
    }

    @Test
    void save_writesConfigToFile() {
        Config config = new Config();
        config.provider = "google";
        config.save();

        assertTrue(new File(TEST_CONFIG_PATH).exists());
    }
}
