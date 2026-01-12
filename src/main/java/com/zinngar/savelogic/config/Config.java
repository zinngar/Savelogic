package com.zinngar.savelogic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zinngar.savelogic.SaveLogic;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Config {
    public String provider;
    public boolean automaticBackup = false;
    public boolean keepLocalBackups = false;
    public GitHubConfig github = new GitHubConfig();
    public GoogleConfig google = new GoogleConfig();

    public boolean isAutomaticBackup() {
        return automaticBackup;
    }

    public static class GitHubConfig {
        public String repositoryUrl;
        public String personalAccessToken;
    }

    public static class GoogleConfig {
        public String clientId;
        public String clientSecret;
        public String refreshToken;
    }

    public static Config load() {
        Gson gson = new Gson();
        File configFile = new File("config/savelogic.json");
        Config config = null;
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                config = gson.fromJson(reader, Config.class);
            } catch (IOException e) {
                SaveLogic.LOGGER.error("Failed to load config file, creating a new one.", e);
            }
        }

        if (config == null) {
            config = new Config();
        }

        boolean needsSave = false;
        if (config.provider == null || config.provider.isEmpty()) {
            config.provider = "github";
            needsSave = true;
        }
        if (config.github == null) {
            config.github = new GitHubConfig();
            needsSave = true;
        }
        if (config.google == null) {
            config.google = new GoogleConfig();
            needsSave = true;
        }

        if (needsSave) {
            config.save();
        }

        return config;
    }

    public void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File configFile = new File("config/savelogic.json");
        configFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(this, writer);
        } catch (IOException e) {
            SaveLogic.LOGGER.error("Failed to save config", e);
        }
    }
}
