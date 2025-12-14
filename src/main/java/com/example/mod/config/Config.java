package com.example.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.example.mod.CloudSavesCommon;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Config {
    public String provider;
    public GitHubConfig github = new GitHubConfig();
    public GoogleConfig google = new GoogleConfig();

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
        File configFile = new File("config/cloudsaves.json");
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                return gson.fromJson(reader, Config.class);
            } catch (IOException e) {
                CloudSavesCommon.LOGGER.error("Failed to load config", e);
            }
        }
        return new Config();
    }

    public void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File configFile = new File("config/cloudsaves.json");
        configFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(this, writer);
        } catch (IOException e) {
            CloudSavesCommon.LOGGER.error("Failed to save config", e);
        }
    }
}
