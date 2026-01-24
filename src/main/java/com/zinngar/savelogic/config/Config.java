package com.zinngar.savelogic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.util.CryptoUtils;
import java.io.File;
import java.io.FileReader;
import java.util.UUID;
import java.io.FileWriter;
import java.io.IOException;

public class Config {
    public String provider;
    public boolean automaticBackup = false;
    public GitHubConfig github = new GitHubConfig();
    public GoogleConfig google = new GoogleConfig();

    private transient boolean isEncrypted = false;
    private transient UUID userId;

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
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                Config config = gson.fromJson(reader, Config.class);
                config.decrypt();
                return config;
            } catch (IOException e) {
                SaveLogic.LOGGER.error("Failed to load config", e);
            }
        }
        Config defaultConfig = new Config();
        defaultConfig.provider = "github";
        defaultConfig.save();
        return defaultConfig;
    }

    public void save() {
        this.encrypt();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File configFile = new File("config/savelogic.json");
        configFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(this, writer);
        } catch (IOException e) {
            SaveLogic.LOGGER.error("Failed to save config", e);
        }
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    private void encrypt() {
        if (!isEncrypted) {
            this.google.clientSecret = CryptoUtils.encrypt(this.google.clientSecret, userId);
            this.google.refreshToken = CryptoUtils.encrypt(this.google.refreshToken, userId);
            this.github.personalAccessToken = CryptoUtils.encrypt(this.github.personalAccessToken, userId);
            this.isEncrypted = true;
        }
    }

    private void decrypt() {
        if (isEncrypted) {
            this.google.clientSecret = CryptoUtils.decrypt(this.google.clientSecret, userId);
            this.google.refreshToken = CryptoUtils.decrypt(this.google.refreshToken, userId);
            this.github.personalAccessToken = CryptoUtils.decrypt(this.github.personalAccessToken, userId);
            this.isEncrypted = false;
        }
    }
}
