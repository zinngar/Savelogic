package com.zinngar.savelogic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class GitHubStorageProvider implements CloudStorageProvider {

    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    @Override
    public CompletableFuture<Void> uploadSave(File zipFile) {
        return CompletableFuture.runAsync(() -> {
            try {
                String[] parts = parseRepoUrl(SaveLogic.CONFIG.github.repositoryUrl);
                String owner = parts[0];
                String repo = parts[1];
                String pat = SaveLogic.CONFIG.github.personalAccessToken;

                String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo;
                String uploadUrl = apiUrl + "/contents/" + zipFile.getName();

                byte[] fileBytes = Files.readAllBytes(zipFile.toPath());
                String encodedFile = Base64.getEncoder().encodeToString(fileBytes);

                Map<String, String> payload = new HashMap<>();
                payload.put("message", "Uploaded world save: " + zipFile.getName());
                payload.put("content", encodedFile);

                Request getRequest = new Request.Builder()
                        .url(uploadUrl)
                        .header("Authorization", "token " + pat)
                        .build();

                try (Response getResponse = client.newCall(getRequest).execute()) {
                    if (getResponse.isSuccessful()) {
                        Type type = new TypeToken<Map<String, Object>>() {}.getType();
                        Map<String, Object> jsonResponse = gson.fromJson(getResponse.body().string(), type);
                        payload.put("sha", (String) jsonResponse.get("sha"));
                    }
                }

                RequestBody body = RequestBody.create(gson.toJson(payload), MediaType.get("application/json; charset=utf-8"));
                Request putRequest = new Request.Builder()
                        .url(uploadUrl)
                        .header("Authorization", "token " + pat)
                        .put(body)
                        .build();

                try (Response putResponse = client.newCall(putRequest).execute()) {
                    if (!putResponse.isSuccessful()) {
                        SaveLogic.LOGGER.error("Failed to upload to GitHub: " + putResponse.body().string());
                    } else {
                        SaveLogic.LOGGER.info("Successfully uploaded to GitHub!");
                    }
                }

            } catch (IOException e) {
                SaveLogic.LOGGER.error("Error during GitHub upload", e);
            }
        });
    }

    @Override
    public CompletableFuture<File> downloadSave(String saveName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String[] parts = parseRepoUrl(SaveLogic.CONFIG.github.repositoryUrl);
                String owner = parts[0];
                String repo = parts[1];
                String pat = SaveLogic.CONFIG.github.personalAccessToken;

                String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo;
                String downloadUrl = apiUrl + "/contents/" + saveName;

                Request request = new Request.Builder()
                        .url(downloadUrl)
                        .header("Authorization", "token " + pat)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Type type = new TypeToken<Map<String, Object>>() {}.getType();
                        Map<String, Object> jsonResponse = gson.fromJson(response.body().string(), type);
                        String encodedContent = (String) jsonResponse.get("content");
                        byte[] decodedBytes = Base64.getDecoder().decode(encodedContent.replace("\n", ""));

                        File downloadedFile = new File(saveName);
                        try (FileOutputStream fos = new FileOutputStream(downloadedFile)) {
                            fos.write(decodedBytes);
                        }
                        return downloadedFile;
                    } else {
                        SaveLogic.LOGGER.error("Failed to download from GitHub: " + response.body().string());
                    }
                }
            } catch (IOException e) {
                SaveLogic.LOGGER.error("Error during GitHub download", e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<List<String>> listSaves() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String[] parts = parseRepoUrl(SaveLogic.CONFIG.github.repositoryUrl);
                String owner = parts[0];
                String repo = parts[1];
                String pat = SaveLogic.CONFIG.github.personalAccessToken;

                String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo;
                String listUrl = apiUrl + "/contents/";

                Request request = new Request.Builder()
                        .url(listUrl)
                        .header("Authorization", "token " + pat)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
                        List<Map<String, Object>> jsonResponse = gson.fromJson(response.body().string(), listType);
                        return jsonResponse.stream()
                                .map(file -> (String) file.get("name"))
                                .filter(name -> name.endsWith(".zip"))
                                .collect(Collectors.toList());
                    } else {
                        SaveLogic.LOGGER.error("Failed to list saves from GitHub: " + response.body().string());
                    }
                }
            } catch (IOException e) {
                SaveLogic.LOGGER.error("Error during GitHub list saves", e);
            }
            return new ArrayList<>();
        });
    }

    @Override
    public String getProviderName() {
        return "GitHub";
    }

    private String[] parseRepoUrl(String url) {
        String[] parts = url.replace("https://github.com/", "").split("/");
        if (parts.length < 2) {
            return new String[]{"", ""};
        }
        return new String[]{parts[0], parts[1]};
    }

    @Override
    public CompletableFuture<Boolean> testConnection() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String[] parts = parseRepoUrl(SaveLogic.CONFIG.github.repositoryUrl);
                String owner = parts[0];
                String repo = parts[1];
                String pat = SaveLogic.CONFIG.github.personalAccessToken;

                String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo;

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .header("Authorization", "token " + pat)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    return response.isSuccessful();
                }
            } catch (IOException e) {
                SaveLogic.LOGGER.error("Failed to test GitHub connection", e);
                return false;
            }
        });
    }
}
