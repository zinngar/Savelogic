package com.zinngar.savelogic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "cloudsaves.json");

    public String provider = "google";
    public GoogleConfig google = new GoogleConfig();
    public GitHubConfig github = new GitHubConfig();

    public static class GoogleConfig {
        public String clientId = "YOUR_CLIENT_ID_HERE";
        public String clientSecret = "YOUR_CLIENT_SECRET_HERE";
        public String refreshToken = "";
    }

    public static class GitHubConfig {
        public String personalAccessToken = "YOUR_PAT_HERE";
        public String repositoryUrl = "";
    }

    public static Config load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                return GSON.fromJson(reader, Config.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Config config = new Config();
        config.save();
        return config;
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
