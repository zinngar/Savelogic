package com.zinngar.savelogic.mods;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zinngar.savelogic.util.Progress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The parts of Modrinth's public API (https://docs.modrinth.com) used to find mod updates. Mods are identified by the
 * SHA-1 of their jar, so no mod ids need to be configured. No account or key is needed.
 */
public class ModrinthClient {

    /** A mod version on Modrinth, reduced to its primary file and its required dependencies. */
    public record Version(String id, String projectId, String versionNumber, Instant published, String fileName,
                          String url, String sha1, long size, List<String> requiredProjects) {}

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String base;
    private final String userAgent;

    /** userAgentVersion goes into the User-Agent, which Modrinth asks every client to set uniquely. */
    public ModrinthClient(String userAgentVersion) {
        // The system property only exists for SaveLogic's own tests, which use a local fake Modrinth.
        this.base = System.getProperty("savelogic.testModrinthBase", "https://api.modrinth.com");
        this.userAgent = "zinngar/Savelogic/" + userAgentVersion + " (github.com/zinngar/Savelogic)";
    }

    /** The Modrinth version each jar (by SHA-1) belongs to; jars not on Modrinth are missing from the result. */
    public Map<String, Version> versionsByHash(Collection<String> sha1s) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.add("hashes", array(sha1s));
        body.addProperty("algorithm", "sha1");
        return parseVersionMap(post("/v2/version_files", body));
    }

    /** For each jar (by SHA-1), the newest version of the same mod for this Minecraft version and loader. */
    public Map<String, Version> latestByHash(Collection<String> sha1s, String gameVersion, String loader)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.add("hashes", array(sha1s));
        body.addProperty("algorithm", "sha1");
        body.add("loaders", array(List.of(loader)));
        body.add("game_versions", array(List.of(gameVersion)));
        return parseVersionMap(post("/v2/version_files/update", body));
    }

    /** Project id -> display name. */
    public Map<String, String> projectTitles(Collection<String> projectIds) throws IOException, InterruptedException {
        Map<String, String> titles = new HashMap<>();
        if (projectIds.isEmpty()) {
            return titles;
        }
        JsonArray projects = GSON.fromJson(get("/v2/projects?ids=" + encode(GSON.toJson(array(projectIds)))), JsonArray.class);
        if (projects == null) {
            return titles;
        }
        for (JsonElement element : projects) {
            JsonObject project = element.getAsJsonObject();
            titles.put(project.get("id").getAsString(), project.get("title").getAsString());
        }
        return titles;
    }

    /** The newest version of a project for this Minecraft version and loader, if there is one. */
    public Optional<Version> latestForProject(String projectId, String gameVersion, String loader)
            throws IOException, InterruptedException {
        JsonArray versions = GSON.fromJson(get("/v2/project/" + encode(projectId) + "/version?loaders="
                + encode(GSON.toJson(array(List.of(loader)))) + "&game_versions="
                + encode(GSON.toJson(array(List.of(gameVersion))))), JsonArray.class);
        Version newest = null;
        if (versions == null) {
            return Optional.empty();
        }
        for (JsonElement element : versions) {
            Version version = parseVersion(element.getAsJsonObject());
            if (version != null && (newest == null || version.published().isAfter(newest.published()))) {
                newest = version;
            }
        }
        return Optional.ofNullable(newest);
    }

    /** Downloads a version's file to target, checking it against the SHA-1 Modrinth published for it. */
    public void download(Version version, Path target, Progress progress) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(version.url()))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", userAgent)
                .GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Modrinth download of " + version.fileName() + " failed (HTTP " + response.statusCode() + ")");
        }
        MessageDigest digest = sha1();
        long done = 0;
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                done += read;
                progress.bytes(done);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(version.sha1())) {
            Files.deleteIfExists(target);
            throw new IOException("The download of " + version.fileName() + " doesn't match Modrinth's fingerprint");
        }
    }

    public static String sha1Of(Path file) throws IOException {
        MessageDigest digest = sha1();
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // --- Parsing ---

    private static Map<String, Version> parseVersionMap(String json) {
        Map<String, Version> result = new HashMap<>();
        JsonObject map = GSON.fromJson(json, JsonObject.class);
        if (map == null) {
            return result;
        }
        map.entrySet().forEach(entry -> {
            Version version = parseVersion(entry.getValue().getAsJsonObject());
            if (version != null) {
                result.put(entry.getKey().toLowerCase(), version);
            }
        });
        return result;
    }

    private static Version parseVersion(JsonObject json) {
        JsonArray files = json.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            return null;
        }
        JsonObject file = files.get(0).getAsJsonObject();
        for (JsonElement element : files) {
            if (element.getAsJsonObject().has("primary") && element.getAsJsonObject().get("primary").getAsBoolean()) {
                file = element.getAsJsonObject();
                break;
            }
        }
        List<String> required = new ArrayList<>();
        JsonArray dependencies = json.getAsJsonArray("dependencies");
        if (dependencies != null) {
            for (JsonElement element : dependencies) {
                JsonObject dependency = element.getAsJsonObject();
                JsonElement project = dependency.get("project_id");
                if ("required".equals(dependency.get("dependency_type").getAsString()) && project != null && !project.isJsonNull()) {
                    required.add(project.getAsString());
                }
            }
        }
        return new Version(json.get("id").getAsString(), json.get("project_id").getAsString(),
                json.get("version_number").getAsString(), Instant.parse(json.get("date_published").getAsString()),
                file.get("filename").getAsString(), file.get("url").getAsString(),
                file.getAsJsonObject("hashes").get("sha1").getAsString().toLowerCase(), file.get("size").getAsLong(), required);
    }

    // --- HTTP ---

    private String get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    private String post(String path, JsonObject body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))));
    }

    private String send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(builder.timeout(Duration.ofSeconds(30)).header("User-Agent", userAgent).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null; // Unknown project or version: callers treat it as "nothing found".
        }
        if (response.statusCode() != 200) {
            throw new IOException("Modrinth request failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        return response.body();
    }

    private static JsonArray array(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
