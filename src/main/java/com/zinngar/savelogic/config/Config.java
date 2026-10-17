package com.zinngar.savelogic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.util.CryptoUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean automaticBackup = false;
    /** Upload synced worlds automatically when leaving them. */
    public boolean autoUpload = true;
    /** How many versions of each world to keep in the cloud. */
    public int keepVersions = 5;
    /** Random id for this computer, used for world locks; never changes. */
    public String deviceId = "";
    /** Name shown for this computer in version lists and lock warnings. */
    public String deviceName = "";
    public GitHubConfig github = new GitHubConfig();

    public GitHubStorageProvider.Device device() {
        return new GitHubStorageProvider.Device(deviceId, deviceName.isBlank() ? defaultDeviceName() : deviceName);
    }

    static String defaultDeviceName() {
        String name = System.getenv("COMPUTERNAME"); // Windows
        if (name == null || name.isBlank()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                name = "";
            }
        }
        name = name.replaceFirst("\\.local$", "").replaceFirst("\\.lan$", "");
        return name.isBlank() ? "My computer" : name;
    }

    /** Fills in values that older config files don't have. Returns true if anything changed. */
    private boolean fillDefaults() {
        boolean changed = false;
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = UUID.randomUUID().toString();
            changed = true;
        }
        if (deviceName == null || deviceName.isBlank()) {
            deviceName = defaultDeviceName();
            changed = true;
        }
        if (keepVersions < 1) {
            keepVersions = 5;
            changed = true;
        }
        return changed;
    }

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
                if (config.fillDefaults()) {
                    config.save();
                }
                return config;
            } catch (IOException | RuntimeException e) {
                SaveLogic.LOGGER.error("Failed to load config, using defaults", e);
            }
        }
        Config defaultConfig = new Config();
        defaultConfig.fillDefaults();
        defaultConfig.save();
        return defaultConfig;
    }

    /** Writes the config to disk with the token encrypted. This object is left unchanged. */
    public void save() {
        Config onDisk = new Config();
        onDisk.automaticBackup = this.automaticBackup;
        onDisk.autoUpload = this.autoUpload;
        onDisk.keepVersions = this.keepVersions;
        onDisk.deviceId = this.deviceId;
        onDisk.deviceName = this.deviceName;
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
