package com.zinngar.savelogic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.util.CryptoUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean automaticBackup = false;
    public GitHubConfig github = new GitHubConfig();

    public boolean isAutomaticBackup() {
        return automaticBackup;
    }

    public static class GitHubConfig {
        public String repositoryUrl = "";
        public String personalAccessToken = "";
    }

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("savelogic.json");
    }

    /** Loads the config from disk. The returned object always holds the token in plain text. */
    public static Config load() {
        Path file = configFile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Config config = GSON.fromJson(reader, Config.class);
                if (config == null) {
                    config = new Config();
                }
                if (config.github == null) {
                    config.github = new GitHubConfig();
                }
                config.github.personalAccessToken = CryptoUtils.decrypt(config.github.personalAccessToken);
                return config;
            } catch (IOException | RuntimeException e) {
                SaveLogic.LOGGER.error("Failed to load config, using defaults", e);
            }
        }
        Config defaultConfig = new Config();
        defaultConfig.save();
        return defaultConfig;
    }

    /** Writes the config to disk with the token encrypted. This object is left unchanged. */
    public void save() {
        Config onDisk = new Config();
        onDisk.automaticBackup = this.automaticBackup;
        onDisk.github.repositoryUrl = this.github.repositoryUrl;
        onDisk.github.personalAccessToken = CryptoUtils.encrypt(this.github.personalAccessToken);

        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(onDisk, writer);
            }
        } catch (IOException e) {
            SaveLogic.LOGGER.error("Failed to save config", e);
        }
    }
}
