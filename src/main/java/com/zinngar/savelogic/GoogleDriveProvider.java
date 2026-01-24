package com.zinngar.savelogic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpServer;
import com.zinngar.savelogic.util.HttpClient;
import okhttp3.*;

import java.awt.Desktop;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class GoogleDriveProvider implements CloudStorageProvider {

    private static final OkHttpClient client = HttpClient.getClient();
    private static final Gson gson = new Gson();
    private static final String REDIRECT_URI = "http://localhost:8080";
    private HttpServer server;

    public void initiateAuthorization() {
        String authUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=" + SaveLogic.CONFIG.google.clientId +
                "&redirect_uri=" + REDIRECT_URI +
                "&response_type=code" +
                "&scope=https://www.googleapis.com/auth/drive.file" +
                "&access_type=offline";

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(authUrl));
            } else {
                SaveLogic.LOGGER.error("Cannot open browser to initiate Google Drive authorization.");
                return;
            }
        } catch (IOException | URISyntaxException e) {
            SaveLogic.LOGGER.error("Failed to open browser for Google Drive authorization", e);
            return;
        }

        startHttpServer();
    }

    private void startHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/", httpExchange -> {
                String query = httpExchange.getRequestURI().getQuery();
                String code = null;
                if (query != null && query.startsWith("code=")) {
                    code = query.substring(5).split("&")[0];
                }

                String responseText;
                if (code != null) {
                    exchangeCodeForTokens(code);
                    responseText = "<h1>Authorization Successful!</h1><p>You can now close this window and return to Minecraft.</p>";
                } else {
                     responseText = "<h1>Authorization Failed</h1><p>No authorization code received. Please try again.</p>";
                }

                httpExchange.sendResponseHeaders(200, responseText.getBytes(StandardCharsets.UTF_8).length);
                OutputStream os = httpExchange.getResponseBody();
                os.write(responseText.getBytes(StandardCharsets.UTF_8));
                os.close();

                new Thread(() -> server.stop(0)).start();
            });
            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            SaveLogic.LOGGER.error("Failed to start local server for Google Drive authorization", e);
        }
    }

    private void exchangeCodeForTokens(String code) {
        RequestBody body = new FormBody.Builder()
                .add("client_id", SaveLogic.CONFIG.google.clientId)
                .add("client_secret", SaveLogic.CONFIG.google.clientSecret)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .build();

        Request request = new Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String json = response.body().string();
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> tokenMap = gson.fromJson(json, type);

                if (tokenMap.containsKey("refresh_token")) {
                    SaveLogic.CONFIG.google.refreshToken = tokenMap.get("refresh_token");
                    SaveLogic.CONFIG.save();
                    SaveLogic.LOGGER.info("Successfully received and saved Google Drive refresh token.");
                } else {
                    SaveLogic.LOGGER.warn("Did not receive a refresh token. You may need to re-authenticate later.");
                }

            } else {
                SaveLogic.LOGGER.error("Failed to exchange authorization code for tokens: " + response.body().string());
            }
        } catch (IOException e) {
            SaveLogic.LOGGER.error("Error exchanging authorization code for tokens", e);
        }
    }

    @Override
    public CompletableFuture<Void> uploadSave(File zipFile) {
        return getAccessToken().thenAccept(accessToken -> {
            if (accessToken == null) return;
            try {
                String fileId = findFileId(accessToken, zipFile.getName());

                RequestBody fileBody = RequestBody.create(zipFile, MediaType.get("application/zip"));

                RequestBody metadataBody = RequestBody.create(
                    "{\"name\": \"" + zipFile.getName() + "\"}",
                    MediaType.get("application/json; charset=utf-8")
                );

                MultipartBody multipartBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addPart(
                            Headers.of("Content-Disposition", "form-data; name=\"metadata\""),
                            metadataBody
                        )
                        .addPart(
                            Headers.of("Content-Disposition", "form-data; name=\"file\"; filename=\"" + zipFile.getName() + "\""),
                            fileBody
                        )
                        .build();

                String uploadUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart";
                Request.Builder requestBuilder = new Request.Builder()
                        .header("Authorization", "Bearer " + accessToken);

                if (fileId != null) {
                    uploadUrl = "https://www.googleapis.com/upload/drive/v3/files/" + fileId + "?uploadType=multipart";
                    requestBuilder.patch(multipartBody);
                } else {
                    requestBuilder.post(multipartBody);
                }

                Request request = requestBuilder.url(uploadUrl).build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        SaveLogic.LOGGER.error("Failed to upload to Google Drive: " + response.body().string());
                    } else {
                        SaveLogic.LOGGER.info("Successfully uploaded to Google Drive!");
                    }
                }

            } catch (IOException e) {
                SaveLogic.LOGGER.error("Error during Google Drive upload", e);
            }
        });
    }

    private String findFileId(String accessToken, String fileName) throws IOException {
        HttpUrl url = HttpUrl.parse("https://www.googleapis.com/drive/v3/files").newBuilder()
            .addQueryParameter("q", "name='" + fileName + "' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                Type type = new TypeToken<Map<String, List<Map<String, String>>>>(){}.getType();
                Map<String, List<Map<String, String>>> jsonResponse = gson.fromJson(response.body().string(), type);
                List<Map<String, String>> files = jsonResponse.get("files");
                if (files != null && !files.isEmpty()) {
                    return files.get(0).get("id");
                }
            } else {
                 SaveLogic.LOGGER.error("Failed to find file ID: " + response.body().string());
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<File> downloadSave(String saveName) {
        return getAccessToken().thenApply(accessToken -> {
             if (accessToken == null) return null;
            try {
                String fileId = findFileId(accessToken, saveName + ".zip");
                if (fileId == null) {
                    SaveLogic.LOGGER.error("File not found on Google Drive: " + saveName);
                    return null;
                }

                Request request = new Request.Builder()
                        .url("https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media")
                        .header("Authorization", "Bearer " + accessToken)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        File downloadedFile = new File(saveName + ".zip");
                        try (FileOutputStream fos = new FileOutputStream(downloadedFile)) {
                            fos.write(response.body().bytes());
                        }
                        return downloadedFile;
                    } else {
                        SaveLogic.LOGGER.error("Failed to download from Google Drive: " + response.body().string());
                    }
                }
            } catch (IOException e) {
                SaveLogic.LOGGER.error("Error during Google Drive download", e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<List<String>> listSaves() {
        return getAccessToken().thenApply(accessToken -> {
            if (accessToken == null) return new ArrayList<>();
            try {
                 HttpUrl url = HttpUrl.parse("https://www.googleapis.com/drive/v3/files").newBuilder()
                    .addQueryParameter("q", "mimeType='application/zip' and trashed = false")
                    .addQueryParameter("fields", "files(name)")
                    .build();

                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + accessToken)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Type type = new TypeToken<Map<String, List<Map<String, String>>>>(){}.getType();
                        Map<String, List<Map<String, String>>> jsonResponse = gson.fromJson(response.body().string(), type);

                        List<Map<String, String>> files = jsonResponse.get("files");
                        if (files != null) {
                            return files.stream()
                                    .map(file -> file.get("name").replace(".zip", ""))
                                    .collect(Collectors.toList());
                        }
                    } else {
                        SaveLogic.LOGGER.error("Failed to list saves from Google Drive: " + response.body().string());
                    }
                }
            } catch (IOException e) {
                SaveLogic.LOGGER.error("Error during Google Drive list saves", e);
            }
            return new ArrayList<>();
        });
    }

    @Override
    public String getProviderName() {
        return "Google Drive";
    }

    private CompletableFuture<String> getAccessToken() {
        return CompletableFuture.supplyAsync(() -> {
            if (SaveLogic.CONFIG.google.refreshToken == null || SaveLogic.CONFIG.google.refreshToken.isEmpty()) {
                SaveLogic.LOGGER.warn("Not authenticated with Google Drive. Please login first.");
                return null;
            }

            RequestBody body = new FormBody.Builder()
                    .add("client_id", SaveLogic.CONFIG.google.clientId)
                    .add("client_secret", SaveLogic.CONFIG.google.clientSecret)
                    .add("refresh_token", SaveLogic.CONFIG.google.refreshToken)
                    .add("grant_type", "refresh_token")
                    .build();

            Request request = new Request.Builder()
                    .url("https://oauth2.googleapis.com/token")
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String json = response.body().string();
                    Type type = new TypeToken<Map<String, String>>(){}.getType();
                    Map<String, String> tokenMap = gson.fromJson(json, type);
                    return tokenMap.get("access_token");
                } else {
                    SaveLogic.LOGGER.error("Failed to refresh access token: " + response.body().string());
                    SaveLogic.CONFIG.google.refreshToken = "";
                    SaveLogic.CONFIG.save();
                    return null;
                }
            } catch (IOException e) {
                SaveLogic.LOGGER.error("Error refreshing access token", e);
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> testConnection() {
        return getAccessToken().thenApply(accessToken -> accessToken != null);
    }
}
