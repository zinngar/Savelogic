package com.zinngar.savelogic.client;

import com.zinngar.savelogic.GitHubStorageProvider;
import com.zinngar.savelogic.SaveLogic;

public class CloudStorageManager {

    /** Returns a provider for the current settings, or null if the repository or token is missing. */
    public static GitHubStorageProvider getProvider() {
        GitHubStorageProvider provider = new GitHubStorageProvider(
                SaveLogic.CONFIG.github.repositoryUrl,
                SaveLogic.CONFIG.github.personalAccessToken);
        return provider.isConfigured() ? provider : null;
    }
}
