package com.example.mod;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;

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
                String repoUrl = CloudSaves.CONFIG.github.repositoryUrl;
                String pat = CloudSaves.CONFIG.github.personalAccessToken;

                // e.g. https://github.com/user/repo -> api.github.com/repos/user/repo
                String apiUrl = repoUrl.replace("github.com", "api.github.com/repos");
                String uploadUrl = apiUrl + "/contents/" + zipFile.getName();

                byte[] fileBytes = Files.readAllBytes(zipFile.toPath());
                String encodedFile = Base64.getEncoder().encodeToString(fileBytes);

                Map<String, String> payload = new HashMap<>();
                payload.put("message", "Uploaded world save: " + zipFile.getName());
                payload.put("content", encodedFile);

                // Check if the file exists to get its SHA, required for updates
                Request getRequest = new Request.Builder()
                        .url(uploadUrl)
                        .header("Authorization", "token " + pat)
                        .build();

                try (Response getResponse = client.newCall(getRequest).execute()) {
                    if (getResponse.isSuccessful()) {
                        Map<String, Object> jsonResponse = gson.fromJson(getResponse.body().string(), Map.class);
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
                        CloudSaves.LOGGER.error("Failed to upload to GitHub: " + putResponse.body().string());
                    } else {
                        CloudSaves.LOGGER.info("Successfully uploaded to GitHub!");
                    }
                }

            } catch (IOException e) {
                CloudSaves.LOGGER.error("Error during GitHub upload", e);
            }
        });
    }

    @Override
    public CompletableFuture<File> downloadSave(String saveName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String repoUrl = CloudSaves.CONFIG.github.repositoryUrl;
                String pat = CloudSaves.CONFIG.github.personalAccessToken;
                String apiUrl = repoUrl.replace("github.com", "api.github.com/repos");
                String downloadUrl = apiUrl + "/contents/" + saveName;

                Request request = new Request.Builder()
                        .url(downloadUrl)
                        .header("Authorization", "token " + pat)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Map<String, Object> jsonResponse = gson.fromJson(response.body().string(), Map.class);
                        String encodedContent = (String) jsonResponse.get("content");
                        byte[] decodedBytes = Base64.getDecoder().decode(encodedContent.replace("\n", ""));

                        File downloadedFile = new File(saveName);
                        try (FileOutputStream fos = new FileOutputStream(downloadedFile)) {
                            fos.write(decodedBytes);
                        }
                        return downloadedFile;
                    } else {
                        CloudSaves.LOGGER.error("Failed to download from GitHub: " + response.body().string());
                    }
                }
            } catch (IOException e) {
                CloudSaves.LOGGER.error("Error during GitHub download", e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<List<String>> listSaves() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String repoUrl = CloudSaves.CONFIG.github.repositoryUrl;
                String pat = CloudSaves.CONFIG.github.personalAccessToken;
                String apiUrl = repoUrl.replace("github.com", "api.github.com/repos");
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
                        CloudSaves.LOGGER.error("Failed to list saves from GitHub: " + response.body().string());
                    }
                }
            } catch (IOException e) {
                CloudSaves.LOGGER.error("Error during GitHub list saves", e);
            }
            return new ArrayList<>();
        });
    }

    @Override
    public String getProviderName() {
        return "GitHub";
    }
}
