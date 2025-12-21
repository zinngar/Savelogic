package com.zinngar.savelogic.client;

import com.zinngar.savelogic.CloudStorageProvider;
import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.GoogleDriveProvider;
import com.zinngar.savelogic.SaveLogic;

public class CloudStorageManager {

    private static CloudStorageProvider provider;

    public static void initialize() {
        if ("google".equalsIgnoreCase(SaveLogic.CONFIG.provider)) {
            provider = new GoogleDriveProvider();
        } else if ("github".equalsIgnoreCase(SaveLogic.CONFIG.provider)) {
            provider = new GitHubStorageProvider();
        }
    }

    public static CloudStorageProvider getProvider() {
        if (provider == null) {
            initialize();
        }
        return provider;
    }
}
